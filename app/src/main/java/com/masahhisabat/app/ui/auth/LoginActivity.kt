package com.masahhisabat.app.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.ActivityEntry
import com.masahhisabat.app.databinding.ActivityLoginBinding
import com.masahhisabat.app.ui.main.MainActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private val storageRequestCode = 9001

    private fun requestExternalStoragePermissionIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                if (intent.resolveActivity(packageManager) != null) {
                    startActivityForResult(intent, storageRequestCode)
                }
            }
        } catch (_: Exception) { /* لا نعيق التشغيل */ }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == storageRequestCode) {
            // إعادة محاولة إنشاء تهيئة البيانات بعد منح الإذن
            try {
                AppRepository.init(this)
                // إن وُجدت صور محفوظة من نسخة قديمة داخل cacheDir/filesDir،
                // انقلها الآن إلى Documents/MasahHisabat قبل أن يحذفها النظام.
                if (AppRepository.isUsingExternalStorage()) {
                    AppRepository.remapTempImagePaths()
                }
            } catch (_: Exception) { }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppRepository.initAppContext(this)
        super.onCreate(savedInstanceState)
        requestExternalStoragePermissionIfNeeded()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = getColor(android.R.color.transparent)
        applyLoginTheme()

        val remembered = AppRepository.rememberedLogin()
        if (!remembered.isNullOrBlank()) {
            binding.usernameInput.setText(remembered)
            binding.rememberCheck.isChecked = true
            // الدخول التلقائي فورًا دون انتظار الضغط على الزر
            val user = AppRepository.users().find { it.username == remembered && it.enabled }
            if (user != null) {
                AppRepository.logActivity(ActivityEntry(user.username, getString(R.string.log_login, remembered)))
                SessionStore.save(this, user)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return
            }
        }

        binding.passwordToggle.setOnClickListener {
            if (binding.passwordInput.inputType ==
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            ) {
                binding.passwordInput.inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                binding.passwordToggle.setImageResource(R.drawable.ic_visibility_off)
            } else {
                binding.passwordInput.inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                binding.passwordToggle.setImageResource(R.drawable.ic_visibility)
            }
            binding.passwordInput.setSelection(binding.passwordInput.text?.length ?: 0)
        }

        binding.loginButton.setOnClickListener {
            try {
                val username = binding.usernameInput.text.toString().trim()
                val password = binding.passwordInput.text.toString()
                if (username.isBlank() || password.isBlank()) {
                    shakeError()
                    return@setOnClickListener
                }
                val user = AppRepository.authenticate(username, password)
                if (user == null) {
                    shakeError()
                    binding.errorText.visibility = View.VISIBLE
                    // تسجيل تشخيصي داخلي لمعرفة سبب الفشل (ملف login_diag.txt)
                    try {
                        val sb = StringBuilder()
                        sb.append("LOGIN_FAIL ${System.currentTimeMillis()} entered=[$username]\n")
                        val allUsers = try { AppRepository.users() } catch (_: Exception) { emptyList<com.masahhisabat.app.data.User>() }
                        sb.append("users count=${allUsers.size} external=${AppRepository.isUsingExternalStorage()}\n")
                        allUsers.forEach { u ->
                            val decoded = com.masahhisabat.app.data.HashUtil.decodePlain(u.passwordHash)
                            sb.append("- ${u.username} role=${u.role} enabled=${u.enabled} v2=${u.passwordHash.startsWith("v2:")} hash_len=${u.passwordHash.length} decoded_len=${decoded?.length ?: -1}\n")
                        }
                        java.io.File(filesDir, "login_diag.txt").appendText(sb.toString() + "---\n")
                    } catch (_: Exception) { }
                    Toast.makeText(this, R.string.login_error, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (binding.rememberCheck.isChecked) {
                    AppRepository.rememberLogin(username)
                } else {
                    AppRepository.clearRemember()
                }
                AppRepository.logActivity(ActivityEntry(user.username, getString(R.string.log_login, username)))
                SessionStore.save(this, user)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } catch (e: Exception) {
                try {
                    // تسجيل سبب الفشل في ملف داخلي للفحص
                    val sw = java.io.StringWriter()
                    e.printStackTrace(java.io.PrintWriter(sw))
                    val file = java.io.File(filesDir, "crash_log.txt")
                    file.writeText(
                        "LOGIN_FAIL ${System.currentTimeMillis()}: ${e.message}\n$sw\n---\n" +
                            (if (file.exists()) file.readText().take(50_000) else "")
                    )
                    Toast.makeText(this, "حدث خطأ: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "حدث خطأ غير متوقع", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** تطبيق ألوان موحدة على شاشة تسجيل الدخول حسب الوضع الليلي/النهاري */
    private fun applyLoginTheme() {
        val night = com.masahhisabat.app.ui.ThemeHelper.isNight(this)
        // خلفية الشاشة: متدرج Teal داكن في الليلي، فاتح هادئ في النهاري
        val bg = if (night) getDrawable(R.drawable.login_bg) else getDrawable(R.drawable.login_bg_day)
        window.decorView.background = bg?.mutate()
        window.decorView.backgroundTintMode = android.graphics.PorterDuff.Mode.SRC
        binding.loginCard.setCardBackgroundColor(
            if (night) getColor(R.color.night_surface) else getColor(R.color.day_surface)
        )
        binding.usernameInput.setTextColor(com.masahhisabat.app.ui.ThemeHelper.inputText(this))
        binding.usernameInput.setHintTextColor(com.masahhisabat.app.ui.ThemeHelper.inputHint(this))
        binding.passwordInput.setTextColor(com.masahhisabat.app.ui.ThemeHelper.inputText(this))
        binding.passwordInput.setHintTextColor(com.masahhisabat.app.ui.ThemeHelper.inputHint(this))
        binding.rememberCheck.setTextColor(getColor(if (night) R.color.night_text else R.color.day_text))
        binding.loginTitleText.setTextColor(getColor(if (night) R.color.night_text else R.color.day_text))
        binding.labelUsername.setTextColor(getColor(if (night) R.color.night_text else R.color.day_text))
        binding.labelPassword.setTextColor(getColor(if (night) R.color.night_text else R.color.day_text))
        // تلوين خلفية حقول الإدخال ديناميكيًا (input_fill يتغير مع الوضع)
        val inputBg = getDrawable(R.drawable.input_bg)?.mutate()
        (inputBg as? android.graphics.drawable.GradientDrawable)?.apply {
            setColor(com.masahhisabat.app.ui.ThemeHelper.inputFill(this@LoginActivity))
            setStroke(1, com.masahhisabat.app.ui.ThemeHelper.inputStroke(this@LoginActivity))
        }
        binding.usernameInput.background = inputBg
        binding.passwordInput.background = inputBg
    }

    private fun shakeError() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        v?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        binding.loginCard.animate()
            .translationX(0f)
            .setDuration(60)
            .withEndAction {
                binding.loginCard.animate().translationX(20f).setDuration(60).withEndAction {
                    binding.loginCard.animate().translationX(-20f).setDuration(60).withEndAction {
                        binding.loginCard.animate().translationX(0f).setDuration(60).start()
                    }.start()
                }.start()
            }.start()
    }
}

/** حفظ جلسة المستخدم الحالي (username + role) محلياً */
object SessionStore {
    private const val PREFS = "session"
    fun save(ctx: Context, user: com.masahhisabat.app.data.User) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("username", user.username)
            .putString("role", user.role.name)
            .putBoolean("app_locked", false)
            .apply()
    }
    fun currentRole(ctx: Context): com.masahhisabat.app.data.Role {
        val r = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("role", "VIEWER") ?: "VIEWER"
        return try { com.masahhisabat.app.data.Role.valueOf(r) } catch (e: Exception) { com.masahhisabat.app.data.Role.VIEWER }
    }
    fun currentUser(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("username", null)
    fun lock(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("app_locked", true).apply()
    fun unlock(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("app_locked", false).apply()
    fun requiresUnlock(ctx: Context): Boolean =
        AppRepository.hasAppLock() && currentUser(ctx) != null &&
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("app_locked", false)
    fun logout(ctx: Context) = clear(ctx)
    fun clear(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}
