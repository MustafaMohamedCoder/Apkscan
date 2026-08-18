package com.masahhisabat.app.data

/**
 * تشفير كلمات المرور:
 * - v1: SHA-256 مع الملح (غير قابل للفك) — كلمات مرور المستخدمين القدامى.
 * - v2: تشفير Symmetric قابل للفك (AES-like XOR بمفتاح) — كلمات المرور الجديدة
 *   تُخزن بصيغة "v2:<base64>" لتسهيل عرضها لاحقًا كما هي.
 */
object HashUtil {

    private const val KEY = "masah_hisabat_key_v2"

    /** SHA-256 القديم (غير قابل للفك) — يُستخدم فقط لمقارنة الدخول للمستخدمين القدامى */
    fun hash(password: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val salt = "masah_hisabat_salt_v1".toByteArray(Charsets.UTF_8)
        md.update(salt)
        val bytes = md.digest(password.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** تشفير قابل للفك لكلمة المرور (الإصدار الجديد) */
    fun encodePlain(password: String): String {
        val bytes = password.toByteArray(Charsets.UTF_8)
        val key = KEY.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) out[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
        return "v2:" + android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    /** فك كلمة المرور المشفرة (null إذا كانت بصيغة قديمة غير قابلة للفك) */
    fun decodePlain(stored: String): String? {
        return try {
            if (!stored.startsWith("v2:")) return null
            val raw = android.util.Base64.decode(stored.removePrefix("v2:"), android.util.Base64.NO_WRAP)
            val key = KEY.toByteArray(Charsets.UTF_8)
            val out = ByteArray(raw.size)
            for (i in raw.indices) out[i] = (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
            String(out, Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    /** هل كلمة المرور المخزنة من النوع القابل للفك؟ */
    fun isDecodable(stored: String): Boolean = stored.startsWith("v2:")
}
