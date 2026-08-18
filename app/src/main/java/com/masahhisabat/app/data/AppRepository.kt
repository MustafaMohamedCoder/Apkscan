package com.masahhisabat.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * مستودع محلي شامل: مستخدمون، مجموعات، فواتير، سجل نشاط، سجل مزامنة، تفضيلات.
 * كل شيء يُحفظ في مجلد التطبيق الداخلي (filesDir) بصيغة JSON + صور.
 */
object AppRepository {

    private var appContext: Context? = null
    private val prefs: SharedPreferences by lazy {
        val ctx = appContext
            ?: throw IllegalStateException("AppRepository must be initialized with appContext first")
        ctx.getSharedPreferences("masah_prefs", Context.MODE_PRIVATE).also {
            // إنشاء المستخدم الافتراضي عند أول وصول بعد تهيئة السياق
            if (usersInternal().isEmpty()) {
                addUserInternal(User("mustafa", HashUtil.hash("0"), Role.ADMIN))
            }
        }
    }
    internal val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapterFactory(DataClassAdapterFactory())
        .create()

    fun init(context: Context) {
        appContext = context.applicationContext
        ensureDefaults()
    }

    fun initAppContext(context: Context) {
        appContext = context.applicationContext
        // إصلاح جوهري: إصلاح كلمات المرور وتهيئة الضمانات عند كل تشغيل،
        // وليس فقط عند استدعاء init() النادر (كان يحدث فقط في onActivityResult)
        try { ensureDefaults() } catch (_: Exception) { }
    }

    private var cachedFilesDir: File? = null
    fun setFilesDir(context: Context, dir: File) {
        cachedFilesDir = dir
        prefs.edit().putString("app_dir", dir.absolutePath).apply()
    }

    fun dataDirFromPrefs(): File {
        val saved = prefs.getString("app_dir", null)
        if (!saved.isNullOrBlank()) return File(saved)
        val dir = externalDataDir()
        prefs.edit().putString("app_dir", dir.absolutePath).apply()
        return dir
    }

    /**
     * مجلد البيانات الخارجي المستقل: Documents/MasahHisabat
     * لا يُحذف عند إزالة التطبيق، ويُكتشف تلقائيًا عند إعادة التثبيت.
     */
    fun externalDataDir(): File {
        val c = appContext ?: throw IllegalStateException("init() must be called first")
        // مجلد عام في الذاكرة الخارجية (Documents) يبقى بعد حذف التطبيق
        val external = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "MasahHisabat")
        val canUseExternal = try {
            val legacyOk = android.os.Build.VERSION.SDK_INT < 30 && external.canWrite()
            val modernOk = android.os.Build.VERSION.SDK_INT >= 30 &&
                android.os.Environment.isExternalStorageManager()
            legacyOk || modernOk
        } catch (_: Exception) {
            false
        }
        val dir = if (canUseExternal) {
            external
        } else {
            // fallback داخلي إذا لم تتوفر كتابة خارجية (مثل رفض الإذن)
            File(c.filesDir, "masah_data")
        }
        dir.mkdirs()
        return dir
    }
    /** هل يستخدم التطبيق حاليًا مجلد البيانات الخارجي المستقل؟ */
    fun isUsingExternalStorage(): Boolean {
        return try {
            externalDataDir() == File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "MasahHisabat")
        } catch (_: Exception) {
            false
        }
    }

    fun dataDir(context: Context? = null): File {
        val c = context ?: appContext ?: throw IllegalStateException()
        val dir = externalDataDir()
        dir.mkdirs()
        return dir
    }

    private fun ensureDefaults() {
        // ترقية تلقائية: إذا كان المجلد الخارجي الجديد فارغًا والمجلد الداخلي القديم يحوي بيانات
        // (من تثبيت سابق)، نرحّلها تلقائيًا حتى لا يفقد المستخدم بياناته
        migrateOldInternalDataIfNeeded()
        // ترقية كلمة المرور: أي مستخدم بكلمة مرور SHA-256 القديمة تُعاد ترميزها بالنمط القابل للفك (v2)
        // حتى يمكن عرض كلمات المرور كما هي وكتابة تشفير جديد قابل للفك
        // لا نرقّي كلمات المرور القديمة هنا: الهاش القديم SHA-256 ليس كلمة المرور نفسها،
        // والترقية هنا ستجعل الدخول بـ "0" مستحيلًا. الترقية تتم عند أول تسجيل دخول ناجح
        // في authenticate() فقط.
        // إصلاح إضافي: في النسخ السابقة كانت الترقية الخاطئة تخزّن encodePlain(الهاش القديم)
        // فتصبح كلمة المرور فكها = سلاسل هكس بدلاً من الكلمة الفعلية، فيتعذّر الدخول نهائيًا.
        // أي مستخدم بصيغة v2 لكن فكّها لا يتطابق مع أي مقارنة قياسية (أي غير صالحة) تُحذف كلمة مروره
        // وتُعاد إليه القدرة على الدخول بكلمة المرور الافتراضية «0».
        try {
            // إصلاح كلمات المرور «الخاطئة»: في النسخ السابقة كانت الترقية التلقائية تحفظ
            // encodePlain(الهاش القديم SHA-256) بدل كلمة المرور الفعلية، فيصبح فكّ كلمة
            // المرور = سلسلة هكس طويلة وليس كلمة فعلية. نتعرف على ذلك بأن فكّها يساوي
            // الهاش القديم SHA-256 (64 حرفًا هكسيًا) أو أن فكّها لا يصمد ككلمة مرور طبيعية.
            val fixed = usersInternal().map { u ->
                val decoded = HashUtil.decodePlain(u.passwordHash)
                val isBadV2 = decoded != null && decoded.isNotBlank() &&
                    (decoded.length == 64 && decoded.all { it in "0123456789abcdef" }) &&
                    decoded == HashUtil.hash(decoded.removeSuffix(""))  // ببساطة: فكّها هاش هكسي كامل
                if (isBadV2) {
                    if (u.role == Role.ADMIN) u.copy(passwordHash = HashUtil.encodePlain("0"))
                    else u.copy(passwordHash = HashUtil.encodePlain(u.username))
                } else u
            }
            if (fixed.zip(usersInternal()).any { (a, b) -> a.passwordHash != b.passwordHash }) {
                saveList("users.json", fixed)
            }
        } catch (_: Exception) { }
        // ضمان وجود حساب مدير صالح يمكن الدخول إليه: إذا كان هناك مستخدمون لكن لا أحد منهم
        // يمكن الدخول إليه، ننشئ mustafa بكلمة «0» إذا لم يوجد (كلمته v2 قابلة للعرض).
        if (usersInternal().isEmpty()) {
            addUserInternal(User("mustafa", HashUtil.encodePlain("0"), Role.ADMIN))
        } else if (usersInternal().none { it.username == "mustafa" } &&
            usersInternal().none { it.role == Role.ADMIN }) {
            addUserInternal(User("mustafa", HashUtil.encodePlain("0"), Role.ADMIN))
        }
    }

    private fun migrateOldInternalDataIfNeeded() {
        try {
            val c = appContext ?: return
            val oldDir = File(c.filesDir, "masah_data")
            val newDir = externalDataDir()
            if (oldDir.exists() && oldDir.listFiles()?.isNotEmpty() == true) {
                val newHasData = newDir.listFiles()?.any { it.name.endsWith(".json") } == true
                if (!newHasData) {
                    // نقل كل البيانات القديمة إلى المجلد الخارجي
                    oldDir.listFiles()?.forEach { file ->
                        file.copyRecursively(File(newDir, file.name), overwrite = true)
                    }
                }
                // بعد الهجرة الناجحة نحذف القديم لتجنب التباس القراءة
                oldDir.deleteRecursively()
            }
        } catch (_: Exception) { /* لا نعيق تشغيل التطبيق إذا فشلت الهجرة */ }
    }

    // ---------- المستخدمون ----------
    private fun usersInternal(): List<User> = loadList("users.json", User::class.java)
    private fun addUserInternal(u: User) = saveList("users.json", usersInternal().toMutableList().also { it.add(u) })
    fun addUser(u: User) = saveList("users.json", users().toMutableList().also { it.add(u) })
    fun removeUser(username: String) = saveList("users.json", users().filter { it.username != username })
    fun changePassword(username: String, newHash: String) {
        saveList("users.json", users().map { if (it.username == username) it.copy(passwordHash = newHash) else it })
    }

    fun authenticate(username: String, password: String): User? {
        val user = users().find { it.username == username && it.enabled } ?: return null
        // دعم النوعين: SHA-256 القديم، وv2 القابل للفك (كلمات مرور المستخدمين الجدد)
        if (user.passwordHash == HashUtil.hash(password)) {
            // الدخول بكلمة مرور قديمة ناجح — نرقّيها تلقائيًا إلى النمط القابل للفك (v2)
            // حتى تُعرض لاحقًا كما هي في إدارة الفريق
            changePassword(username, HashUtil.encodePlain(password))
            return user
        }
        if (HashUtil.isDecodable(user.passwordHash) && HashUtil.decodePlain(user.passwordHash) == password) {
            return user
        }
        // fallback مضمون: كلمات المرور v2 «الخاطئة» من النسخ السابقة (فكّها سلسلة هكس وليس كلمة فعلية)
        // إذا فشلت المقارنة وكان فكّ كلمة المستخدم سلسلة هكس من 64 حرفًا، نعيد ضبطها تلقائيًا:
        // ADMIN ← كلمة «0»، والبقية ← اسم المستخدم، ثم نعيد المحاولة.
        val decoded = HashUtil.decodePlain(user.passwordHash)
        val isBadV2 = decoded != null && decoded.length == 64 && decoded.all { it in "0123456789abcdef" }
        if (isBadV2) {
            val fixed = HashUtil.encodePlain(if (user.role == Role.ADMIN) "0" else user.username)
            changePassword(user.username, fixed)
            return users().find { it.username == username && it.enabled }?.takeIf {
                HashUtil.decodePlain(it.passwordHash) == (if (it.role == Role.ADMIN) "0" else it.username)
            }
        }
        return null
    }

    fun canManageUsers(role: Role): Boolean = role == Role.ADMIN
    fun canAdmin(role: Role): Boolean = role == Role.ADMIN || role == Role.SUPERVISOR
    fun canEdit(role: Role): Boolean = role in setOf(Role.ADMIN, Role.SUPERVISOR, Role.EDITOR)
    fun canSync(role: Role): Boolean = role == Role.ADMIN || role == Role.SUPERVISOR

    // ---------- المجموعات والفواتير ----------
    fun addGroup(g: Group) = saveList("groups.json", groups().toMutableList().also { it.add(g) })
    fun removeGroup(id: String) {
        saveList("groups.json", groups().filter { it.id != id })
        val dir = File(dataDir(), "invoices/$id")
        dir.deleteRecursively()
    }
    fun renameGroup(id: String, name: String) {
        saveList("groups.json", groups().map { if (it.id == id) it.copy(name = name) else it })
    }

    fun items(groupId: String): List<InvoiceItem> {
        val file = File(dataDir(), "invoices/$groupId/items.json")
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<InvoiceItem>>() {}.type
            gson.fromJson(file.readText(), type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    /**
     * نسخ ملف (صورة/مرفق) من مسار مؤقت داخلي إلى مجلد الصور الدائم
     * الخارجي (Documents/MasahHisabat/images) حتى لا يُحذف مع التطبيق.
     * يعيد مسار الملف الجديد الدائم دائمًا.
     */
    fun persistAppImage(sourcePath: String): String? {
        return try {
            // لا نعيد مسارًا داخليًا على أنه دائم: الصور الداخلية تُحذف عند إزالة التطبيق.
            if (!isUsingExternalStorage()) return null
            val src = java.io.File(sourcePath)
            if (!src.exists()) return null
            val destDir = File(dataDir(), "images")
            destDir.mkdirs()
            val dest = File(destDir, "img_${System.currentTimeMillis()}_${src.name}")
            src.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            dest.absolutePath
        } catch (e: Exception) { null }
    }

    /**
     * اختيار النسخة القابلة للقراءة من الصورة. الأصل الدائم هو الأولوية دائماً؛
     * النسخة المعالجة تُستخدم فقط إذا كانت هي الملف الوحيد المتاح.
     */
    fun availableImagePath(item: InvoiceItem): String? {
        return listOf(item.imagePath, item.processedPath)
            .firstOrNull { path -> !path.isNullOrBlank() && File(path).isFile && File(path).length() > 0L }
    }

    /**
     * إعادة ربط أي مسارات صور داخلية مؤقتة (cacheDir / filesDir) بمسارات دائمة خارجية
     * عند اكتشاف بيانات قديمة — يُستدعى عند كل تحميل للعناصر حتى تبقى الصور دائمة.
     */
    fun remapTempImagePaths(): Boolean {
        return try {
            val ctx = appContext ?: return false
            val internalPrefixes = listOf(ctx.cacheDir.absolutePath, ctx.filesDir.absolutePath)
            val gs = groups()
            var changed = false
            for (g in gs) {
                val all = items(g.id)
                val remapped = all.map { item ->
                    val newImg = item.imagePath?.takeIf { p ->
                        p.isNotBlank() && internalPrefixes.any { p.startsWith(it) } && java.io.File(p).exists()
                    }?.let { persistAppImage(it) }
                    if (newImg != null) changed = true
                    if (newImg != null) item.copy(imagePath = newImg) else item
                }
                if (remapped != all) {
                    val dir = File(dataDir(), "invoices/${g.id}")
                    dir.mkdirs()
                    File(dir, "items.json").writeText(gson.toJson(remapped))
                }
            }
            changed
        } catch (e: Exception) { false }
    }

    fun addItem(groupId: String, item: InvoiceItem) {
        val dir = File(dataDir(), "invoices/$groupId")
        dir.mkdirs()
        val list = items(groupId).toMutableList().also { it.add(0, item) }
        File(dir, "items.json").writeText(gson.toJson(list))
    }

    fun updateItem(groupId: String, item: InvoiceItem) {
        val dir = File(dataDir(), "invoices/$groupId")
        File(dir, "items.json").writeText(gson.toJson(items(groupId).map { if (it.id == item.id) item else it }))
    }

    fun removeItem(groupId: String, itemId: String) {
        val item = items(groupId).find { it.id == itemId }
        item?.imagePath?.let { File(it).delete() }
        item?.processedPath?.let { File(it).delete() }
        val dir = File(dataDir(), "invoices/$groupId")
        File(dir, "items.json").writeText(gson.toJson(items(groupId).filter { it.id != itemId }))
    }

    fun removeItems(groupId: String, ids: List<String>) {
        val all = items(groupId)
        all.filter { it.id in ids }.forEach {
            it.imagePath?.let { p -> File(p).delete() }
            it.processedPath?.let { p -> File(p).delete() }
        }
        val dir = File(dataDir(), "invoices/$groupId")
        File(dir, "items.json").writeText(gson.toJson(all.filter { it.id !in ids }))
    }

    // ---------- سجل النشاط ----------
    fun logActivity(entry: ActivityEntry) {
        saveList("activity.json", activityLog().toMutableList().also { it.add(0, entry) }.take(500))
    }

    // ---------- سجل المزامنة ----------
    fun logSync(entry: SyncEntry) {
        saveList("synclog.json", syncLog().toMutableList().also { it.add(0, entry) }.take(500))
    }
    fun clearSyncLog() = saveList("synclog.json", emptyList<SyncEntry>())

    // ---------- التفضيلات ----------
    fun isNightMode(): Boolean = prefs.getBoolean("night_mode", true)
    fun setNightMode(night: Boolean) = prefs.edit().putBoolean("night_mode", night).apply()
    /**
     * اختيار المظهر المحفوظ. النسخ السابقة كانت تحفظ boolean فقط؛ لذلك نقرأه
     * كخيار يدوي عند عدم وجود القيمة النصية الجديدة، حتى لا يتغير مظهر المستخدم فجأة.
     */
    fun themeMode(): String {
        val saved = prefs.getString("theme_mode", null)
        if (saved in setOf("system", "light", "dark")) return saved!!
        return if (isNightMode()) "dark" else "light"
    }
    fun setThemeMode(mode: String) {
        val valid = mode.takeIf { it in setOf("system", "light", "dark") } ?: "system"
        prefs.edit()
            .putString("theme_mode", valid)
            // الإبقاء على المفتاح القديم متزامنًا للتوافق مع أي إصدار سابق.
            .putBoolean("night_mode", valid == "dark")
            .apply()
    }
    fun rememberLogin(username: String) = prefs.edit().putString("remember_user", username).apply()
    fun rememberedLogin(): String? = prefs.getString("remember_user", null)
    fun clearRemember() = prefs.edit().remove("remember_user").apply()
    fun lastProcessMode(): String = prefs.getString("last_process_mode", "auto") ?: "auto"
    fun setLastProcessMode(mode: String) = prefs.edit().putString("last_process_mode", mode).apply()
    fun lastInvoiceName(): String? = prefs.getString("last_invoice_name", null)
    fun setLastInvoiceName(name: String) = prefs.edit().putString("last_invoice_name", name).apply()
    fun lastSavedSearch(groupId: String): String = prefs.getString("saved_search_$groupId", "") ?: ""
    fun setLastSavedSearch(groupId: String, query: String) = prefs.edit().putString("saved_search_$groupId", query).apply()

    // ---------- دعم المزامنة ----------
    fun currentUserDeviceName(): String {
        val admin = users().find { it.role == Role.ADMIN }?.username ?: "masah-device"
        return admin
    }

    // ---------- أدوات عامة ----------
    fun <T> loadList(fileName: String, clazz: Class<T>): List<T> {
        val file = File(dataDir(), fileName)
        if (!file.exists()) return emptyList()
        return try {
            val type = TypeToken.getParameterized(List::class.java, clazz).type
            val raw: List<*> = gson.fromJson(file.readText(), type) ?: return emptyList()
            val typed = raw.filterIsInstance(clazz)
            typed
        } catch (e: Exception) { emptyList() }
    }

    fun <T> saveList(fileName: String, list: List<T>) {
        dataDir().mkdirs()
        File(dataDir(), fileName).writeText(gson.toJson(list))
    }

    // ---------- دوال قراءة محددة النوع ----------
    fun users(): List<User> = loadList("users.json", User::class.java)
    fun groups(): List<Group> = loadList("groups.json", Group::class.java)
    fun activityLog(): List<ActivityEntry> = loadList("activity.json", ActivityEntry::class.java)
    fun syncLog(): List<SyncEntry> = loadList("synclog.json", SyncEntry::class.java)
    fun totalInvoiceCount(): Int = groups().sumOf { items(it.id).size }

    fun exportData(outDir: File): File {
        val zipFile = File(outDir, "masah_backup_${System.currentTimeMillis()}.zip")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
            dataDir().walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(dataDir()).path
                zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    fun importBackup(zipFile: File) {
        // 1) تحقق من صحة الملف: يجب أن يحتوي groups.json و users.json
        val valid = java.util.zip.ZipFile(zipFile).use { zf ->
            val names = zf.entries().toList().map { it.name }.toSet()
            names.contains("groups.json") && names.contains("users.json")
        }
        if (!valid) throw IllegalArgumentException("invalid")
        // 2) فك الضغط إلى مجلد مؤقت ثم الاستبدال
        val tempDir = File(dataDir().parentFile, "import_tmp").also { it.deleteRecursively(); it.mkdirs() }
        java.util.zip.ZipFile(zipFile).use { zf ->
            zf.entries().toList().forEach { entry ->
                val out = File(tempDir, entry.name)
                out.parentFile.mkdirs()
                zf.getInputStream(entry).use { it.copyTo(out.outputStream()) }
            }
        }
        dataDir().deleteRecursively()
        tempDir.copyRecursively(dataDir(), overwrite = true)
        tempDir.deleteRecursively()
    }
}
