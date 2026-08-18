package com.masahhisabat.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * مزامنة محلية بين الأجهزة المتصلة بشبكة Wi-Fi نفسها دون خادم سحابي.
 * - كل جهاز يملك خادم TCP يستمع على PORT ويقبل طلبات مزامنة.
 * - الاكتشاف يتم عبر بث UDP (port 8766): كل جهاز يرد بهاتف يعرف عنوانه.
 * - تبادل البيانات: JSON شامل (users + groups + items + صور مضمنة base64).
 */
object SyncManager {

    private const val TAG = "SyncManager"
    private const val TCP_PORT = 8765
    private const val UDP_PORT = 8766
    private val gson = Gson()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var isServing = false
    val peers = CopyOnWriteArrayList<String>()

    // ---------- إدارة الخادم ----------
    fun startServer(context: Context) {
        if (isServing) return
        Thread {
            try {
                server = ServerSocket(TCP_PORT)
                isServing = true
                AppRepository.logSync(SyncEntry("بدء الاستماع", "خادم المزامنة نشط", true))
                while (isServing) {
                    val client = try { server?.accept() } catch (_: Throwable) { break } ?: break
                    handleClient(client, context)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "server error", e)
                isServing = false
            }
        }.name = "sync-server"
        Thread {
            while (isServing) {
                udpListener()
                try { Thread.sleep(800) } catch (_: InterruptedException) { break }
            }
        }.name = "udp-discovery"
    }

    fun stopServer() {
        isServing = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
    }

    private fun handleClient(socket: Socket, context: Context) {
        Thread {
            try {
                socket.soTimeout = 60000
                val reader = socket.getInputStream().bufferedReader()
                val payloadJson = reader.readLine() ?: return@Thread
                val payload = gson.fromJson(payloadJson, SyncPayload::class.java)
                val result = applyPayload(context, payload)
                // رد: عدد العناصر المستقبلة + عدد المستخدمين المستقبلة
                val ack = "OK ${result.items} ${result.users}"
                socket.getOutputStream().write((ack + "\n").toByteArray())
                socket.getOutputStream().flush()
                AppRepository.logSync(SyncEntry("استقبال", "استُقبلت ${result.items} عناصر و${result.users} مستخدمين من ${socket.inetAddress.hostAddress}", true))
            } catch (e: Throwable) {
                Log.e(TAG, "client error", e)
                AppRepository.logSync(SyncEntry("استقبال", "فشل: ${e.message}", false))
            } finally {
                try { socket.close() } catch (_: Throwable) {}
            }
        }.name = "sync-client-handler"
    }

    // ---------- العميل: الاتصال والارسال ----------
    /** إرسال بيانات هذا الجهاز إلى جهاز آخر (يستقبلها بدوره) */
    fun syncWithHost(context: Context, host: String): SyncResult {
        try {
            val socket = Socket(InetAddress.getByName(host), TCP_PORT)
            socket.soTimeout = 120000
            val payload = buildPayload(context)
            val writer = socket.getOutputStream().bufferedWriter()
            writer.write(gson.toJson(payload))
            writer.newLine()
            writer.flush()
            val reader = socket.getInputStream().bufferedReader()
            val ack = reader.readLine() ?: "FAIL"
            socket.close()
            val parts = ack.split(" ")
            val ok = parts[0] == "OK"
            val gotItems = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val gotUsers = parts.getOrNull(2)?.toIntOrNull() ?: 0
            AppRepository.logSync(SyncEntry("إرسال", "إلى $host: أُرسلت ${payload.users.size} مستخدمين و${payload.totalItems} عناصر — استُقبلت $gotItems عناصر و$gotUsers مستخدمين", ok))
            return SyncResult(true, gotItems, gotUsers)
        } catch (e: Throwable) {
            Log.e(TAG, "sync error", e)
            AppRepository.logSync(SyncEntry("إرسال", "فشل الاتصال بـ $host: ${e.message}", false))
            return SyncResult(false, 0, 0)
        }
    }

    private fun buildPayload(context: Context): SyncPayload {
        return SyncPayload(
            users = AppRepository.users().map { UserPayload(it.username, it.passwordHash, it.role.name, it.enabled) },
            items = buildList {
                for (g in AppRepository.groups()) {
                    for (item in AppRepository.items(g.id)) {
                        add(SyncItemPayload(g.id, g.name, item))
                    }
                }
            }
        )
    }

    /** تطبيق بيانات مستقبلة ومزامنتها مع المحلي: المستخدمون يُدمجون (إضافة الجدد، تحديث كلمات المرور)، والبيانات تُضاف. */
    private fun applyPayload(context: Context, payload: SyncPayload): ApplyResult {
        var addedUsers = 0
        // 1) مزامنة المستخدمين: دمج حسب اسم المستخدم
        val local = AppRepository.users().toMutableList()
        val localNames = local.map { it.username }.toMutableSet()
        for (u in payload.users) {
            val role = try { Role.valueOf(u.roleName) } catch (_: Exception) { Role.VIEWER }
            if (u.username !in localNames) {
                // مستخدم جديد على هذا الجهاز — أضيفه
                local.add(User(u.username, u.passwordHash, role, enabled = u.enabled))
                localNames.add(u.username)
                addedUsers++
            } else {
                // موجود محليًا: إذا كان محليًا غير مفعّل أو بدون كلمة مرور صحيحة، حدثّ كلمة المرور (لأول مرة فقط أو عند مزامنة)
                val existing = local.find { it.username == u.username }
                if (existing != null && existing.passwordHash.isBlank()) {
                    local.replaceAll { if (it.username == u.username) it.copy(passwordHash = u.passwordHash, role = role) else it }
                }
            }
        }
        // المالك المحلي (mustafa الافتراضي) لا يُمس إن لم يُرسل من الطرف الآخر
        AppRepository.saveList("users.json", local)

        // 2) مزامنة المجموعات والعناصر
        var addedItems = 0
        for (p in payload.items) {
            var group = AppRepository.groups().find { it.id == p.groupId }
            if (group == null) {
                group = Group(p.groupId, p.groupName)
                AppRepository.addGroup(group)
            }
            val exists = AppRepository.items(p.groupId).any { it.id == p.item.id }
            if (!exists) {
                AppRepository.addItem(p.groupId, p.item)
                addedItems++
            }
        }
        return ApplyResult(addedItems, addedUsers)
    }

    // ---------- الاكتشاف عبر UDP ----------
    /** الاستماع لبث UDP والرد بعنوان الجهاز */
    private fun udpListener() {
        try {
            DatagramSocket(UDP_PORT).use { socket ->
                socket.soTimeout = 700
                val buf = ByteArray(512)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length).trim()
                    if (msg.startsWith("WHO_IS_HERE")) {
                        // رد بعنواننا + الاسم
                        val me = AppRepository.currentUserDeviceName()
                        val reply = ("HERE " + java.net.NetworkInterface.getNetworkInterfaces()
                            .toList().flatMap { it.inetAddresses.toList() }
                            .filter { !it.isLoopbackAddress && it.hostAddress.contains(".") }
                            .firstOrNull()?.hostAddress ?: "unknown") + " " + me
                        val resp = DatagramPacket(reply.toByteArray(), reply.length, packet.address, packet.port)
                        socket.send(resp)
                        peers.addIfAbsent(packet.address.hostAddress)
                    }
                } catch (_: Exception) { /* timeout */ }
            }
        } catch (_: Throwable) {}
    }

    /** إرسال استفسار عبر البث وإرجاع الأجهزة المكتشفة */
    fun discover(timeoutMs: Long = 2500): List<DiscoveredPeer> {
        val found = CopyOnWriteArrayList<DiscoveredPeer>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs.toInt()
                socket.broadcast = true
                val msg = "WHO_IS_HERE"
                val packet = DatagramPacket(msg.toByteArray(), msg.length, InetAddress.getByName("255.255.255.255"), UDP_PORT)
                try { socket.send(packet) } catch (_: Throwable) {}
                val buf = ByteArray(512)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < timeoutMs) {
                    try {
                        val resp = DatagramPacket(buf, buf.size)
                        socket.receive(resp)
                        val text = String(resp.data, 0, resp.length).trim()
                        if (text.startsWith("HERE ")) {
                            val parts = text.removePrefix("HERE ").split(" ", limit = 2)
                            found.addIfAbsent(DiscoveredPeer(parts[0], parts.getOrNull(1) ?: parts[0]))
                        }
                    } catch (_: Exception) { break }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "discover error", e)
        }
        return found.toList()
    }

    fun ensureServer(context: Context) {
        if (!isServing) startServer(context)
    }

    data class DiscoveredPeer(val address: String, val name: String)
    data class SyncResult(val ok: Boolean, val itemsReceived: Int, val usersReceived: Int)

    // ---------- نماذج المزامنة ----------
    data class UserPayload(val username: String, val passwordHash: String, val roleName: String, val enabled: Boolean)
    data class SyncItemPayload(val groupId: String, val groupName: String, val item: InvoiceItem)
    data class SyncPayload(val users: List<UserPayload>, val items: List<SyncItemPayload>) {
        val totalItems get() = items.size
    }
    data class ApplyResult(val items: Int, val users: Int)
}
