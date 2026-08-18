package com.masahhisabat.app.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.google.gson.Gson
import java.io.EOFException
import java.io.File
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
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val SELF_TEST_TIMEOUT_MS = 600
    private const val AUTO_SYNC_DISCOVERY_TIMEOUT_MS = 1_500L
    private const val AUTO_SYNC_INTERVAL_MS = 8_000L
    private const val AUTO_USER_SYNC_MODE = "mustafa_users_only"
    private val gson = Gson()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var isServing = false
    @Volatile private var autoSyncRunning = false
    @Volatile private var autoSyncThread: Thread? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    val peers = CopyOnWriteArrayList<String>()
    private val autoSyncedUserFiles = ConcurrentHashMap<String, String>()

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
    }

    fun stopServer() {
        isServing = false
        autoSyncRunning = false
        autoSyncThread?.interrupt()
        autoSyncThread = null
        autoSyncedUserFiles.clear()
        try { server?.close() } catch (_: Throwable) {}
        server = null
        releaseMulticastLock()
    }

    private fun handleClient(socket: Socket, context: Context) {
        Thread {
            socket.use { client ->
                try {
                    client.soTimeout = READ_TIMEOUT_MS
                    val reader = client.getInputStream().bufferedReader()
                    val payloadJson = reader.readLine()
                        ?: throw EOFException("انقطع الاتصال قبل اكتمال بيانات المزامنة")
                    val payload = gson.fromJson(payloadJson, SyncPayload::class.java)
                        ?: throw IOException("بيانات المزامنة غير صالحة")
                    val preview = previewPayload(payload)
                    AppRepository.logSync(SyncEntry(
                        "معاينة استقبال",
                        "من ${client.inetAddress.hostAddress}: ستُضاف ${preview.newItems} عناصر و${preview.newUsers} مستخدمين و${preview.newGroups} مجموعات.",
                        true
                    ))
                    val isMustafaUserFile = payload.mode == AUTO_USER_SYNC_MODE
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
                    client.getOutputStream().flush()
                    val sender = payload.deviceName?.takeIf { it.isNotBlank() } ?: client.inetAddress.hostAddress
                    val action = if (isMustafaUserFile) "استقبال تلقائي للمستخدمين" else "استقبال"
                    val detail = if (isMustafaUserFile) {
                        "تم تحديث ${result.users} حسابًا من جهاز mustafa: $sender"
                    } else {
                        "استُقبلت ${result.items} عناصر و${result.users} مستخدمين من $sender"
                    }
                    AppRepository.logSync(SyncEntry(action, detail, true))
                } catch (e: Throwable) {
                    Log.e(TAG, "client error", e)
                    AppRepository.logSync(SyncEntry("استقبال", "فشلت المزامنة: ${syncErrorMessage(e)}", false))
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
                socket.soTimeout = READ_TIMEOUT_MS
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
                onProgress(100, "اكتملت المزامنة بنجاح")
                return SyncResult(true, gotItems, gotUsers)
            }
        } catch (e: Throwable) {
            val errorMessage = syncErrorMessage(e)
            Log.e(TAG, "sync error", e)
            val sentSummary = payload?.let { " بعد تجهيز ${it.totalItems} عناصر و${it.users.size} مستخدمين" }.orEmpty()
            AppRepository.logSync(SyncEntry("إرسال", "فشلت المزامنة مع $peerName$sentSummary: $errorMessage", false))
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
                socket.soTimeout = READ_TIMEOUT_MS
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
                return SyncResult(true, 0, changedUsers)
            }
        } catch (e: Throwable) {
            val errorMessage = syncErrorMessage(e)
            AppRepository.logSync(SyncEntry(
                "إرسال تلقائي للمستخدمين",
                "تعذر إرسال ملف المستخدمين إلى $peerName: $errorMessage",
                false
            ))
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
                                val result = syncMustafaUsersWithHost(peer.address, peer.name)
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

    private fun isMustafaSession(context: Context): Boolean {
        val username = context.getSharedPreferences("session", Context.MODE_PRIVATE)
            .getString("username", null)
        return AppRepository.normalizeUsername(username.orEmpty()) == "mustafa"
    }

    private fun userFileFingerprint(): String = AppRepository.users()
        .sortedBy { AppRepository.normalizeUsername(it.username) }
        .joinToString("|") { user ->
            "${AppRepository.normalizeUsername(user.username)}:${user.passwordHash}:${user.role.name}:${user.enabled}"
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
            items = buildList {
                for (g in AppRepository.groups()) {
                    for (item in AppRepository.items(g.id)) {
                        add(SyncItemPayload(g.id, g.name, item))
                    }
                }
            }
        )
    }

    private fun buildMustafaUsersPayload(): SyncPayload = SyncPayload(
        deviceName = AppRepository.currentUserDeviceName(),
        users = AppRepository.users().map { UserPayload(it.username, it.passwordHash, it.role.name, it.enabled) },
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
        var newGroups = 0
        payload.items.groupBy { it.groupId }.forEach { (groupId, items) ->
            if (groupId !in localGroups) newGroups++
            val localIds = AppRepository.items(groupId).map { it.id }.toSet()
            newItems += items.count { it.item.id !in localIds }
        }
        return SyncPreview(
            newUsers = payload.users.count { AppRepository.normalizeUsername(it.username) !in localUsers },
            existingUsers = payload.users.count { AppRepository.normalizeUsername(it.username) in localUsers },
            newGroups = newGroups,
            newItems = newItems
        )
    }

    /** تطبيق بيانات مستقبلة ومزامنتها مع المحلي: المستخدمون يُدمجون (إضافة الجدد، تحديث كلمات المرور)، والبيانات تُضاف. */
    private fun applyPayload(context: Context, payload: SyncPayload): ApplyResult {
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
                        val reply = "HERE $localAddress $me"
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

    data class DiscoveredPeer(val address: String, val name: String)
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
        val newItems: Int
    ) { val hasChanges: Boolean get() = newUsers > 0 || newGroups > 0 || newItems > 0 }
    data class NetworkTestCase(val label: String, val success: Boolean, val detail: String)
    data class NetworkSelfTestReport(val results: List<NetworkTestCase>) {
        val passedCount: Int get() = results.count { it.success }
        val isSuccessful: Boolean get() = results.isNotEmpty() && passedCount == results.size
    }

    // ---------- نماذج المزامنة ----------
    data class UserPayload(val username: String, val passwordHash: String, val roleName: String, val enabled: Boolean)
    data class SyncItemPayload(val groupId: String, val groupName: String, val item: InvoiceItem)
    data class SyncPayload(
        val deviceName: String? = null,
        val users: List<UserPayload>,
        val items: List<SyncItemPayload>,
        val mode: String? = null,
        val sourceUsername: String? = null
    ) {
        val totalItems get() = items.size
    }
    data class ApplyResult(val items: Int, val users: Int)
}
