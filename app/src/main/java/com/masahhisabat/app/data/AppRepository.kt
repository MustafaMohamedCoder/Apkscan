package com.masahhisabat.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * مستودع محلي شامل: مستخدمون، مجموعات، فواتير، سجل نشاط، سجل مزامنة، تفضيلات.
 * كل شيء يُحفظ في مجلد التطبيق الداخلي (filesDir) بصيغة JSON + صور.
 */
object AppRepository {

    private const val AUTO_TRASH_PURGE_KEY = "auto_purge_trash_after_30_days"
    private const val TRASH_WARNING_NOTIFIED_IDS_KEY = "trash_warning_notified_ids"
    private const val TRASH_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    private const val TRASH_WARNING_WINDOW_MS = 3L * 24L * 60L * 60L * 1000L
    private const val INVOICE_REMINDERS_ENABLED_KEY = "invoice_reminders_enabled"
    private const val CALL_RINGTONE_ENABLED_KEY = "call_ringtone_enabled"
    private const val CALL_VIBRATION_ENABLED_KEY = "call_vibration_enabled"
    private const val CALL_RINGTONE_URI_KEY = "call_ringtone_uri"
    private const val CALL_ICE_FAILURE_ALERT_ENABLED_KEY = "call_ice_failure_alert_enabled"
    private const val CALL_ICE_FAILURE_TIMESTAMPS_KEY = "call_ice_failure_timestamps"
    private const val CALL_ICE_FAILURE_ALERT_WINDOW_MS = 15L * 60L * 1000L
    private const val ENCRYPTED_BACKUP_MAGIC = "MSHB1"
    private const val BACKUP_SALT_BYTES = 16
    private const val BACKUP_IV_BYTES = 12
    private const val BACKUP_KDF_ITERATIONS = 120_000

    private var appContext: Context? = null
    /** يمنع كتابة متزامنة من الإرسال المحلي والمزامنة من استبدال ملف رسائل المجموعة. */
    private val groupItemsWriteLock = Any()
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
    /** تحديث بطاقة التاجر دون تغيير المعرّف أو أرشيف الفواتير والطلبات المرتبط به. */
    fun updateTraderDetails(id: String, name: String, phone: String?, notes: String?) {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "اسم الفاتورة مطلوب" }
        saveList("groups.json", groups().map {
            if (it.id == id) it.copy(
                name = cleanName,
                supplierPhone = phone?.trim()?.takeIf { value -> value.isNotBlank() },
                supplierNotes = notes?.trim()?.takeIf { value -> value.isNotBlank() }
            ) else it
        })
    }
    /** تحفظ المجموعة في الأرشيف دون المساس برسائلها أو صورها. */
    fun setGroupArchived(id: String, archived: Boolean) {
        saveList("groups.json", groups().map {
            if (it.id == id) it.copy(archivedAt = if (archived) System.currentTimeMillis() else null) else it
        })
    }

    /** ينقل المجموعة وكل رسائلها إلى السلة دون لمس صورها أو مجلدها الدائم. */
    fun moveGroupToTrash(id: String, deletedBy: String?): TrashEntry? {
        val group = groups().find { it.id == id } ?: return null
        val entry = TrashEntry(
            type = "group",
            groupId = group.id,
            groupName = group.name,
            group = group,
            items = items(group.id),
            deletedBy = deletedBy?.let(::normalizeUsername)
        )
        saveList("groups.json", groups().filterNot { it.id == id })
        saveTrashRecords(listOf(entry) + trashRecords())
        setFavoriteGroupIds(favoriteGroupIds() - id)
        clearMessageDraft(id)
        return entry
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
            // التخزين الخارجي هو الخيار المفضل، لكن يجب ألا يفشل الإرسال عند عدم توفره.
            // نستخدم مجلد البيانات الداخلي كبديل آمن بدل إسقاط الصورة بصمت.
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

    fun addItem(groupId: String, item: InvoiceItem) = synchronized(groupItemsWriteLock) {
        val dir = File(dataDir(), "invoices/$groupId")
        dir.mkdirs()
        val list = items(groupId).toMutableList().also { current ->
            if (current.none { it.id == item.id }) current.add(0, item)
        }
        writeTextAtomically(File(dir, "items.json"), gson.toJson(list))

        // الإشعار تحسين إضافي فقط؛ لا يجوز لفشله أن يحوّل رسالة محفوظة إلى «إرسال فاشل» في الواجهة.
        runCatching {
            val groupName = groups().firstOrNull { it.id == groupId }?.name ?: "مجموعة"
            addNotification(NotificationEvent(
                title = "رسالة جديدة في $groupName",
                body = item.text?.takeIf { it.isNotBlank() } ?: if (item.imagePath != null) "تمت إضافة صورة إلى المجموعة" else "تمت إضافة رسالة جديدة",
                type = "group_message",
                actor = item.sender
            ))
        }
    }

    fun updateItem(groupId: String, item: InvoiceItem) {
        val dir = File(dataDir(), "invoices/$groupId")
        val current = items(groupId)
        writeTextAtomically(File(dir, "items.json"), gson.toJson(current.map { if (it.id == item.id) item else it }))
    }

    /** عناصر دورة المتابعة الموحدة، مرتبة بالأحدث ومن المجموعات غير المؤرشفة فقط. */
    fun invoiceWorkItems(includePaid: Boolean = true): List<Pair<Group, InvoiceItem>> {
        return groups()
            .asSequence()
            .filter { it.archivedAt == null }
            .flatMap { group ->
                items(group.id).asSequence()
                    .filter { item -> includePaid || item.status != "paid" }
                    .map { item -> group to item }
            }
            .sortedByDescending { (_, item) -> item.createdAt }
            .toList()
    }

    /** يحدّث حالة المتابعة فقط، ويلغي الاستحقاق عند إغلاق الفاتورة كمدفوعة. */
    fun updateInvoiceStatus(groupId: String, itemId: String, status: String): InvoiceItem? {
        val allowed = setOf("new", "in_review", "completed", "paid")
        val normalized = status.takeIf { it in allowed } ?: "new"
        var changed: InvoiceItem? = null
        val updated = items(groupId).map { item ->
            if (item.id != itemId) item else item.copy(
                status = normalized,
                reminderAt = if (normalized == "paid") null else item.reminderAt,
                reminderNotifiedAt = if (normalized == "paid") null else item.reminderNotifiedAt
            ).also { changed = it }
        }
        if (changed != null) {
            val dir = File(dataDir(), "invoices/$groupId")
            dir.mkdirs()
            writeTextAtomically(File(dir, "items.json"), gson.toJson(updated))
        }
        return changed
    }

    /** عناصر تذكير محلية مستحقة ولم يُعرض تنبيهها على هذا الجهاز بعد. */
    fun dueInvoiceReminders(now: Long = System.currentTimeMillis()): List<Pair<Group, InvoiceItem>> {
        if (!areInvoiceRemindersEnabled()) return emptyList()
        return groups().asSequence().filter { it.archivedAt == null }.flatMap { group ->
            items(group.id).asSequence()
                .filter { item -> item.status != "paid" && item.reminderAt != null && item.reminderAt <= now && item.reminderNotifiedAt == null }
                .map { item -> group to item }
        }.toList()
    }

    /** يسجّل عرض التنبيه كي لا يتكرر في تشغيل العامل اللاحق. */
    fun markInvoiceRemindersShown(reminders: List<Pair<Group, InvoiceItem>>) {
        val shownAt = System.currentTimeMillis()
        reminders.groupBy({ it.first.id }, { it.second }).forEach { (groupId, groupItems) ->
            val ids = groupItems.map { it.id }.toSet()
            val updated = items(groupId).map { item ->
                if (item.id in ids) item.copy(reminderNotifiedAt = shownAt) else item
            }
            val dir = File(dataDir(), "invoices/$groupId")
            dir.mkdirs()
            writeTextAtomically(File(dir, "items.json"), gson.toJson(updated))
        }
    }

    fun areInvoiceRemindersEnabled(): Boolean = prefs.getBoolean(INVOICE_REMINDERS_ENABLED_KEY, true)

    fun setInvoiceRemindersEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(INVOICE_REMINDERS_ENABLED_KEY, enabled).apply()
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

    /** ينقل الرسائل المحددة إلى السلة ويُبقي المرفقات الدائمة متاحة للاستعادة. */
    fun moveItemsToTrash(groupId: String, groupName: String, ids: List<String>, deletedBy: String?): List<TrashEntry> {
        if (ids.isEmpty()) return emptyList()
        val all = items(groupId)
        val removed = all.filter { it.id in ids }
        if (removed.isEmpty()) return emptyList()
        val entries = removed.map { item ->
            TrashEntry(
                type = "item",
                groupId = groupId,
                groupName = groupName,
                items = listOf(item),
                deletedBy = deletedBy?.let(::normalizeUsername)
            )
        }
        val dir = File(dataDir(), "invoices/$groupId")
        dir.mkdirs()
        writeTextAtomically(File(dir, "items.json"), gson.toJson(all.filterNot { it.id in ids }))
        saveTrashRecords(entries + trashRecords())
        return entries
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

    // ---------- سلة المحذوفات ----------
    /** كل سجلات حالات السلة، بما في ذلك الاستعادة والحذف النهائي، لاستخدام المزامنة فقط. */
    fun trashRecords(): List<TrashEntry> =
        loadList("trash.json", TrashEntry::class.java).sortedByDescending { it.stateChangedAt }

    /** العناصر الظاهرة للمستخدم في سلة المحذوفات. */
    fun trashEntries(): List<TrashEntry> = trashRecords().filter { it.state == "trashed" }

    /** هل يسمح المستخدم بالحذف النهائي التلقائي لعناصر السلة بعد مدة الاحتفاظ. */
    fun isAutoTrashPurgeEnabled(): Boolean = prefs.getBoolean(AUTO_TRASH_PURGE_KEY, true)

    /** يحفظ تفضيل الحذف التلقائي محليًا؛ القيمة الافتراضية مفعلة لحماية مساحة التخزين. */
    fun setAutoTrashPurgeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(AUTO_TRASH_PURGE_KEY, enabled).apply()
    }

    /**
     * عناصر السلة التي اقترب حذفها النهائي خلال ثلاثة أيام ولم يظهر لها تنبيه على هذا الجهاز.
     * حالة الإشعار محلية؛ لذلك يمكن لكل جهاز متصل إظهار تنبيهه دون التأثير في مزامنة بيانات السلة.
     */
    fun trashEntriesRequiringDeletionWarning(now: Long = System.currentTimeMillis()): List<TrashEntry> {
        if (!isAutoTrashPurgeEnabled()) return emptyList()
        val notified = prefs.getStringSet(TRASH_WARNING_NOTIFIED_IDS_KEY, emptySet()).orEmpty()
        return trashEntries().filter { entry ->
            val remaining = (entry.stateChangedAt + TRASH_RETENTION_MS) - now
            remaining in 1..TRASH_WARNING_WINDOW_MS && entry.id !in notified
        }
    }

    /** يحفظ أن التنبيه ظهر بالفعل، وينظف المعرّفات التي لم تعد تخص عناصرًا فعالة في السلة. */
    fun markTrashDeletionWarningsShown(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val activeIds = trashEntries().mapTo(mutableSetOf()) { it.id }
        val notified = prefs.getStringSet(TRASH_WARNING_NOTIFIED_IDS_KEY, emptySet())
            ?.filterTo(mutableSetOf()) { it in activeIds }
            ?: mutableSetOf()
        notified.addAll(ids.filter { it in activeIds })
        prefs.edit().putStringSet(TRASH_WARNING_NOTIFIED_IDS_KEY, notified).apply()
    }

    /**
     * يحذف نهائيًا عناصر السلة التي تجاوزت 30 يومًا فقط عند تفعيل الميزة.
     * لا يلمس الصور المرتبطة إلا عبر الحذف النهائي الآمن الموجود أصلًا.
     */
    fun purgeExpiredTrash(now: Long = System.currentTimeMillis()): Int {
        if (!isAutoTrashPurgeEnabled()) return 0
        val cutoff = now - TRASH_RETENTION_MS
        return trashEntries()
            .filter { it.stateChangedAt in 1..cutoff }
            .count { permanentlyDeleteTrashEntry(it.id) }
    }

    /** يعيد عنصرًا واحدًا من السلة ويحذفه منها عند نجاح الاستعادة. */
    fun restoreTrashEntry(trashId: String): Boolean {
        val entry = trashEntries().find { it.id == trashId } ?: return false
        val restored = when (entry.type) {
            "group" -> {
                val group = entry.group
                if (group == null || groups().any { it.id == group.id }) false
                else {
                    restoreGroup(group)
                    val dir = File(dataDir(), "invoices/${group.id}")
                    dir.mkdirs()
                    writeTextAtomically(File(dir, "items.json"), gson.toJson(entry.items))
                    true
                }
            }
            "item" -> {
                if (groups().none { it.id == entry.groupId }) false
                else {
                    restoreItems(entry.groupId, entry.items)
                    true
                }
            }
            else -> false
        }
        if (restored) updateTrashState(trashId, "restored")
        return restored
    }

    /** يحذف عنصر السلة نهائيًا، مع حذف صوره فقط إذا لم تعد مستخدمة في بيانات أخرى. */
    fun permanentlyDeleteTrashEntry(trashId: String): Boolean {
        val entry = trashEntries().find { it.id == trashId } ?: return false
        updateTrashState(trashId, "purged")
        if (entry.type == "group") {
            try { File(dataDir(), "invoices/${entry.groupId}").deleteRecursively() } catch (_: Exception) { }
        }
        deleteUnreferencedAttachments(entry)
        return true
    }

    /** يفرغ السلة بالكامل؛ لا يمس إلا المرفقات غير المرتبطة ببيانات فعّالة. */
    fun emptyTrash(): Int {
        val entries = trashEntries()
        if (entries.isEmpty()) return 0
        entries.forEach { permanentlyDeleteTrashEntry(it.id) }
        return entries.size
    }

    /** يدمج سجلات السلة الواردة ثم يطبّق حالتها قبل دمج العناصر النشطة. */
    fun mergeTrashRecords(incoming: List<TrashEntry>): Int {
        if (incoming.isEmpty()) return 0
        val current = trashRecords().toMutableList()
        val updates = mutableListOf<TrashEntry>()
        incoming.sortedBy { it.stateChangedAt }.forEach { record ->
            val index = current.indexOfFirst { it.id == record.id }
            val existing = current.getOrNull(index)
            if (existing == null || record.stateChangedAt > existing.stateChangedAt) {
                if (index >= 0) current[index] = record else current.add(record)
                updates.add(record)
            }
        }
        if (updates.isEmpty()) return 0
        saveTrashRecords(current)
        updates.forEach(::applyTrashState)
        return updates.size
    }

    /** هل يجب منع مزامنة مجموعة أو رسالة من العودة من حمولة جهاز آخر؟ */
    fun isGroupTrashed(groupId: String): Boolean = trashRecords().any {
        it.state != "restored" && it.type == "group" && it.groupId == groupId
    }
    fun isItemTrashed(groupId: String, itemId: String): Boolean = trashRecords().any { entry ->
        entry.state != "restored" && entry.type == "item" && entry.groupId == groupId && entry.items.any { it.id == itemId }
    }

    private fun updateTrashState(trashId: String, state: String) {
        val changedAt = System.currentTimeMillis()
        saveTrashRecords(trashRecords().map { entry ->
            if (entry.id == trashId) entry.copy(state = state, stateChangedAt = changedAt) else entry
        })
    }

    private fun saveTrashRecords(records: List<TrashEntry>) {
        saveList("trash.json", records.sortedByDescending { it.stateChangedAt })
    }

    private fun applyTrashState(entry: TrashEntry) {
        when (entry.state) {
            "trashed", "purged" -> {
                if (entry.type == "group") {
                    if (groups().any { it.id == entry.groupId }) {
                        saveList("groups.json", groups().filterNot { it.id == entry.groupId })
                        setFavoriteGroupIds(favoriteGroupIds() - entry.groupId)
                        clearMessageDraft(entry.groupId)
                    }
                } else if (entry.type == "item") {
                    removeActiveItemsWithoutAttachments(entry.groupId, entry.items.map { it.id }.toSet())
                }
                if (entry.state == "purged") {
                    if (entry.type == "group") {
                        try { File(dataDir(), "invoices/${entry.groupId}").deleteRecursively() } catch (_: Exception) { }
                    }
                    deleteUnreferencedAttachments(entry)
                }
            }
            "restored" -> {
                if (entry.type == "group") {
                    entry.group?.let(::restoreGroup)
                }
                if (groups().any { it.id == entry.groupId }) restoreItems(entry.groupId, entry.items)
            }
        }
    }

    private fun removeActiveItemsWithoutAttachments(groupId: String, ids: Set<String>) {
        if (ids.isEmpty()) return
        val all = items(groupId)
        if (all.none { it.id in ids }) return
        val dir = File(dataDir(), "invoices/$groupId").apply { mkdirs() }
        writeTextAtomically(File(dir, "items.json"), gson.toJson(all.filterNot { it.id in ids }))
    }

    private fun deleteUnreferencedAttachments(entry: TrashEntry) {
        entry.items.flatMap { listOfNotNull(it.imagePath, it.processedPath) }
            .distinct()
            .filterNot(::isAttachmentReferenced)
            .forEach { path -> try { File(path).delete() } catch (_: Exception) { } }
    }

    private fun isAttachmentReferenced(path: String): Boolean {
        val activeReference = groups().asSequence()
            .flatMap { items(it.id).asSequence() }
            .any { item -> item.imagePath == path || item.processedPath == path }
        if (activeReference) return true
        return trashEntries().asSequence()
            .flatMap { it.items.asSequence() }
            .any { item -> item.imagePath == path || item.processedPath == path }
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

    fun recordSyncDevice(
        address: String,
        name: String,
        success: Boolean? = null,
        error: String? = null
    ) {
        val now = System.currentTimeMillis()
        val updated = syncDevices().toMutableList()
        val index = updated.indexOfFirst { it.address == address }
        val previous = updated.getOrNull(index)
        val status = SyncDeviceStatus(
            address = address,
            name = name.ifBlank { previous?.name ?: address },
            lastSeenAt = now,
            lastSyncAt = success?.let { now } ?: previous?.lastSyncAt,
            lastSyncSuccess = success ?: previous?.lastSyncSuccess,
            lastError = if (success == true) null else error ?: previous?.lastError
        )
        if (index >= 0) updated[index] = status else updated.add(status)
        saveList("sync_devices.json", updated.sortedByDescending { it.lastSeenAt }.take(50))
    }

    fun logSyncConflict(conflict: SyncConflict) {
        saveList("sync_conflicts.json", syncConflicts().toMutableList().also { it.add(0, conflict) }.take(200))
    }

    fun clearSyncConflicts() = saveList("sync_conflicts.json", emptyList<SyncConflict>())

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
    fun clearAppLockPin() = prefs.edit().remove("app_lock_pin").remove("app_lock_biometric").apply()
    fun verifyAppLockPin(pin: String): Boolean = HashUtil.decodePlain(prefs.getString("app_lock_pin", "") ?: "") == pin
    /** مهلة القفل تحفظ بالملي ثانية وتبقى ضمن خيارات واجهة الإعدادات المعتمدة فقط. */
    fun appLockTimeoutMs(): Long = prefs.getLong("app_lock_timeout_ms", 30_000L)
        .takeIf { it in setOf(0L, 30_000L, 60_000L, 300_000L) } ?: 30_000L
    fun setAppLockTimeoutMs(timeoutMs: Long) {
        val safe = timeoutMs.takeIf { it in setOf(0L, 30_000L, 60_000L, 300_000L) } ?: 30_000L
        prefs.edit().putLong("app_lock_timeout_ms", safe).apply()
    }
    fun isScreenPrivacyEnabled(): Boolean = prefs.getBoolean("app_lock_screen_privacy", false)
    fun setScreenPrivacyEnabled(enabled: Boolean) = prefs.edit().putBoolean("app_lock_screen_privacy", enabled).apply()
    fun isBiometricUnlockEnabled(): Boolean = prefs.getBoolean("app_lock_biometric", false)
    fun setBiometricUnlockEnabled(enabled: Boolean) = prefs.edit().putBoolean("app_lock_biometric", enabled).apply()
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
        ?.takeIf { it in setOf("recent", "name", "created", "created_oldest", "size_desc", "size_asc") } ?: "recent"
    fun setGroupSortMode(mode: String) {
        prefs.edit().putString("group_sort_mode", mode.takeIf {
            it in setOf("recent", "name", "created", "created_oldest", "size_desc", "size_asc")
        } ?: "recent").apply()
    }
    fun groupFilterMode(): String = prefs.getString("group_filter_mode", "all")
        ?.takeIf { it in setOf("all", "with_documents", "empty", "pinned", "recent_30d") } ?: "all"
    fun setGroupFilterMode(mode: String) {
        prefs.edit().putString("group_filter_mode", mode.takeIf {
            it in setOf("all", "with_documents", "empty", "pinned", "recent_30d")
        } ?: "all").apply()
    }
    /** تنبيهات المكالمات تحفظ محليًا على الجهاز ولا تُرسل عبر الشبكة. */
    fun isCallRingtoneEnabled(): Boolean = prefs.getBoolean(CALL_RINGTONE_ENABLED_KEY, true)
    fun setCallRingtoneEnabled(enabled: Boolean) = prefs.edit().putBoolean(CALL_RINGTONE_ENABLED_KEY, enabled).apply()
    fun isCallVibrationEnabled(): Boolean = prefs.getBoolean(CALL_VIBRATION_ENABLED_KEY, true)
    fun setCallVibrationEnabled(enabled: Boolean) = prefs.edit().putBoolean(CALL_VIBRATION_ENABLED_KEY, enabled).apply()
    fun callRingtoneUri(): String? = prefs.getString(CALL_RINGTONE_URI_KEY, null)?.takeIf { it.isNotBlank() }
    fun setCallRingtoneUri(uri: String?) {
        prefs.edit().apply {
            if (uri.isNullOrBlank()) remove(CALL_RINGTONE_URI_KEY) else putString(CALL_RINGTONE_URI_KEY, uri)
        }.apply()
    }
    fun isIceFailureAlertEnabled(): Boolean = prefs.getBoolean(CALL_ICE_FAILURE_ALERT_ENABLED_KEY, true)
    fun setIceFailureAlertEnabled(enabled: Boolean) {
        prefs.edit().apply {
            putBoolean(CALL_ICE_FAILURE_ALERT_ENABLED_KEY, enabled)
            if (!enabled) remove(CALL_ICE_FAILURE_TIMESTAMPS_KEY)
        }.apply()
    }

    /** يعيد true مرة واحدة عند الفشل الثالث خلال 15 دقيقة، ولا يرسل أي سجل خارج الجهاز. */
    fun recordIceFailureAndShouldAlert(now: Long = System.currentTimeMillis()): Boolean {
        if (!isIceFailureAlertEnabled()) return false
        val recent = prefs.getString(CALL_ICE_FAILURE_TIMESTAMPS_KEY, "").orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { timestamp -> now - timestamp in 0..CALL_ICE_FAILURE_ALERT_WINDOW_MS }
        val updated = (recent + now).takeLast(10)
        prefs.edit().putString(CALL_ICE_FAILURE_TIMESTAMPS_KEY, updated.joinToString(",")).apply()
        return updated.size == 3
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
    fun directMessages(): List<DirectMessage> = loadList("direct_messages.json", DirectMessage::class.java)
        .sortedBy { it.createdAt }
    fun directConversation(first: String, second: String): List<DirectMessage> = directMessages()
        .filter { (it.fromUser == first && it.toUser == second) || (it.fromUser == second && it.toUser == first) }
        .sortedBy { it.createdAt }
    fun addDirectMessage(message: DirectMessage) {
        saveList("direct_messages.json", (directMessages() + message).distinctBy { it.id }.sortedBy { it.createdAt }.takeLast(2_000))
        logActivity(ActivityEntry(message.fromUser, "أرسل رسالة مباشرة إلى ${message.toUser}"))
    }
    fun notifications(): List<NotificationEvent> = loadList("notifications.json", NotificationEvent::class.java)
        .sortedByDescending { it.createdAt }
    fun unreadNotificationCount(): Int = notifications().count { !it.read }
    fun addNotification(event: NotificationEvent) {
        saveList("notifications.json", (notifications() + event).distinctBy { it.id }.sortedByDescending { it.createdAt }.take(500))
    }
    fun markNotificationsRead() = saveList("notifications.json", notifications().map { it.copy(read = true) })
    fun presence(): List<UserPresence> = loadList("presence.json", UserPresence::class.java)
    fun touchPresence(username: String) {
        val updated = presence().filterNot { it.username == username } + UserPresence(username)
        saveList("presence.json", updated.sortedByDescending { it.lastSeenAt }.take(200))
    }
    fun isUserOnline(username: String, now: Long = System.currentTimeMillis()): Boolean =
        presence().firstOrNull { it.username == username }?.let { now - it.lastSeenAt <= 90_000L } == true

    // ---------- سجل المكالمات المحلية ----------
    fun callLogs(): List<CallLog> = loadList("call_logs.json", CallLog::class.java)
        .sortedByDescending { it.startedAt }

    fun addCallLog(log: CallLog) {
        saveList("call_logs.json", (callLogs() + log).distinctBy { it.id }
            .sortedByDescending { it.startedAt }.take(500))
    }

    fun updateCallLog(id: String, update: (CallLog) -> CallLog) {
        saveList("call_logs.json", callLogs().map { if (it.id == id) update(it) else it })
    }

    fun activityLog(): List<ActivityEntry> = loadList("activity.json", ActivityEntry::class.java)
    fun syncLog(): List<SyncEntry> = loadList("synclog.json", SyncEntry::class.java)
    fun syncDevices(): List<SyncDeviceStatus> = loadList("sync_devices.json", SyncDeviceStatus::class.java)
    fun syncConflicts(): List<SyncConflict> = loadList("sync_conflicts.json", SyncConflict::class.java)
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

    /** ينشئ نسخة كاملة مشفرة محليًا؛ لا تحفظ كلمة المرور ولا تغادر الجهاز. */
    fun exportEncryptedData(outDir: File, passphrase: String): File {
        require(passphrase.length >= 6) { "weak_passphrase" }
        outDir.mkdirs()
        val temporaryDir = File(outDir, ".masah_backup_tmp").also { it.mkdirs() }
        val plainZip = exportData(temporaryDir)
        val encrypted = File(outDir, "masah_backup_${System.currentTimeMillis()}.masahbak")
        val salt = ByteArray(BACKUP_SALT_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val iv = ByteArray(BACKUP_IV_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, backupKey(passphrase, salt), GCMParameterSpec(128, iv))
            }
            java.io.DataOutputStream(encrypted.outputStream().buffered()).use { output ->
                output.write(ENCRYPTED_BACKUP_MAGIC.toByteArray(Charsets.US_ASCII))
                output.writeByte(salt.size)
                output.write(salt)
                output.writeByte(iv.size)
                output.write(iv)
                CipherOutputStream(output, cipher).use { encryptedStream ->
                    plainZip.inputStream().buffered().use { source -> source.copyTo(encryptedStream) }
                }
            }
            return encrypted
        } catch (error: Exception) {
            encrypted.delete()
            throw error
        } finally {
            plainZip.delete()
            temporaryDir.delete()
        }
    }

    /** يفك النسخة المشفرة مؤقتًا ثم يمررها إلى مسار الاستيراد المتحقق الحالي. */
    fun importEncryptedBackup(encryptedFile: File, passphrase: String) {
        require(passphrase.length >= 6) { "weak_passphrase" }
        val ctx = appContext ?: throw IllegalStateException("init() must be called first")
        val temporaryZip = File(ctx.cacheDir, "encrypted_import_${System.currentTimeMillis()}.zip")
        try {
            java.io.DataInputStream(encryptedFile.inputStream().buffered()).use { input ->
                val magic = ByteArray(ENCRYPTED_BACKUP_MAGIC.length)
                input.readFully(magic)
                if (!magic.contentEquals(ENCRYPTED_BACKUP_MAGIC.toByteArray(Charsets.US_ASCII))) {
                    throw IllegalArgumentException("invalid_encrypted_backup")
                }
                val saltLength = input.readUnsignedByte()
                if (saltLength !in 12..64) throw IllegalArgumentException("invalid_encrypted_backup")
                val salt = ByteArray(saltLength).also(input::readFully)
                val ivLength = input.readUnsignedByte()
                if (ivLength !in 12..32) throw IllegalArgumentException("invalid_encrypted_backup")
                val iv = ByteArray(ivLength).also(input::readFully)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, backupKey(passphrase, salt), GCMParameterSpec(128, iv))
                }
                CipherInputStream(input, cipher).use { decrypted ->
                    temporaryZip.outputStream().buffered().use { output -> decrypted.copyTo(output) }
                }
            }
            importBackup(temporaryZip)
        } finally {
            temporaryZip.delete()
        }
    }

    private fun backupKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, BACKUP_KDF_ITERATIONS, 256)
        return try {
            val material = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(material, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    data class ContentBackupResult(
        val groupsImported: Int,
        val itemsImported: Int,
        val attachmentsImported: Int,
        val safetyBackup: File
    )

    private data class ContentBackupAsset(
        val groupId: String,
        val itemId: String,
        val kind: String,
        val archivePath: String
    )

    private data class ContentBackupManifest(
        val format: String = "masahhisabat-content",
        val version: Int = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val groups: Int = 0,
        val items: Int = 0,
        val assets: List<ContentBackupAsset> = emptyList()
    )

    /**
     * يصدر المجموعات والمستندات فقط في حزمة مستقلة قابلة للمشاركة.
     * تُحفظ الصور داخل الحزمة بمسارات نسبية حتى لا تتسرب مسارات الجهاز أو تعتمد الاستعادة عليها.
     */
    fun exportContentData(outDir: File): File {
        outDir.mkdirs()
        val zipFile = File(outDir, "masah_groups_${System.currentTimeMillis()}.zip")
        val assetEntries = mutableListOf<ContentBackupAsset>()
        val groupsSnapshot = groups()
        val itemSnapshots = groupsSnapshot.associate { group -> group.id to items(group.id) }

        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
            fun putText(path: String, content: String) {
                zos.putNextEntry(java.util.zip.ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            fun putFile(path: String, source: File) {
                zos.putNextEntry(java.util.zip.ZipEntry(path))
                source.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            putText("groups.json", gson.toJson(groupsSnapshot))
            groupsSnapshot.forEach { group ->
                val exportedItems = itemSnapshots[group.id].orEmpty().map { item ->
                    fun archiveAttachment(path: String?, kind: String): String? {
                        if (path.isNullOrBlank()) return null
                        val source = File(path)
                        if (!source.isFile || source.length() <= 0L) return null
                        val extension = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".bin"
                        val archivePath = "assets/${group.id}/${item.id}/$kind$extension"
                        if (assetEntries.none { it.archivePath == archivePath }) {
                            assetEntries += ContentBackupAsset(group.id, item.id, kind, archivePath)
                            putFile(archivePath, source)
                        }
                        return archivePath
                    }
                    item.copy(
                        imagePath = archiveAttachment(item.imagePath, "image"),
                        processedPath = archiveAttachment(item.processedPath, "processed")
                    )
                }
                putText("invoices/${group.id}/items.json", gson.toJson(exportedItems))
            }
            putText(
                "content_manifest.json",
                gson.toJson(
                    ContentBackupManifest(
                        groups = groupsSnapshot.size,
                        items = itemSnapshots.values.sumOf { it.size },
                        assets = assetEntries.toList()
                    )
                )
            )
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
        // النسخة الكاملة القديمة تستبدل البيانات بعد التحقق؛ تُستخدم فقط من خيار النسخة الكاملة.
        val valid = java.util.zip.ZipFile(zipFile).use { zf ->
            val names = zf.entries().toList().map { it.name }.toSet()
            names.contains("groups.json") && names.contains("users.json")
        }
        if (!valid) throw IllegalArgumentException("invalid")
        val tempDir = File(dataDir().parentFile, "import_tmp").also { it.deleteRecursively(); it.mkdirs() }
        try {
            java.util.zip.ZipFile(zipFile).use { zf ->
                zf.entries().toList().forEach { entry ->
                    val out = safeZipOutput(tempDir, entry.name)
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        zf.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
            createSafetyBackup()
            dataDir().deleteRecursively()
            tempDir.copyRecursively(dataDir(), overwrite = true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * يستورد حزمة المجموعات والمستندات بالدمج حسب المعرّف، ولا يحذف أي بيانات محلية.
     * تُنسخ المرفقات إلى مجلد الصور المحلي وتُعاد كتابة مساراتها قبل حفظ العناصر.
     */
    fun importContentBackup(zipFile: File): ContentBackupResult {
        val tempDir = File(dataDir().parentFile, "content_import_tmp").also { it.deleteRecursively(); it.mkdirs() }
        try {
            java.util.zip.ZipFile(zipFile).use { zf ->
                val entries = zf.entries().toList()
                val names = entries.map { it.name }.toSet()
                if (!names.contains("groups.json") || !names.contains("content_manifest.json")) {
                    throw IllegalArgumentException("invalid_content_backup")
                }
                if (entries.any { !isSafeZipEntry(it.name) }) throw IllegalArgumentException("invalid_content_backup")
                var extractedBytes = 0L
                entries.forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val out = safeZipOutput(tempDir, entry.name)
                    out.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        out.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                extractedBytes += read
                                if (extractedBytes > 512L * 1024L * 1024L) {
                                    throw IllegalArgumentException("content_backup_too_large")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }

            val manifest = gson.fromJson(File(tempDir, "content_manifest.json").readText(), ContentBackupManifest::class.java)
            if (manifest.format != "masahhisabat-content" || manifest.version != 1) {
                throw IllegalArgumentException("unsupported_content_backup")
            }
            val manifestAssets = manifest.assets.orEmpty()
            if (manifestAssets.any {
                    !isSafeZipEntry(it.archivePath) ||
                        !it.archivePath.startsWith("assets/") ||
                        !isSafePathPart(it.groupId) ||
                        !isSafePathPart(it.itemId) ||
                        !isSafePathPart(it.kind)
                }) {
                throw IllegalArgumentException("invalid_content_backup")
            }
            val incomingGroups: List<Group> = gson.fromJson(
                File(tempDir, "groups.json").readText(),
                object : TypeToken<List<Group>>() {}.type
            ) ?: emptyList()
            if (incomingGroups.any { !isSafePathPart(it.id) || it.name.isBlank() }) {
                throw IllegalArgumentException("invalid_content_backup")
            }
            val incomingById = incomingGroups.associateBy { it.id }
            val existingGroups = groups().associateBy { it.id }.toMutableMap()
            val safetyBackup = createSafetyBackup()
            incomingGroups.forEach { group -> existingGroups[group.id] = group }
            saveList("groups.json", existingGroups.values.sortedByDescending { it.createdAt })

            val assetsByKey = manifestAssets.associateBy { "${it.groupId}/${it.itemId}/${it.kind}" }
            var importedItems = 0
            var importedAttachments = 0
            incomingGroups.forEach { group ->
                val itemFile = File(tempDir, "invoices/${group.id}/items.json")
                if (!itemFile.isFile) return@forEach
                val incomingItems: List<InvoiceItem> = gson.fromJson(
                    itemFile.readText(),
                    object : TypeToken<List<InvoiceItem>>() {}.type
                ) ?: emptyList()
                val remapped = incomingItems.map { item ->
                    fun restoreAttachment(kind: String, path: String?): String? {
                        if (path.isNullOrBlank()) return null
                        val asset = assetsByKey["${group.id}/${item.id}/$kind"] ?: return null
                        val source = File(tempDir, asset.archivePath)
                        if (!source.isFile) return null
                        val restored = persistImportedAttachment(source, group.id, item.id, kind)
                        if (restored != null) importedAttachments++
                        return restored
                    }
                    item.copy(
                        imagePath = restoreAttachment("image", item.imagePath),
                        processedPath = restoreAttachment("processed", item.processedPath)
                    )
                }
                val merged = linkedMapOf<String, InvoiceItem>()
                items(group.id).forEach { merged[it.id] = it }
                remapped.forEach { merged[it.id] = it }
                writeTextAtomically(
                    File(dataDir(), "invoices/${group.id}/items.json"),
                    gson.toJson(merged.values.sortedByDescending { it.createdAt })
                )
                importedItems += remapped.size
            }
            return ContentBackupResult(incomingById.size, importedItems, importedAttachments, safetyBackup)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun isSafePathPart(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' }

    private fun isSafeZipEntry(name: String): Boolean =
        name.isNotBlank() && !name.startsWith("/") &&
            name.split('/').all { isSafePathPart(it) }

    private fun safeZipOutput(root: File, entryName: String): File {
        if (!isSafeZipEntry(entryName)) throw IllegalArgumentException("invalid_content_backup")
        val rootPath = root.canonicalFile.toPath()
        val output = File(root, entryName).canonicalFile
        if (!output.toPath().startsWith(rootPath)) throw IllegalArgumentException("invalid_content_backup")
        return output
    }

    private fun persistImportedAttachment(source: File, groupId: String, itemId: String, kind: String): String? {
        return try {
            val dir = File(dataDir(), "images").also { it.mkdirs() }
            val ext = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".bin"
            val destination = File(dir, "import_${groupId}_${itemId}_$kind$ext".replace(Regex("[^A-Za-z0-9._-]"), "_"))
            source.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
            destination.absolutePath
        } catch (_: Exception) { null }
    }
}
