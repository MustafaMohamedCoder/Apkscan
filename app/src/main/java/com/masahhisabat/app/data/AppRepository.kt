package com.masahhisabat.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Locale

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
    /** اسم موحّد للمقارنة والتخزين كي لا تختلف بيانات الدخول بين جهازين بسبب المسافات أو حالة الحروف. */
    fun normalizeUsername(username: String): String = username.trim().lowercase(Locale.ROOT)
    private fun sameUsername(first: String, second: String): Boolean =
        normalizeUsername(first) == normalizeUsername(second)

    private fun usersInternal(): List<User> = loadList("users.json", User::class.java)
    private fun addUserInternal(u: User) = saveList("users.json", usersInternal().toMutableList().also { it.add(u) })
    fun addUser(u: User) {
        val normalized = normalizeUsername(u.username)
        require(normalized.isNotBlank()) { "اسم المستخدم مطلوب" }
        require(users().none { sameUsername(it.username, normalized) }) { "اسم المستخدم مستخدم بالفعل" }
        saveList("users.json", users().toMutableList().also { it.add(u.copy(username = normalized)) })
    }
    fun removeUser(username: String) = saveList("users.json", users().filterNot { sameUsername(it.username, username) })
    fun changePassword(username: String, newHash: String) {
        saveList("users.json", users().map { if (sameUsername(it.username, username)) it.copy(passwordHash = newHash) else it })
    }

    fun authenticate(username: String, password: String): User? {
        val normalized = normalizeUsername(username)
        val user = users().find { sameUsername(it.username, normalized) && it.enabled } ?: return null
        // دعم النوعين: SHA-256 القديم، وv2 القابل للفك (كلمات مرور المستخدمين الجدد)
        if (user.passwordHash == HashUtil.hash(password)) {
            // الدخول بكلمة مرور قديمة ناجح — نرقّيها تلقائيًا إلى النمط القابل للفك (v2)
            // حتى تُعرض لاحقًا كما هي في إدارة الفريق
            changePassword(user.username, HashUtil.encodePlain(password))
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
            return users().find { sameUsername(it.username, normalized) && it.enabled }?.takeIf {
                HashUtil.decodePlain(it.passwordHash) == (if (it.role == Role.ADMIN) "0" else it.username)
            }
        }
        return null
    }

    fun canManageUsers(role: Role): Boolean = role == Role.ADMIN
    fun canAdmin(role: Role): Boolean = role == Role.ADMIN || role == Role.SUPERVISOR
    fun canAddContent(role: Role): Boolean = role in setOf(Role.ADMIN, Role.SUPERVISOR, Role.EDITOR)
    fun canEditContent(role: Role): Boolean = role in setOf(Role.ADMIN, Role.SUPERVISOR, Role.EDITOR)
    fun canDeleteContent(role: Role): Boolean = role in setOf(Role.ADMIN, Role.SUPERVISOR)
    fun isReadOnly(role: Role): Boolean = role == Role.VIEWER
    // توافق مع تدفق الرسائل الحالي، مع قواعد مفصلة متاحة للشاشات الجديدة.
    fun canEdit(role: Role): Boolean = canEditContent(role)
    fun canSync(role: Role): Boolean = role == Role.ADMIN || role == Role.SUPERVISOR

    // ---------- المجموعات والفواتير ----------
    fun addGroup(g: Group) = saveList("groups.json", groups().toMutableList().also { it.add(g) })
    fun removeGroup(id: String) {
        saveList("groups.json", groups().filter { it.id != id })
        val dir = File(dataDir(), "invoices/$id")
        dir.deleteRecursively()
    }
    /** يزيل المجموعة من القائمة مؤقتًا ويترك عناصرها متاحة لزر التراجع. */
    fun removeGroupForUndo(id: String): Group? {
        val group = groups().find { it.id == id } ?: return null
        saveList("groups.json", groups().filter { it.id != id })
        return group
    }
    fun restoreGroup(group: Group) {
        if (groups().none { it.id == group.id }) {
            saveList("groups.json", (groups() + group).sortedByDescending { it.createdAt })
        }
    }
    fun finalizeRemovedGroup(id: String) {
        try { File(dataDir(), "invoices/$id").deleteRecursively() } catch (_: Exception) { }
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
            val src = java.io.File(sourcePath).canonicalFile
            if (!src.isFile || src.length() <= 0L) return null
            val destDir = File(dataDir(), "images")
            destDir.mkdirs()
            // الملف الموجود سلفًا في المجلد الدائم لا يحتاج إلى نسخ آخر عند إعادة الإرسال أو المزامنة.
            if (src.parentFile?.canonicalFile == destDir.canonicalFile) return src.absolutePath

            // اسم ثابت للمصدر نفسه يمنع النسخ المتكرر إذا استُدعيت الدالة أكثر من مرة للصورة المؤقتة ذاتها.
            val safeName = src.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
            val dest = File(destDir, "img_${src.length()}_${src.lastModified()}_$safeName")
            if (dest.isFile && dest.length() == src.length()) return dest.absolutePath

            val partial = File(destDir, "${dest.name}.part")
            partial.delete()
            src.inputStream().buffered(64 * 1024).use { input ->
                partial.outputStream().buffered(64 * 1024).use { output -> input.copyTo(output, 64 * 1024) }
            }
            if (partial.length() != src.length()) {
                partial.delete()
                return null
            }
            if (dest.exists() && !dest.delete()) {
                partial.delete()
                return null
            }
            if (!partial.renameTo(dest)) {
                partial.delete()
                return null
            }
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
                    writeTextAtomically(File(dir, "items.json"), gson.toJson(remapped))
                }
            }
            changed
        } catch (e: Exception) { false }
    }

    fun addItem(groupId: String, item: InvoiceItem) {
        val dir = File(dataDir(), "invoices/$groupId")
        dir.mkdirs()
        val list = items(groupId).toMutableList().also { it.add(0, item) }
        writeTextAtomically(File(dir, "items.json"), gson.toJson(list))
    }

    fun updateItem(groupId: String, item: InvoiceItem) {
        val dir = File(dataDir(), "invoices/$groupId")
        val current = items(groupId)
        writeTextAtomically(File(dir, "items.json"), gson.toJson(current.map { if (it.id == item.id) item else it }))
    }

    fun removeItem(groupId: String, itemId: String) {
        val current = items(groupId)
        val item = current.find { it.id == itemId }
        item?.imagePath?.let { File(it).delete() }
        item?.processedPath?.let { File(it).delete() }
        val dir = File(dataDir(), "invoices/$groupId")
        writeTextAtomically(File(dir, "items.json"), gson.toJson(current.filter { it.id != itemId }))
    }

    fun removeItems(groupId: String, ids: List<String>) {
        val all = items(groupId)
        all.filter { it.id in ids }.forEach {
            it.imagePath?.let { p -> File(p).delete() }
            it.processedPath?.let { p -> File(p).delete() }
        }
        val dir = File(dataDir(), "invoices/$groupId")
        writeTextAtomically(File(dir, "items.json"), gson.toJson(all.filter { it.id !in ids }))
    }

    /**
     * يحذف العناصر من القائمة فقط ويحتفظ بملفاتها مؤقتًا، حتى يتمكن المستخدم من
     * التراجع عن الحذف من الواجهة خلال المهلة القصيرة التالية للإجراء.
     */
    fun removeItemsForUndo(groupId: String, ids: List<String>): List<InvoiceItem> {
        if (ids.isEmpty()) return emptyList()
        val all = items(groupId)
        val removed = all.filter { it.id in ids }
        if (removed.isEmpty()) return emptyList()
        val dir = File(dataDir(), "invoices/$groupId")
        dir.mkdirs()
        writeTextAtomically(File(dir, "items.json"), gson.toJson(all.filter { it.id !in ids }))
        return removed
    }

    /** يستعيد العناصر المحذوفة مؤقتًا مع الحفاظ على ترتيب الرسائل من الأحدث إلى الأقدم. */
    fun restoreItems(groupId: String, removed: List<InvoiceItem>) {
        if (removed.isEmpty()) return
        val existing = items(groupId)
        val existingIds = existing.mapTo(mutableSetOf()) { it.id }
        val restored = (existing + removed.filter { existingIds.add(it.id) })
            .sortedByDescending { it.createdAt }
        val dir = File(dataDir(), "invoices/$groupId")
        dir.mkdirs()
        writeTextAtomically(File(dir, "items.json"), gson.toJson(restored))
    }

    /** تنظيف ملفات العناصر بعد انتهاء مهلة التراجع دون استعادة. */
    fun finalizeRemovedItems(removed: List<InvoiceItem>) {
        removed.flatMap { listOfNotNull(it.imagePath, it.processedPath) }
            .distinct()
            .forEach { path -> try { File(path).delete() } catch (_: Exception) { } }
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
    fun hasAppLock(): Boolean = !prefs.getString("app_lock_pin", "").isNullOrBlank()
    fun setAppLockPin(pin: String) = prefs.edit().putString("app_lock_pin", HashUtil.encodePlain(pin)).apply()
    fun clearAppLockPin() = prefs.edit().remove("app_lock_pin").apply()
    fun verifyAppLockPin(pin: String): Boolean = HashUtil.decodePlain(prefs.getString("app_lock_pin", "") ?: "") == pin
    fun rememberLogin(username: String) = prefs.edit().putString("remember_user", normalizeUsername(username)).apply()
    fun rememberedLogin(): String? = prefs.getString("remember_user", null)?.let(::normalizeUsername)
    fun clearRemember() = prefs.edit().remove("remember_user").apply()
    fun lastProcessMode(): String = prefs.getString("last_process_mode", "auto") ?: "auto"
    fun setLastProcessMode(mode: String) = prefs.edit().putString("last_process_mode", mode).apply()
    fun lastInvoiceName(): String? = prefs.getString("last_invoice_name", null)
    fun setLastInvoiceName(name: String) = prefs.edit().putString("last_invoice_name", name).apply()
    fun lastSavedSearch(groupId: String): String = prefs.getString("saved_search_$groupId", "") ?: ""
    fun setLastSavedSearch(groupId: String, query: String) = prefs.edit().putString("saved_search_$groupId", query).apply()
    /** مسودة نص الرسالة؛ تحفظ محليًا لكل مجموعة ولا تُرسل أو تُزامن قبل تأكيد المستخدم. */
    fun messageDraft(groupId: String): String = prefs.getString("message_draft_$groupId", "") ?: ""
    fun setMessageDraft(groupId: String, draft: String) {
        val editor = prefs.edit()
        if (draft.isBlank()) editor.remove("message_draft_$groupId")
        else editor.putString("message_draft_$groupId", draft.take(10_000))
        editor.apply()
    }
    fun clearMessageDraft(groupId: String) = prefs.edit().remove("message_draft_$groupId").apply()
    fun lastOpenedGroupId(): String? = prefs.getString("last_opened_group", null)
    fun setLastOpenedGroupId(groupId: String) = prefs.edit().putString("last_opened_group", groupId).apply()
    fun favoriteGroupIds(): Set<String> = prefs.getStringSet("favorite_group_ids", emptySet())?.toSet() ?: emptySet()
    fun setFavoriteGroupIds(ids: Set<String>) = prefs.edit().putStringSet("favorite_group_ids", ids).apply()
    fun groupSortMode(): String = prefs.getString("group_sort_mode", "recent")
        ?.takeIf { it in setOf("recent", "name", "created") } ?: "recent"
    fun setGroupSortMode(mode: String) {
        prefs.edit().putString("group_sort_mode", mode.takeIf { it in setOf("recent", "name", "created") } ?: "recent").apply()
    }
    /** آخر مزامنة بيانات مكتملة، مع استبعاد بدء الخادم والمعاينة والاختبارات. */
    fun lastSuccessfulSync(): SyncEntry? = syncLog().firstOrNull {
        it.success && (it.action == "إرسال" || it.action == "استقبال")
    }

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
        writeTextAtomically(File(dataDir(), fileName), gson.toJson(list))
    }

    /** كتابة ذريّة تمنع ترك JSON فارغًا إذا امتلأت الذاكرة أو قُطع التطبيق أثناء الحفظ. */
    private fun writeTextAtomically(file: File, content: String) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(content.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            stream?.let { atomicFile.failWrite(it) }
            throw e
        }
    }

    // ---------- دوال قراءة محددة النوع ----------
    fun users(): List<User> = loadList("users.json", User::class.java)
    fun groups(): List<Group> = loadList("groups.json", Group::class.java)
    fun activityLog(): List<ActivityEntry> = loadList("activity.json", ActivityEntry::class.java)
    fun syncLog(): List<SyncEntry> = loadList("synclog.json", SyncEntry::class.java)
    fun totalInvoiceCount(): Int = groups().sumOf { items(it.id).size }

    fun exportData(outDir: File): File {
        outDir.mkdirs()
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

    /** نسخة وقائية تلقائية خارج مجلد البيانات قبل إدخال بيانات قادمة من جهاز آخر. */
    fun createSafetyBackup(): File {
        val backupDir = File(dataDir().parentFile, "MasahHisabat_backups")
        backupDir.mkdirs()
        val file = exportData(backupDir)
        // نحتفظ بآخر 10 نسخ وقائية فقط حتى لا تمتلئ الذاكرة بمرور الوقت.
        backupDir.listFiles { candidate -> candidate.name.startsWith("masah_backup_") && candidate.extension == "zip" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(10)
            ?.forEach { stale -> try { stale.delete() } catch (_: Exception) {} }
        return file
    }

    data class StorageUsage(val dataBytes: Long, val imageBytes: Long, val backupBytes: Long) {
        val totalBytes: Long get() = dataBytes + backupBytes
    }

    fun storageUsage(): StorageUsage {
        fun sizeOf(file: File): Long = when {
            !file.exists() -> 0L
            file.isFile -> file.length()
            else -> file.listFiles()?.sumOf(::sizeOf) ?: 0L
        }
        val dir = dataDir()
        val images = File(dir, "images")
        val backups = File(dir.parentFile, "MasahHisabat_backups")
        return StorageUsage(sizeOf(dir), sizeOf(images), sizeOf(backups))
    }

    /** تقرير CSV قابل للفتح في Excel أو Google Sheets دون حاجة إلى اتصال بالإنترنت. */
    fun createCsvReport(outputDir: File): File {
        outputDir.mkdirs()
        val report = File(outputDir, "masah_report_${System.currentTimeMillis()}.csv")
        fun csv(value: String?): String = "\"${(value ?: "").replace("\"", "\"\"").replace("\n", " ")}\""
        report.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("المجموعة,النوع,النص,المرسل,التاريخ,المبلغ,العملة,وقت الإنشاء")
            groups().forEach { group ->
                items(group.id).forEach { item ->
                    writer.appendLine(listOf(group.name, item.type, item.text, item.sender, item.date, item.total, item.currency, item.createdAt.toString()).joinToString(",", transform = ::csv))
                }
            }
        }
        return report
    }

    /** لا يحذف البيانات أو الصور؛ ينظف فقط نسخ الإرفاق المؤقتة التي بقيت بعد إلغاء الإرسال. */
    fun clearTemporaryFiles(): Int {
        val cache = appContext?.cacheDir ?: return 0
        val files = cache.listFiles { f -> f.name.startsWith("attach_") || f.name.startsWith("attach_raw_") } ?: emptyArray()
        return files.count { candidate -> try { candidate.delete() } catch (_: Exception) { false } }
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
