package com.masahhisabat.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Base64InputStream
import android.util.Base64OutputStream
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.masahhisabat.app.BuildConfig
import com.masahhisabat.app.R
import com.google.gson.Gson
import java.io.EOFException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ConnectException
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
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
    private const val UPDATE_PORT = 8767
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val SYNC_READ_TIMEOUT_MS = 35_000
    private const val UPDATE_READ_TIMEOUT_MS = 60_000
    private const val MAX_UPDATE_BYTES = 250L * 1024L * 1024L
    private const val UPDATE_NOTIFICATION_CHANNEL = "local_update_ready"
    private const val UPDATE_NOTIFICATION_ID = 9_303
    private const val SELF_TEST_TIMEOUT_MS = 600
    private const val AUTO_SYNC_DISCOVERY_TIMEOUT_MS = 1_500L
    private const val AUTO_SYNC_INTERVAL_MS = 30_000L
    private const val AUTO_USER_SYNC_MODE = "mustafa_users_only"
    private const val AUTO_DATA_SYNC_MODE = "automatic_full_app_sync"
    private const val AUTO_DATA_SYNC_ATTEMPTS = 2
    private const val AUTO_DATA_SYNC_RETRY_DELAY_MS = 4_000L
    private const val AUTO_DATA_SYNC_MIN_RESTART_MS = 30_000L
    private const val AUTO_DATA_SYNC_WAKE_LOCK_MS = 90_000L
    private const val SINGLE_TRANSFER_WAKE_LOCK_MS = 45_000L
    private const val AUTO_UPDATE_CHECK_ATTEMPTS = 3
    private const val AUTO_UPDATE_CHECK_RETRY_DELAY_MS = 10_000L
    private const val MAX_SYNC_IMAGE_BYTES = 12L * 1024L * 1024L
    private const val SYNC_IO_BUFFER_BYTES = 48 * 1024
    private val gson = Gson()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var updateServer: ServerSocket? = null
    @Volatile private var isServing = false
    @Volatile private var autoSyncRunning = false
    @Volatile private var autoSyncThread: Thread? = null
    @Volatile private var autoDataSyncRunning = false
    @Volatile private var autoDataSyncThread: Thread? = null
    @Volatile private var lastAutoDataSyncStartedAt = 0L
    @Volatile private var updateCheckRunning = false
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    val peers = CopyOnWriteArrayList<String>()
    private val callSignalListeners = CopyOnWriteArrayList<(CallSignal, String) -> Unit>()

    fun addCallSignalListener(listener: (CallSignal, String) -> Unit) {
        callSignalListeners.addIfAbsent(listener)
    }

    fun removeCallSignalListener(listener: (CallSignal, String) -> Unit) {
        callSignalListeners.remove(listener)
    }

    fun sendCallSignal(host: String, signal: CallSignal): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, TCP_PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = SYNC_READ_TIMEOUT_MS
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write("CALL ${gson.toJson(signal)}")
                writer.newLine()
                writer.flush()
                socket.getInputStream().bufferedReader().readLine()?.startsWith("CALL_OK") == true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "call signal failed", e)
            false
        }
    }

    /** بث إشارة المكالمة للأجهزة المكتشفة؛ الجهاز غير المعني يتجاهلها وفق toUser. */
    fun broadcastCallSignal(signal: CallSignal): Int {
        var delivered = 0
        peers.filter { !isLocalAddress(it) }.distinct().forEach { host ->
            if (sendCallSignal(host, signal)) delivered++
        }
        return delivered
    }

    private val autoSyncedUserFiles = ConcurrentHashMap<String, String>()
    private val autoSyncedDataFiles = ConcurrentHashMap<String, String>()

    // ---------- إدارة الخادم ----------
    fun startServer(context: Context) {
        if (isServing) return
        acquireMulticastLock(context)
        // تُضبط قبل إطلاق الخيوط حتى لا تنتهي خدمة اكتشاف UDP بسبب سباق بدء التشغيل.
        isServing = true
        Thread {
            try {
                server = ServerSocket(TCP_PORT)
                AppRepository.logSync(SyncEntry("بدء الاستماع", "خادم المزامنة نشط", true))
                while (isServing) {
                    val client = try { server?.accept() } catch (_: Throwable) { break } ?: break
                    handleClient(client, context)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "server error", e)
                AppRepository.logSync(SyncEntry("بدء الاستماع", "تعذر بدء خادم المزامنة: ${syncErrorMessage(e)}", false))
                isServing = false
            } finally {
                try { server?.close() } catch (_: Throwable) {}
                server = null
            }
        }.apply {
            name = "sync-server"
            start()
        }
        Thread {
            while (isServing) {
                udpListener()
                try { Thread.sleep(800) } catch (_: InterruptedException) { break }
            }
        }.apply {
            name = "udp-discovery"
            start()
        }
        Thread {
            try {
                updateServer = ServerSocket(UPDATE_PORT)
                while (isServing) {
                    val client = try { updateServer?.accept() } catch (_: Throwable) { break } ?: break
                    handleUpdateClient(client, context.applicationContext)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "تعذر بدء خادم التحديث المحلي", e)
            } finally {
                try { updateServer?.close() } catch (_: Throwable) {}
                updateServer = null
            }
        }.apply {
            name = "local-update-server"
            start()
        }
    }

    fun stopServer() {
        isServing = false
        autoSyncRunning = false
        autoSyncThread?.interrupt()
        autoSyncThread = null
        autoDataSyncRunning = false
        autoDataSyncThread?.interrupt()
        autoDataSyncThread = null
        autoSyncedUserFiles.clear()
        autoSyncedDataFiles.clear()
        try { server?.close() } catch (_: Throwable) {}
        try { updateServer?.close() } catch (_: Throwable) {}
        server = null
        updateServer = null
        releaseMulticastLock()
    }

    /**
     * فحص محدود أثناء فتح التطبيق؛ يعيد المحاولة عدة مرات حتى يلتقط جهازًا اتصل بالشبكة بعد التشغيل.
     * لا يستخدم خدمة خلفية أو إشعارًا دائمًا.
     */
    fun startAutomaticUpdateCheck(context: Context) {
        if (updateCheckRunning) return
        updateCheckRunning = true
        val appContext = context.applicationContext
        Thread {
            try {
                ensureServer(appContext)
                val myVersion = BuildConfig.VERSION_CODE.toLong()
                var updateFound = false
                for (attempt in 0 until AUTO_UPDATE_CHECK_ATTEMPTS) {
                    if (attempt > 0) Thread.sleep(AUTO_UPDATE_CHECK_RETRY_DELAY_MS)
                    val peer = discover(1_800)
                        .filter { !isLocalAddress(it.address) && it.versionCode > myVersion }
                        .maxByOrNull { it.versionCode }
                    if (peer != null && requestLocalUpdate(appContext, peer)) {
                        updateFound = true
                        break
                    }
                }
                if (updateFound) Log.i(TAG, "تم العثور على تحديث محلي وتجهيزه للتثبيت")
            } catch (e: Throwable) {
                Log.w(TAG, "فشل فحص التحديث المحلي", e)
            } finally {
                updateCheckRunning = false
            }
        }.apply {
            name = "local-update-check"
            start()
        }
    }

    /** يرسل APK التطبيق الجاري فقط إذا كان أحدث من الإصدار الذي طلبه الجهاز الآخر. */
    private fun handleUpdateClient(socket: Socket, context: Context) {
        Thread {
            socket.use { client ->
                try {
                    client.soTimeout = UPDATE_READ_TIMEOUT_MS
                    val request = readNetworkLine(client.getInputStream())
                    val requesterVersion = request?.removePrefix("GET_UPDATE ")?.trim()?.toLongOrNull()
                        ?: throw IOException("طلب تحديث محلي غير صالح")
                    val sourceApk = File(context.applicationInfo.sourceDir)
                    val currentVersion = BuildConfig.VERSION_CODE.toLong()
                    if (currentVersion <= requesterVersion || !sourceApk.isFile) {
                        writeUpdateHeader(client, UpdateHeader(false, currentVersion, BuildConfig.VERSION_NAME, 0L, message = "لا يوجد إصدار أحدث"))
                        return@use
                    }
                    val size = sourceApk.length()
                    if (size !in 1..MAX_UPDATE_BYTES) throw IOException("حجم ملف التحديث غير صالح")
                    writeUpdateHeader(client, UpdateHeader(true, currentVersion, BuildConfig.VERSION_NAME, size, sha256(sourceApk)))
                    FileInputStream(sourceApk).use { input ->
                        val output = client.getOutputStream()
                        input.copyTo(output, 64 * 1024)
                        output.flush()
                    }
                    AppRepository.logSync(SyncEntry("إرسال تحديث", "أُرسل الإصدار ${BuildConfig.VERSION_NAME} إلى ${client.inetAddress.hostAddress}", true))
                } catch (e: Throwable) {
                    Log.w(TAG, "فشل إرسال التحديث المحلي", e)
                    AppRepository.logSync(SyncEntry("إرسال تحديث", "تعذر إرسال ملف التحديث", false))
                }
            }
        }.apply {
            name = "local-update-client"
            start()
        }
    }

    /** ينزّل الجهاز الأقدم APK من الجهاز المكتشف ويتحقق من سلامته قبل عرضه لمثبّت أندرويد. */
    private fun requestLocalUpdate(context: Context, peer: DiscoveredPeer): Boolean {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(peer.address, UPDATE_PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = UPDATE_READ_TIMEOUT_MS
                socket.getOutputStream().apply {
                    write("GET_UPDATE ${BuildConfig.VERSION_CODE}\n".toByteArray())
                    flush()
                }
                val headerLine = readNetworkLine(socket.getInputStream())
                    ?: throw EOFException("لم تصل معلومات التحديث")
                val header = gson.fromJson(headerLine, UpdateHeader::class.java)
                    ?: throw IOException("معلومات التحديث غير صالحة")
                if (!header.ok || header.versionCode <= BuildConfig.VERSION_CODE) return false
                if (header.size !in 1..MAX_UPDATE_BYTES || header.sha256.isNullOrBlank()) {
                    throw IOException("حجم أو تحقق ملف التحديث غير صالح")
                }

                val updateDir = File(context.cacheDir, "local_updates").apply { mkdirs() }
                val partialFile = File(updateDir, "masah-hisabat-${header.versionCode}.apk.part")
                val targetFile = File(updateDir, "masah-hisabat-${header.versionCode}.apk")
                val digest = MessageDigest.getInstance("SHA-256")
                var remaining = header.size
                partialFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    val input = socket.getInputStream()
                    while (remaining > 0) {
                        val requested = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = input.read(buffer, 0, requested)
                        if (read < 0) throw EOFException("انقطع تنزيل التحديث قبل اكتماله")
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        remaining -= read
                    }
                    output.flush()
                }
                val downloadedHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!downloadedHash.equals(header.sha256, ignoreCase = true)) {
                    partialFile.delete()
                    throw IOException("فشل التحقق من سلامة ملف التحديث")
                }
                if (targetFile.exists()) targetFile.delete()
                if (!partialFile.renameTo(targetFile)) throw IOException("تعذر تجهيز ملف التحديث")
                AppRepository.logSync(SyncEntry("تنزيل تحديث", "تم تنزيل الإصدار ${header.versionName} من ${peer.name}", true))
                Handler(Looper.getMainLooper()).post {
                    notifyUpdateReady(context, targetFile, header.versionName, peer.name)
                    requestPackageInstall(context, targetFile)
                }
                return true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "فشل تنزيل التحديث المحلي", e)
            AppRepository.logSync(SyncEntry("تنزيل تحديث", "تعذر تنزيل التحديث من ${peer.name}", false))
            return false
        }
    }

    private fun writeUpdateHeader(socket: Socket, header: UpdateHeader) {
        socket.getOutputStream().apply {
            write((gson.toJson(header) + "\n").toByteArray())
            flush()
        }
    }

    private fun readNetworkLine(input: java.io.InputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 8_192) {
            val next = input.read()
            if (next < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
            if (next == '\n'.code) return bytes.toByteArray().toString(Charsets.UTF_8).trimEnd('\r')
            bytes.add(next.toByte())
        }
        throw IOException("سطر شبكة طويل بصورة غير صالحة")
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requestPackageInstall(context: Context, file: File) {
        try {
            context.startActivity(buildInstallOrPermissionIntent(context, file))
        } catch (e: Throwable) {
            Log.w(TAG, "تعذر فتح مثبّت أندرويد", e)
            AppRepository.logSync(SyncEntry("تحديث جاهز", "تم تنزيل التحديث لكن تعذر فتح مثبّت أندرويد.", false))
        }
    }

    private fun buildInstallOrPermissionIntent(context: Context, file: File): Intent {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun notifyUpdateReady(context: Context, file: File, versionName: String, peerName: String) {
        val installIntent = try { buildInstallOrPermissionIntent(context, file) } catch (e: Throwable) {
            Log.w(TAG, "تعذر تجهيز إجراء إشعار التحديث", e)
            Toast.makeText(context, "تم تنزيل تحديث $versionName من $peerName", Toast.LENGTH_LONG).show()
            return
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        Toast.makeText(context, "تم تنزيل تحديث $versionName بنجاح من $peerName", Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(
                UPDATE_NOTIFICATION_CHANNEL,
                "تحديثات محلية",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "إشعارات اكتمال تحديث التطبيق بين الأجهزة" })
            val notification = NotificationCompat.Builder(context, UPDATE_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_sync)
                .setContentTitle("تم تنزيل التحديث بنجاح")
                .setContentText("الإصدار $versionName جاهز للتثبيت من $peerName")
                .setStyle(NotificationCompat.BigTextStyle().bigText("تم نقل الإصدار $versionName والتحقق من سلامته. اضغط لتثبيته."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_sync, "تثبيت التحديث", pendingIntent)
                .build()
            NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (e: Throwable) {
            Log.w(TAG, "تعذر عرض إشعار التحديث", e)
        }
    }

    private fun handleClient(socket: Socket, context: Context) {
        Thread {
            socket.use { client ->
                val clientAddress = client.inetAddress?.hostAddress?.takeIf { it.isNotBlank() } ?: "عنوان غير معروف"
                try {
                    client.soTimeout = SYNC_READ_TIMEOUT_MS
                    val reader = client.getInputStream().bufferedReader()
                    val payloadJson = reader.readLine()
                        ?: throw EOFException("انقطع الاتصال قبل اكتمال بيانات المزامنة")
                    if (payloadJson.startsWith("CALL ")) {
                        val signal = gson.fromJson(payloadJson.removePrefix("CALL "), CallSignal::class.java)
                            ?: throw IOException("إشارة مكالمة غير صالحة")
                        callSignalListeners.forEach { listener ->
                            try { listener(signal, clientAddress) } catch (listenerError: Throwable) {
                                Log.w(TAG, "call signal listener failed", listenerError)
                            }
                        }
                        client.getOutputStream().bufferedWriter().use { writer ->
                            writer.write("CALL_OK\n")
                            writer.flush()
                        }
                        return@use
                    }
                    val payload = gson.fromJson(payloadJson, SyncPayload::class.java)
                        ?: throw IOException("بيانات المزامنة غير صالحة")
                    val preview = previewPayload(payload)
                    AppRepository.logSync(SyncEntry(
                        "معاينة استقبال",
                        "من $clientAddress: ستُضاف ${preview.newItems} عناصر و${preview.newUsers} مستخدمين و${preview.newGroups} مجموعات.",
                        true
                    ))
                    val isMustafaUserFile = payload.mode == AUTO_USER_SYNC_MODE
                    val isAutomaticDataSync = payload.mode == AUTO_DATA_SYNC_MODE
                    if (isMustafaUserFile && AppRepository.normalizeUsername(payload.sourceUsername.orEmpty()) != "mustafa") {
                        throw IOException("مصدر ملف المستخدمين غير معتمد")
                    }
                    if (preview.hasChanges || (isMustafaUserFile && payload.users.isNotEmpty())) {
                        val backup = try { AppRepository.createSafetyBackup() } catch (_: Throwable) { null }
                            ?: throw IOException("تعذر إنشاء نسخة احتياطية وقائية قبل المزامنة")
                        AppRepository.logSync(SyncEntry("نسخة احتياطية تلقائية", "حُفظت نسخة وقائية: ${backup.name}", true))
                    }
                    val result = if (isMustafaUserFile) applyAuthoritativeUsers(payload) else applyPayload(context, payload)
                    // رد: عدد العناصر المستقبلة + عدد المستخدمين المستقبلة.
                    val ack = "OK ${result.items} ${result.users}\n"
                    client.getOutputStream().write(ack.toByteArray())
                    if (isAutomaticDataSync) {
                        // تعيد المزامنة التلقائية بيانات هذا الجهاز في الجلسة نفسها،
                        // وبذلك يصل كل طرف إلى أحدث المجموعات والفواتير دون طلب يدوي ثانٍ.
                        client.getOutputStream().write(gson.toJson(buildAutomaticDataPayload()).toByteArray())
                        client.getOutputStream().write("\n".toByteArray())
                    }
                    client.getOutputStream().flush()
                    val sender = payload.deviceName?.takeIf { it.isNotBlank() } ?: clientAddress
                    val action = when {
                        isMustafaUserFile -> "استقبال تلقائي للمستخدمين"
                        isAutomaticDataSync -> "استقبال تلقائي للبيانات"
                        else -> "استقبال"
                    }
                    val detail = if (isMustafaUserFile) {
                        "تم تحديث ${result.users} حسابًا من جهاز mustafa: $sender"
                    } else if (isAutomaticDataSync) {
                        "تم دمج ${result.items} فاتورة ورسالة من $sender بعد تسجيل الدخول."
                    } else {
                        "استُقبلت ${result.items} عناصر و${result.users} مستخدمين من $sender"
                    }
                    AppRepository.logSync(SyncEntry(action, detail, true))
                    recordSyncDevice(clientAddress, sender, success = true)
                } catch (e: Throwable) {
                    Log.e(TAG, "client error", e)
                    val errorMessage = syncErrorMessage(e)
                    AppRepository.logSync(SyncEntry("استقبال", "فشلت المزامنة: $errorMessage", false))
                    recordSyncDevice(clientAddress, clientAddress, success = false, error = errorMessage)
                }
            }
        }.apply {
            name = "sync-client-handler"
            start()
        }
    }

    // ---------- العميل: الاتصال والارسال ----------
    /** إرسال بيانات هذا الجهاز إلى جهاز آخر (يستقبلها بدوره) */
    fun syncWithHost(
        context: Context,
        host: String,
        peerName: String = host,
        onProgress: (percent: Int, status: String) -> Unit = { _, _ -> }
    ): SyncResult {
        var payload: SyncPayload? = null
        try {
            onProgress(10, "جارٍ الاتصال بالجهاز الآخر...")
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, TCP_PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = SYNC_READ_TIMEOUT_MS
                onProgress(25, "تم الاتصال. جارٍ تجهيز البيانات...")
                val currentPayload = buildPayload(context)
                payload = currentPayload
                onProgress(45, "جارٍ إرسال ${currentPayload.totalItems} عناصر و${currentPayload.users.size} مستخدمين...")
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write(gson.toJson(currentPayload))
                writer.newLine()
                writer.flush()
                onProgress(75, "اكتمل الإرسال. جارٍ انتظار تأكيد الجهاز الآخر...")
                val ack = socket.getInputStream().bufferedReader().readLine()
                    ?: throw EOFException("انقطع الاتصال قبل تأكيد المزامنة")
                val parts = ack.trim().split(Regex("\\s+"))
                val gotItems = parts.getOrNull(1)?.toIntOrNull()
                val gotUsers = parts.getOrNull(2)?.toIntOrNull()
                if (parts.firstOrNull() != "OK" || gotItems == null || gotUsers == null) {
                    throw IOException("استجابة غير مكتملة من الجهاز الآخر")
                }
                AppRepository.logSync(SyncEntry("إرسال", "إلى $peerName: أُرسلت ${currentPayload.users.size} مستخدمين و${currentPayload.totalItems} عناصر — استُقبلت $gotItems عناصر و$gotUsers مستخدمين", true))
                recordSyncDevice(host, peerName, success = true)
                onProgress(100, "اكتملت المزامنة بنجاح")
                return SyncResult(true, gotItems, gotUsers)
            }
        } catch (e: Throwable) {
            val errorMessage = syncErrorMessage(e)
            Log.e(TAG, "sync error", e)
            val sentSummary = payload?.let { " بعد تجهيز ${it.totalItems} عناصر و${it.users.size} مستخدمين" }.orEmpty()
            AppRepository.logSync(SyncEntry("إرسال", "فشلت المزامنة مع $peerName$sentSummary: $errorMessage", false))
            recordSyncDevice(host, peerName, success = false, error = errorMessage)
            onProgress(0, errorMessage)
            return SyncResult(false, 0, 0, errorMessage)
        }
    }

    /** يرسل ملف المستخدمين فقط من جهاز mustafa، دون مجموعات أو فواتير أو صور. */
    private fun syncMustafaUsersWithHost(host: String, peerName: String): SyncResult {
        val payload = buildMustafaUsersPayload()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, TCP_PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = SYNC_READ_TIMEOUT_MS
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write(gson.toJson(payload))
                writer.newLine()
                writer.flush()
                val ack = socket.getInputStream().bufferedReader().readLine()
                    ?: throw EOFException("انقطع الاتصال قبل تأكيد مزامنة المستخدمين")
                val parts = ack.trim().split(Regex("\\s+"))
                val changedUsers = parts.getOrNull(2)?.toIntOrNull()
                if (parts.firstOrNull() != "OK" || changedUsers == null) {
                    throw IOException("استجابة غير مكتملة من الجهاز الآخر")
                }
                AppRepository.logSync(SyncEntry(
                    "إرسال تلقائي للمستخدمين",
                    "إلى $peerName: تم إرسال ${payload.users.size} حسابًا وتحديث $changedUsers حسابًا.",
                    true
                ))
                recordSyncDevice(host, peerName, success = true)
                return SyncResult(true, 0, changedUsers)
            }
        } catch (e: Throwable) {
            val errorMessage = syncErrorMessage(e)
            AppRepository.logSync(SyncEntry(
                "إرسال تلقائي للمستخدمين",
                "تعذر إرسال ملف المستخدمين إلى $peerName: $errorMessage",
                false
            ))
            recordSyncDevice(host, peerName, success = false, error = errorMessage)
            return SyncResult(false, 0, 0, errorMessage)
        }
    }

    /**
     * مزامنة تلقائية ثنائية الاتجاه لكامل بيانات التطبيق بعد فتح جلسة المستخدم.
     * تشمل الحسابات والمجموعات والرسائل والمرفقات وسجل السلة، مع دمج غير هدّام.
     */
    private fun syncAutomaticDataWithHost(context: Context, host: String, peerName: String): SyncResult {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, TCP_PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = SYNC_READ_TIMEOUT_MS
                val outgoing = buildAutomaticDataPayload()
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write(gson.toJson(outgoing))
                writer.newLine()
                writer.flush()

                val reader = socket.getInputStream().bufferedReader()
                val ack = reader.readLine() ?: throw EOFException("انقطع الاتصال قبل تأكيد مزامنة البيانات")
                val parts = ack.trim().split(Regex("\\s+"))
                if (parts.firstOrNull() != "OK") throw IOException("استجابة غير مكتملة من الجهاز الآخر")

                val returnJson = reader.readLine()
                    ?: throw EOFException("لم تصل بيانات المجموعات والفواتير من الجهاز الآخر")
                val returnedPayload = gson.fromJson(returnJson, SyncPayload::class.java)
                    ?: throw IOException("بيانات المجموعات والفواتير غير صالحة")
                if (returnedPayload.mode != AUTO_DATA_SYNC_MODE) {
                    throw IOException("استجابة مزامنة غير متوقعة")
                }

                val preview = previewPayload(returnedPayload)
                if (preview.hasChanges) {
                    val backup = AppRepository.createSafetyBackup()
                    AppRepository.logSync(SyncEntry("نسخة احتياطية تلقائية", "حُفظت نسخة وقائية: ${backup.name}", true))
                }
                val received = applyPayload(context, returnedPayload)
                AppRepository.logSync(SyncEntry(
                    "مزامنة تلقائية للبيانات",
                    "بعد تسجيل الدخول مع $peerName: أُرسلت ${outgoing.totalItems} عنصرًا واستُقبلت ${received.items} عنصرًا.",
                    true
                ))
                recordSyncDevice(host, peerName, success = true)
                return SyncResult(true, received.items, received.users)
            }
        } catch (e: Throwable) {
            val errorMessage = syncErrorMessage(e)
            AppRepository.logSync(SyncEntry(
                "مزامنة تلقائية للبيانات",
                "تعذر مزامنة المجموعات والفواتير مع $peerName: $errorMessage",
                false
            ))
            recordSyncDevice(host, peerName, success = false, error = errorMessage)
            return SyncResult(false, 0, 0, errorMessage)
        }
    }

    /**
     * يبدأ الاكتشاف التلقائي في الواجهة الأمامية. كل الأجهزة تستقبل محليًا،
     * لكن جلسة mustafa وحدها ترسل ملف المستخدمين إلى الأجهزة المكتشفة.
     */
    fun startAutomaticUserSync(context: Context) {
        val appContext = context.applicationContext
        ensureServer(appContext)
        if (!isMustafaSession(appContext) || autoSyncRunning) return
        autoSyncRunning = true
        autoSyncThread = Thread {
            try {
                while (isServing && autoSyncRunning && isMustafaSession(appContext)) {
                    val fingerprint = userFileFingerprint()
                    discover(AUTO_SYNC_DISCOVERY_TIMEOUT_MS)
                        .asSequence()
                        .filterNot { isLocalAddress(it.address) }
                        .distinctBy { it.address }
                        .forEach { peer ->
                            if (autoSyncedUserFiles[peer.address] != fingerprint) {
                                val wakeLock = acquireTransientWakeLock(
                                    appContext,
                                    "user-transfer",
                                    SINGLE_TRANSFER_WAKE_LOCK_MS
                                )
                                val result = try {
                                    syncMustafaUsersWithHost(peer.address, peer.name)
                                } finally {
                                    releaseWakeLock(wakeLock)
                                }
                                if (result.ok) autoSyncedUserFiles[peer.address] = fingerprint
                            }
                        }
                    try { Thread.sleep(AUTO_SYNC_INTERVAL_MS) } catch (_: InterruptedException) { break }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "automatic user sync error", e)
                AppRepository.logSync(SyncEntry("مزامنة المستخدمين التلقائية", syncErrorMessage(e), false))
            } finally {
                autoSyncRunning = false
                autoSyncThread = null
            }
        }.apply {
            name = "mustafa-user-auto-sync"
            start()
        }
    }

    /**
     * يبدأ عند انتقال المستخدم إلى التطبيق بعد تسجيل الدخول، ويعيد الاكتشاف عدة مرات
     * لالتقاط الأجهزة التي فتحت التطبيق بعده بلحظات. المزامنة دمجية ولا تحذف بيانات محلية.
     */
    fun startAutomaticDataSyncAfterLogin(context: Context) {
        val appContext = context.applicationContext
        ensureServer(appContext)
        val now = SystemClock.elapsedRealtime()
        if (autoDataSyncRunning || !hasActiveSession(appContext) ||
            now - lastAutoDataSyncStartedAt < AUTO_DATA_SYNC_MIN_RESTART_MS) return
        autoDataSyncRunning = true
        lastAutoDataSyncStartedAt = now
        autoDataSyncThread = Thread {
            val wakeLock = acquireTransientWakeLock(
                appContext,
                "post-login-data-sync",
                AUTO_DATA_SYNC_WAKE_LOCK_MS
            )
            try {
                for (attempt in 0 until AUTO_DATA_SYNC_ATTEMPTS) {
                    if (!isServing || !hasActiveSession(appContext)) break
                    val fingerprint = dataFingerprint()
                    discover(AUTO_SYNC_DISCOVERY_TIMEOUT_MS)
                        .asSequence()
                        .filterNot { isLocalAddress(it.address) }
                        .distinctBy { it.address }
                        .forEach { peer ->
                            if (autoSyncedDataFiles[peer.address] != fingerprint) {
                                val result = syncAutomaticDataWithHost(appContext, peer.address, peer.name)
                                if (result.ok) autoSyncedDataFiles[peer.address] = dataFingerprint()
                            }
                        }
                    if (attempt < AUTO_DATA_SYNC_ATTEMPTS - 1) {
                        try { Thread.sleep(AUTO_DATA_SYNC_RETRY_DELAY_MS) } catch (_: InterruptedException) { break }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "automatic data sync error", e)
                AppRepository.logSync(SyncEntry("مزامنة البيانات التلقائية", syncErrorMessage(e), false))
            } finally {
                releaseWakeLock(wakeLock)
                autoDataSyncRunning = false
                autoDataSyncThread = null
            }
        }.apply {
            name = "post-login-data-auto-sync"
            start()
        }
    }

    private fun isMustafaSession(context: Context): Boolean {
        val username = context.getSharedPreferences("session", Context.MODE_PRIVATE)
            .getString("username", null)
        return AppRepository.normalizeUsername(username.orEmpty()) == "mustafa"
    }

    private fun hasActiveSession(context: Context): Boolean = context
        .getSharedPreferences("session", Context.MODE_PRIVATE)
        .getString("username", null)
        ?.isNotBlank() == true

    private fun userFileFingerprint(): String = AppRepository.users()
        .sortedBy { AppRepository.normalizeUsername(it.username) }
        .joinToString("|") { user ->
            "${AppRepository.normalizeUsername(user.username)}:${user.passwordHash}:${user.role.name}:${user.enabled}"
        }

    /**
     * بصمة للبيانات التلقائية دون بناء payload أو تحويل صور المجموعات إلى Base64.
     * تقرأ ملفات JSON الخام فقط؛ وبذلك لا تتضاعف الذاكرة مع المرفقات أثناء كل اكتشاف.
     */
    private fun dataFingerprint(): String = MessageDigest.getInstance("SHA-256").let { digest ->
        AppRepository.users().sortedBy { AppRepository.normalizeUsername(it.username) }.forEach { user ->
            digest.update("user:${AppRepository.normalizeUsername(user.username)}:${user.passwordHash}:${user.role.name}:${user.enabled}:${user.createdAt}\n".toByteArray(Charsets.UTF_8))
        }
        AppRepository.groups().sortedBy { it.id }.forEach { group ->
            digest.update("group:${group.id}:${group.name}:${group.createdAt}\n".toByteArray(Charsets.UTF_8))
            val itemsFile = File(AppRepository.dataDir(), "invoices/${group.id}/items.json")
            if (!itemsFile.isFile) {
                digest.update("missing\n".toByteArray(Charsets.UTF_8))
            } else {
                FileInputStream(itemsFile).use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.update('\n'.code.toByte())
            }
        }
        listOf("trash.json", "direct_messages.json", "notifications.json", "presence.json").forEach { fileName ->
            val file = File(AppRepository.dataDir(), fileName)
            digest.update("file:$fileName:".toByteArray(Charsets.UTF_8))
            if (file.isFile) {
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            } else {
                digest.update("missing".toByteArray(Charsets.UTF_8))
            }
            digest.update('\n'.code.toByte())
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** يمنع انقطاع Wi‑Fi خلال نقل محدود، ويُحرر مباشرة عند انتهاء المهمة أو فشلها. */
    private fun acquireTransientWakeLock(context: Context, label: String, timeoutMs: Long): PowerManager.WakeLock? {
        return try {
            val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "masahhisabat:$label").apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "تعذر الاحتفاظ المؤقت بالمزامنة", e)
            null
        }
    }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (_: Throwable) {
        }
    }

    private fun isLocalAddress(address: String): Boolean = try {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.any { it.hostAddress == address } == true
    } catch (_: Throwable) {
        false
    }

    /** تمنع هواتف أندرويد من تصفية ردود UDP الخاصة باكتشاف الأجهزة على Wi‑Fi. */
    private fun acquireMulticastLock(context: Context) {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = multicastLock ?: wifi.createMulticastLock("masah-hisabat-sync").also {
                it.setReferenceCounted(false)
                multicastLock = it
            }
            if (!lock.isHeld) lock.acquire()
        } catch (e: Throwable) {
            Log.w(TAG, "تعذر حجز بث Wi‑Fi المحلي", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        } finally {
            multicastLock = null
        }
    }

    /** يحول أخطاء الشبكة المتوقعة إلى رسائل عربية مفهومة، بلا كشف تفاصيل داخلية للمستخدم. */
    private fun syncErrorMessage(error: Throwable): String = when (error) {
        is UnknownHostException -> "تعذر الوصول للجهاز الآخر. تحقق من اتصال الشبكة."
        is SocketTimeoutException -> "انتهت مهلة المزامنة. ربما انقطع الاتصال أو استغرق الجهاز الآخر وقتًا طويلًا."
        is ConnectException -> "تعذر الاتصال بالجهاز الآخر. تأكد أن التطبيق مفتوح وأنكما على الشبكة نفسها."
        is EOFException -> "انقطع الاتصال قبل اكتمال المزامنة."
        is SocketException -> "انقطع اتصال الشبكة أثناء المزامنة."
        is IOException -> "لم تكتمل المزامنة بسبب استجابة غير صالحة أو اتصال غير مستقر."
        else -> "حدث خطأ غير متوقع أثناء المزامنة. أعد المحاولة."
    }

    /** يحفظ ملخص آخر محاولة مع جهاز، ولا يسمح لفشل ملف السجل بتعطيل المزامنة نفسها. */
    private fun recordSyncDevice(address: String, name: String, success: Boolean, error: String? = null) {
        try {
            AppRepository.recordSyncDevice(address, name, success, error)
        } catch (storageError: Throwable) {
            Log.w(TAG, "تعذر حفظ حالة جهاز المزامنة", storageError)
        }
    }

    /** يحدث وقت آخر ظهور للجهاز دون تغيير نتيجة آخر محاولة مزامنة. */
    private fun recordDiscoveredDevice(address: String, name: String) {
        try {
            AppRepository.recordSyncDevice(address, name)
        } catch (storageError: Throwable) {
            Log.w(TAG, "تعذر حفظ ظهور جهاز مكتشف", storageError)
        }
    }

    /** يسجل التعارضات التي حُسمت تلقائياً لتكون قابلة للمراجعة من الإعدادات. */
    private fun recordSyncConflict(payload: SyncPayload, entityType: String, entityId: String, resolution: String) {
        try {
            val deviceName = payload.deviceName?.takeIf { it.isNotBlank() } ?: "جهاز غير مسمى"
            AppRepository.logSyncConflict(SyncConflict(
                entityType = entityType,
                entityId = entityId,
                deviceName = deviceName,
                resolution = resolution
            ))
        } catch (storageError: Throwable) {
            Log.w(TAG, "تعذر حفظ سجل تعارض المزامنة", storageError)
        }
    }

    /**
     * فحص شبكي ذاتي لا يرسل بيانات حقيقية ولا يحتاج جهازًا ثانيًا.
     * يستخدم حلقة الاتصال المحلية فقط لاختبار نجاح الاتصال، الرفض، المهلة،
     * انقطاع الرد، والاستجابة غير الصالحة؛ وتُسجل كل نتيجة في سجل المزامنة.
     */
    fun runNetworkSelfTest(
        onProgress: (percent: Int, status: String) -> Unit = { _, _ -> }
    ): NetworkSelfTestReport {
        val results = mutableListOf<NetworkTestCase>()

        fun record(label: String, success: Boolean, detail: String) {
            results += NetworkTestCase(label, success, detail)
            AppRepository.logSync(
                SyncEntry(
                    action = "اختبار شبكة — $label",
                    detail = detail,
                    success = success
                )
            )
        }

        onProgress(5, "جارٍ بدء الاختبار الذاتي الآمن...")

        onProgress(20, "اختبار الاتصال المحلي والاستجابة السليمة...")
        try {
            withLoopbackServer(
                handler = { peer ->
                    val request = peer.getInputStream().bufferedReader().readLine()
                    if (request != "PING") throw IOException("طلب اختبار غير متوقع")
                    peer.getOutputStream().bufferedWriter().use { writer ->
                        writer.write("OK\n")
                        writer.flush()
                    }
                },
                client = { port ->
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), SELF_TEST_TIMEOUT_MS)
                        socket.soTimeout = SELF_TEST_TIMEOUT_MS
                        val writer = socket.getOutputStream().bufferedWriter()
                        writer.write("PING\n")
                        writer.flush()
                        val response = socket.getInputStream().bufferedReader().readLine()
                        if (response != "OK") throw IOException("رد اختبار غير صالح")
                    }
                }
            )
            record("اتصال محلي", true, "نجح فتح اتصال محلي واستلام تأكيد صحيح.")
        } catch (e: Throwable) {
            record("اتصال محلي", false, syncErrorMessage(e))
        }

        onProgress(40, "اختبار رفض الاتصال...")
        try {
            val closedPort = ServerSocket(0).use { it.localPort }
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", closedPort), SELF_TEST_TIMEOUT_MS)
            }
            record("رفض الاتصال", false, "تم الاتصال بمنفذ يجب أن يكون مغلقًا.")
        } catch (_: ConnectException) {
            record("رفض الاتصال", true, "تم التقاط رفض الاتصال ومعالجته بأمان.")
        } catch (e: Throwable) {
            record("رفض الاتصال", false, "نوع خطأ غير متوقع: ${syncErrorMessage(e)}")
        }

        onProgress(60, "اختبار انتهاء مهلة الرد...")
        try {
            withLoopbackServer(
                handler = { Thread.sleep(SELF_TEST_TIMEOUT_MS.toLong() + 400) },
                client = { port ->
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), SELF_TEST_TIMEOUT_MS)
                        socket.soTimeout = SELF_TEST_TIMEOUT_MS
                        socket.getInputStream().read()
                        throw IOException("لم تنته مهلة الرد كما هو متوقع")
                    }
                }
            )
            record("مهلة الرد", false, "لم تنته المهلة في سيناريو عدم الاستجابة.")
        } catch (_: SocketTimeoutException) {
            record("مهلة الرد", true, "تم التقاط انتهاء مهلة الرد ومعالجته بأمان.")
        } catch (e: Throwable) {
            record("مهلة الرد", false, "نوع خطأ غير متوقع: ${syncErrorMessage(e)}")
        }

        onProgress(80, "اختبار انقطاع الرد والاستجابة غير الصالحة...")
        try {
            withLoopbackServer(
                handler = { /* يقبل الاتصال ثم يغلقه دون رد. */ },
                client = { port ->
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), SELF_TEST_TIMEOUT_MS)
                        socket.soTimeout = SELF_TEST_TIMEOUT_MS
                        if (socket.getInputStream().bufferedReader().readLine() != null) {
                            throw IOException("وصل رد بينما يجب أن ينقطع الاتصال")
                        }
                        throw EOFException("انقطع الرد كما هو متوقع")
                    }
                }
            )
            record("انقطاع الرد", false, "لم يُلتقط انقطاع الرد كما هو متوقع.")
        } catch (_: EOFException) {
            record("انقطاع الرد", true, "تم التقاط انقطاع الرد ومعالجته بأمان.")
        } catch (e: Throwable) {
            record("انقطاع الرد", false, "نوع خطأ غير متوقع: ${syncErrorMessage(e)}")
        }

        try {
            withLoopbackServer(
                handler = { peer ->
                    peer.getOutputStream().bufferedWriter().use { writer ->
                        writer.write("REPLY_INCOMPLETE\n")
                        writer.flush()
                    }
                },
                client = { port ->
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), SELF_TEST_TIMEOUT_MS)
                        socket.soTimeout = SELF_TEST_TIMEOUT_MS
                        val reply = socket.getInputStream().bufferedReader().readLine()
                        if (reply?.startsWith("OK") == true) throw IOException("قُبل رد غير صالح")
                    }
                }
            )
            record("استجابة غير صالحة", true, "تم رفض استجابة ناقصة دون تعطل التطبيق.")
        } catch (e: Throwable) {
            record("استجابة غير صالحة", false, syncErrorMessage(e))
        }

        val passed = results.count { it.success }
        onProgress(100, "اكتمل الاختبار: $passed من ${results.size} سيناريوهات نجحت")
        return NetworkSelfTestReport(results)
    }

    /** خادم محلي مؤقت للاختبارات فقط؛ يُغلق دائمًا بعد كل سيناريو. */
    private fun withLoopbackServer(handler: (Socket) -> Unit, client: (Int) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { testServer ->
            val worker = Thread {
                try {
                    testServer.accept().use { handler(it) }
                } catch (_: Throwable) {
                    // السيناريوهات المقصودة قد تغلق الاتصال قبل اكتمال رد الخادم.
                }
            }.apply { name = "sync-self-test-server" }
            worker.start()
            try {
                client(testServer.localPort)
            } finally {
                try { testServer.close() } catch (_: Throwable) {}
                try { worker.join(SELF_TEST_TIMEOUT_MS.toLong() + 1_200) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun buildPayload(context: Context): SyncPayload {
        return SyncPayload(
            deviceName = AppRepository.currentUserDeviceName(),
            users = AppRepository.users().map { UserPayload(it.username, it.passwordHash, it.role.name, it.enabled) },
            groups = buildGroupsPayload(),
            items = buildDataItems(),
            trashRecords = AppRepository.trashRecords(),
            directMessages = buildDirectMessagePayloads(),
            notifications = AppRepository.notifications().take(100),
            presence = AppRepository.presence()
        )
    }

    private fun buildAutomaticDataPayload(): SyncPayload = SyncPayload(
        deviceName = AppRepository.currentUserDeviceName(),
        users = AppRepository.users().map { UserPayload(it.username, it.passwordHash, it.role.name, it.enabled) },
        groups = buildGroupsPayload(),
        items = buildDataItems(),
        trashRecords = AppRepository.trashRecords(),
        directMessages = buildDirectMessagePayloads(),
        notifications = AppRepository.notifications().take(100),
        presence = AppRepository.presence(),
        mode = AUTO_DATA_SYNC_MODE
    )

    private fun buildGroupsPayload(): List<SyncGroupPayload> =
        AppRepository.groups().map { SyncGroupPayload(it.id, it.name) }

    private fun buildDirectMessagePayloads(): List<SyncDirectMessagePayload> =
        AppRepository.directMessages().takeLast(2_000).map { message ->
            SyncDirectMessagePayload(message, encodeImageForSync(message.imagePath))
        }
    private fun buildDataItems(): List<SyncItemPayload> = buildList {
        for (group in AppRepository.groups()) {
            for (item in AppRepository.items(group.id)) {
                add(
                    SyncItemPayload(
                        groupId = group.id,
                        groupName = group.name,
                        item = item,
                        image = encodeImageForSync(item.imagePath),
                        processedImage = encodeImageForSync(item.processedPath)
                    )
                )
            }
        }
    }

    /** يحول المرفق المحلي إلى بيانات قابلة للنقل، مع حد يمنع استهلاك الذاكرة على ملفات غير منطقية. */
    private fun encodeImageForSync(path: String?): SyncImagePayload? {
        return try {
            val file = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
            if (!file.isFile || file.length() !in 1..MAX_SYNC_IMAGE_BYTES) return null
            val encodedCapacity = (((file.length() + 2L) / 3L) * 4L).toInt()
            val encoded = ByteArrayOutputStream(encodedCapacity)
            Base64OutputStream(encoded, Base64.NO_WRAP).use { output ->
                FileInputStream(file).use { input -> input.copyTo(output, SYNC_IO_BUFFER_BYTES) }
            }
            SyncImagePayload(file.name, encoded.toString(Charsets.US_ASCII.name()))
        } catch (_: Throwable) {
            null
        }
    }

    /** يعيد حفظ المرفق ضمن مجلد بيانات التطبيق الدائم بدل الاحتفاظ بمسار الجهاز المصدر. */
    private fun restoreImageFromSync(itemId: String, label: String, image: SyncImagePayload?): String? {
        return try {
            image ?: return null
            if (image.data.isBlank()) return null
            val extension = image.fileName.substringAfterLast('.', "jpg")
                .replace(Regex("[^A-Za-z0-9]"), "")
                .take(8)
                .ifBlank { "jpg" }
            val imageDir = File(AppRepository.dataDir(), "images").also { it.mkdirs() }
            val target = File(imageDir, "sync_${itemId}_${label}.${extension}")
            val partial = File(imageDir, "${target.name}.part")
            partial.delete()
            Base64InputStream(
                ByteArrayInputStream(image.data.toByteArray(Charsets.US_ASCII)),
                Base64.NO_WRAP
            ).use { input ->
                partial.outputStream().buffered(64 * 1024).use { output ->
                    input.copyTo(output, SYNC_IO_BUFFER_BYTES)
                    output.flush()
                }
            }
            if (!partial.isFile || partial.length() !in 1..MAX_SYNC_IMAGE_BYTES) {
                partial.delete()
                return null
            }
            if (target.exists() && !target.delete()) {
                partial.delete()
                return null
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                return null
            }
            target.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildMustafaUsersPayload(): SyncPayload = SyncPayload(
        deviceName = AppRepository.currentUserDeviceName(),
        users = AppRepository.users().map { UserPayload(it.username, it.passwordHash, it.role.name, it.enabled) },
        groups = emptyList(),
        items = emptyList(),
        mode = AUTO_USER_SYNC_MODE,
        sourceUsername = "mustafa"
    )

    /** معاينة غير مدمرة، تستخدم في السجل وقبل حفظ أي بيانات واردة. */
    private fun previewPayload(payload: SyncPayload): SyncPreview {
        val localUsers = AppRepository.users()
            .map { AppRepository.normalizeUsername(it.username) }
            .toSet()
        val localGroups = AppRepository.groups().associateBy { it.id }
        var newItems = 0
        var newGroups = payload.groups.count { it.id !in localGroups }
        payload.items.groupBy { it.groupId }.forEach { (groupId, items) ->
            if (groupId !in localGroups && payload.groups.none { it.id == groupId }) newGroups++
            val localIds = AppRepository.items(groupId).map { it.id }.toSet()
            newItems += items.count { it.item.id !in localIds }
        }
        val localTrash = AppRepository.trashRecords().associateBy { it.id }
        val changedTrashRecords = payload.trashRecords.orEmpty().count { incoming ->
            val localRecord = localTrash[incoming.id]
            localRecord == null || incoming.stateChangedAt > localRecord.stateChangedAt
        }
        return SyncPreview(
            newUsers = payload.users.count { AppRepository.normalizeUsername(it.username) !in localUsers },
            existingUsers = payload.users.count { AppRepository.normalizeUsername(it.username) in localUsers },
            newGroups = newGroups,
            newItems = newItems,
            changedTrashRecords = changedTrashRecords
        )
    }

    /** تطبيق بيانات مستقبلة ومزامنتها مع المحلي: المستخدمون يُدمجون (إضافة الجدد، تحديث كلمات المرور)، والبيانات تُضاف. */
    private fun applyPayload(context: Context, payload: SyncPayload): ApplyResult {
        // تطبق حالات السلة أولًا حتى لا تعود البيانات المحذوفة من جهاز آخر.
        val localTrash = AppRepository.trashRecords().associateBy { it.id }
        payload.trashRecords.orEmpty().forEach { incoming ->
            val existing = localTrash[incoming.id] ?: return@forEach
            if (existing.state != incoming.state && existing.stateChangedAt != incoming.stateChangedAt) {
                val resolution = if (incoming.stateChangedAt > existing.stateChangedAt) {
                    "اعتمدت حالة السلة الأحدث الواردة من الجهاز الآخر."
                } else {
                    "احتُفظ بحالة السلة المحلية لأنها الأحدث."
                }
                recordSyncConflict(payload, "trash", incoming.id, resolution)
            }
        }
        AppRepository.mergeTrashRecords(payload.trashRecords.orEmpty())
        var addedUsers = 0
        // 1) مزامنة المستخدمين: دمج حسب اسم المستخدم
        val local = AppRepository.users().toMutableList()
        val localNames = local.map { AppRepository.normalizeUsername(it.username) }.toMutableSet()
        for (u in payload.users) {
            val normalizedUsername = AppRepository.normalizeUsername(u.username)
            if (normalizedUsername.isBlank()) continue
            val role = try { Role.valueOf(u.roleName) } catch (_: Exception) { Role.VIEWER }
            if (normalizedUsername !in localNames) {
                // مستخدم جديد على هذا الجهاز — أضيفه
                local.add(User(normalizedUsername, u.passwordHash, role, enabled = u.enabled))
                localNames.add(normalizedUsername)
                addedUsers++
            } else {
                // موجود محليًا: إذا كان محليًا غير مفعّل أو بدون كلمة مرور صحيحة، حدثّ كلمة المرور (لأول مرة فقط أو عند مزامنة)
                val existing = local.find { AppRepository.normalizeUsername(it.username) == normalizedUsername }
                if (existing != null && existing.passwordHash.isBlank()) {
                    local.replaceAll {
                        if (AppRepository.normalizeUsername(it.username) == normalizedUsername) {
                            it.copy(passwordHash = u.passwordHash, role = role, enabled = u.enabled)
                        } else it
                    }
                } else if (
                    existing != null &&
                    (existing.passwordHash != u.passwordHash || existing.role != role || existing.enabled != u.enabled)
                ) {
                    recordSyncConflict(
                        payload,
                        "user",
                        normalizedUsername,
                        "احتُفظ بإعدادات المستخدم المحلية لأن المزامنة غير السلطوية لا تستبدل حساباً قائماً."
                    )
                }
            }
        }
        // المالك المحلي (mustafa الافتراضي) لا يُمس إن لم يُرسل من الطرف الآخر
        AppRepository.saveList("users.json", local)

        // 2) مزامنة المجموعات والعناصر، بما فيها المجموعات الخالية من الرسائل
        for (incomingGroup in payload.groups) {
            if (incomingGroup.id.isBlank() || AppRepository.isGroupTrashed(incomingGroup.id)) continue
            val localGroup = AppRepository.groups().find { it.id == incomingGroup.id }
            if (localGroup == null) AppRepository.addGroup(Group(incomingGroup.id, incomingGroup.name))
            else if (localGroup.name != incomingGroup.name) {
                recordSyncConflict(payload, "group", incomingGroup.id, "احتُفظ باسم المجموعة المحلي عند اختلاف الاسم بين الجهازين.")
            }
        }
        var addedItems = 0
        for (p in payload.items) {
            if (AppRepository.isGroupTrashed(p.groupId) || AppRepository.isItemTrashed(p.groupId, p.item.id)) continue
            var group = AppRepository.groups().find { it.id == p.groupId }
            if (group == null) {
                group = Group(p.groupId, p.groupName)
                AppRepository.addGroup(group)
            } else if (group.name != p.groupName) {
                recordSyncConflict(
                    payload,
                    "group",
                    p.groupId,
                    "احتُفظ باسم المجموعة المحلي عند اختلاف الاسم بين الجهازين."
                )
            }
            val existingItem = AppRepository.items(p.groupId).find { it.id == p.item.id }
            if (existingItem == null) {
                val syncedItem = p.item.copy(
                    imagePath = restoreImageFromSync(p.item.id, "original", p.image),
                    processedPath = restoreImageFromSync(p.item.id, "processed", p.processedImage)
                )
                AppRepository.addItem(p.groupId, syncedItem)
                addedItems++
            } else if (existingItem != p.item) {
                recordSyncConflict(
                    payload,
                    "item",
                    p.item.id,
                    "احتُفظ بالعنصر المحلي الموجود لتجنب استبدال محتوى تم تعديله على هذا الجهاز."
                )
            }
        }
        for (incoming in payload.directMessages.orEmpty()) {
            if (AppRepository.directMessages().any { it.id == incoming.message.id }) continue
            val imagePath = restoreImageFromSync(incoming.message.id, "direct", incoming.image)
            AppRepository.addDirectMessage(incoming.message.copy(imagePath = imagePath))
        }
        for (incoming in payload.notifications.orEmpty()) {
            if (AppRepository.notifications().none { it.id == incoming.id }) AppRepository.addNotification(incoming)
        }
        val incomingPresence = payload.presence.orEmpty()
        if (incomingPresence.isNotEmpty()) {
            val mergedPresence = (AppRepository.presence() + incomingPresence)
                .distinctBy { it.username }
                .sortedByDescending { it.lastSeenAt }
                .take(200)
            AppRepository.saveList("presence.json", mergedPresence)
        }
        return ApplyResult(addedItems, addedUsers)
    }

    /** يطابق ملف المستخدمين القادم من جهاز mustafa: يضيف الحسابات ويحدّث كلمة المرور والصلاحية للحسابات الموجودة. */
    private fun applyAuthoritativeUsers(payload: SyncPayload): ApplyResult {
        val local = AppRepository.users().toMutableList()
        var changedUsers = 0
        for (incoming in payload.users) {
            val username = AppRepository.normalizeUsername(incoming.username)
            if (username.isBlank()) continue
            val role = try { Role.valueOf(incoming.roleName) } catch (_: Exception) { Role.VIEWER }
            val index = local.indexOfFirst { AppRepository.normalizeUsername(it.username) == username }
            val existing = local.getOrNull(index)
            val replacement = User(
                username = username,
                passwordHash = incoming.passwordHash,
                role = role,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                enabled = incoming.enabled
            )
            if (index < 0) {
                local.add(replacement)
                changedUsers++
            } else if (existing != replacement) {
                recordSyncConflict(
                    payload,
                    "user",
                    username,
                    "اعتمدت نسخة حساب المستخدم القادمة من جهاز mustafa بوصفها المصدر السلطوي."
                )
                local[index] = replacement
                changedUsers++
            }
        }
        AppRepository.saveList("users.json", local)
        return ApplyResult(0, changedUsers)
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
                        val localAddress = try {
                            java.net.NetworkInterface.getNetworkInterfaces()
                                ?.toList()
                                ?.flatMap { it.inetAddresses.toList() }
                                ?.firstOrNull { address ->
                                    !address.isLoopbackAddress && address.hostAddress?.contains(".") == true
                                }
                                ?.hostAddress
                                ?: "unknown"
                        } catch (_: Throwable) {
                            "unknown"
                        }
                        val reply = "HERE $localAddress ${BuildConfig.VERSION_CODE} $me"
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
                discoveryBroadcastAddresses().forEach { address ->
                    val packet = DatagramPacket(msg.toByteArray(), msg.length, address, UDP_PORT)
                    try { socket.send(packet) } catch (_: Throwable) {}
                }
                val buf = ByteArray(512)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < timeoutMs) {
                    try {
                        val resp = DatagramPacket(buf, buf.size)
                        socket.receive(resp)
                        val text = String(resp.data, 0, resp.length).trim()
                        if (text.startsWith("HERE ")) {
                            val parts = text.removePrefix("HERE ").split(" ", limit = 3)
                            val advertisedVersion = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                            val peerName = if (advertisedVersion > 0L) {
                                parts.getOrNull(2) ?: parts[0]
                            } else {
                                text.removePrefix("HERE ").substringAfter(" ", parts[0])
                            }
                            val peer = DiscoveredPeer(parts[0], peerName, advertisedVersion)
                            found.addIfAbsent(peer)
                            recordDiscoveredDevice(peer.address, peer.name)
                        }
                    } catch (_: Exception) { break }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "discover error", e)
        }
        return found.toList()
    }

    /** يجمع البث العام وبث الشبكات الفرعية، لأن بعض موجّهات Wi‑Fi لا تمرّر 255.255.255.255. */
    private fun discoveryBroadcastAddresses(): List<InetAddress> {
        val addresses = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.interfaceAddresses }
                ?.mapNotNull { it.broadcast }
                ?.filterIsInstance<Inet4Address>()
                ?.forEach { addresses.add(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "تعذر تحديد بث الشبكة الفرعية", e)
        }
        return addresses.toList()
    }

    fun ensureServer(context: Context) {
        if (!isServing) startServer(context)
    }

    data class DiscoveredPeer(val address: String, val name: String, val versionCode: Long = 0L)
    data class UpdateHeader(
        val ok: Boolean,
        val versionCode: Long,
        val versionName: String,
        val size: Long,
        val sha256: String? = null,
        val message: String? = null
    )
    data class SyncResult(
        val ok: Boolean,
        val itemsReceived: Int,
        val usersReceived: Int,
        val errorMessage: String? = null
    )
    data class SyncPreview(
        val newUsers: Int,
        val existingUsers: Int,
        val newGroups: Int,
        val newItems: Int,
        val changedTrashRecords: Int = 0
    ) { val hasChanges: Boolean get() = newUsers > 0 || newGroups > 0 || newItems > 0 || changedTrashRecords > 0 }
    data class NetworkTestCase(val label: String, val success: Boolean, val detail: String)
    data class NetworkSelfTestReport(val results: List<NetworkTestCase>) {
        val passedCount: Int get() = results.count { it.success }
        val isSuccessful: Boolean get() = results.isNotEmpty() && passedCount == results.size
    }

    // ---------- نماذج المزامنة ----------
    data class UserPayload(val username: String, val passwordHash: String, val roleName: String, val enabled: Boolean)
    data class SyncImagePayload(val fileName: String, val data: String)
    data class SyncGroupPayload(val id: String, val name: String)
    data class SyncItemPayload(
        val groupId: String,
        val groupName: String,
        val item: InvoiceItem,
        val image: SyncImagePayload? = null,
        val processedImage: SyncImagePayload? = null
    )
    data class SyncDirectMessagePayload(
        val message: DirectMessage,
        val image: SyncImagePayload? = null
    )
    data class SyncPayload(
        val deviceName: String? = null,
        val users: List<UserPayload>,
        val groups: List<SyncGroupPayload> = emptyList(),
        val items: List<SyncItemPayload>,
        /** nullable للحفاظ على توافق حمولات الإصدارات السابقة. */
        val trashRecords: List<TrashEntry>? = null,
        val directMessages: List<SyncDirectMessagePayload>? = null,
        val notifications: List<NotificationEvent>? = null,
        val presence: List<UserPresence>? = null,
        val mode: String? = null,
        val sourceUsername: String? = null
    ) {
        val totalItems get() = items.size
    }
    data class ApplyResult(val items: Int, val users: Int)
}
