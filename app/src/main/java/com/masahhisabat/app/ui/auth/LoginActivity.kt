package com.masahhisabat.app.ui.auth

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.ActivityEntry
import com.masahhisabat.app.databinding.ActivityLoginBinding
import com.masahhisabat.app.ui.main.MainActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private val notificationRequestCode = 9002

    private val storageSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // تعاد التهيئة بعد الرجوع من إعدادات النظام؛ لا تعتمد بعض الواجهات
        // المعدلة على resultCode لإبلاغ منح الإذن.
        try {
            AppRepository.init(this)
            if (AppRepository.isUsingExternalStorage()) {
                AppRepository.remapTempImagePaths()
            }
        } catch (_: Exception) {
            // تظل شاشة الدخول متاحة حتى إن تعذر الوصول الخارجي على جهاز مخصص.
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), notificationRequestCode)
        }
    }

    private fun requestExternalStoragePermissionIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                // يعالج catch غياب شاشة الإعدادات على الأنظمة المعدلة، دون أن تمنع
                // قيود إظهار الحزم فتح الإعدادات النظامية على Android 11+.
                storageSettingsLauncher.launch(intent)
            }
        } catch (_: Exception) { /* لا نعيق التشغيل */ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppRepository.initAppContext(this)
        super.onCreate(savedInstanceState)
        requestExternalStoragePermissionIfNeeded()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestNotificationPermissionIfNeeded()

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = getColor(android.R.color.transparent)
        applyLoginTheme()

        val remembered = AppRepository.rememberedLogin()
        if (!remembered.isNullOrBlank()) {
            binding.usernameInput.setText(remembered)
            binding.rememberCheck.isChecked = true
            // تذكرني يملأ اسم المستخدم فقط؛ لا يُنشئ جلسة دون التحقق من كلمة المرور.
            // هذا يمنع فتح الأرشيف تلقائيًا إذا أصبح الجهاز متاحًا لشخص آخر.
            binding.passwordInput.requestFocus()
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
                val remainingSeconds = LoginAttemptGuard.remainingSeconds(this)
                if (remainingSeconds > 0L) {
                    binding.errorText.text = getString(R.string.login_retry_later, remainingSeconds)
                    binding.errorText.visibility = View.VISIBLE
                    shakeError()
                    return@setOnClickListener
                }
                val username = binding.usernameInput.text.toString().trim()
                val password = binding.passwordInput.text.toString()
                if (username.isBlank() || password.isBlank()) {
                    shakeError()
                    return@setOnClickListener
                }
                val user = AppRepository.authenticate(username, password)
                if (user == null) {
                    LoginAttemptGuard.recordFailure(this)
                    shakeError()
                    binding.errorText.setText(R.string.login_error)
                    binding.errorText.visibility = View.VISIBLE
                    // يسجل التشخيص حالة الفشل فقط، دون أسماء الحسابات أو أي بيانات اعتماد.
                    try {
                        val sb = StringBuilder()
                        sb.append("LOGIN_FAIL ${System.currentTimeMillis()} username_length=${username.length} ")
                        sb.append("external=${AppRepository.isUsingExternalStorage()}\n")
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
                LoginAttemptGuard.reset(this)
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
        val bg = if (night) {
            androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.login_bg)
        } else {
            androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.login_bg_day)
        }
        binding.loginRoot.background = bg?.mutate()
        binding.usernameInput.setTextColor(com.masahhisabat.app.ui.ThemeHelper.inputText(this))
        binding.usernameInput.setHintTextColor(com.masahhisabat.app.ui.ThemeHelper.inputHint(this))
        binding.passwordInput.setTextColor(com.masahhisabat.app.ui.ThemeHelper.inputText(this))
        binding.passwordInput.setHintTextColor(com.masahhisabat.app.ui.ThemeHelper.inputHint(this))
        binding.rememberCheck.setTextColor(getColor(if (night) R.color.night_text else R.color.day_text))
        binding.loginTitleText.setTextColor(getColor(if (night) R.color.night_text else R.color.day_text))
        binding.labelUsername.setTextColor(getColor(if (night) R.color.night_text else R.color.day_text))
        binding.labelPassword.setTextColor(getColor(if (night) R.color.night_text else R.color.day_text))
        binding.developerText.setTextColor(getColor(if (night) R.color.white else R.color.primary_dark))
        binding.errorText.setTextColor(getColor(R.color.error))
        // تلوين خلفية حقول الإدخال ديناميكيًا (input_fill يتغير مع الوضع)
        val inputBg = androidx.appcompat.content.res.AppCompatResources
            .getDrawable(this, R.drawable.input_bg)?.mutate()
        (inputBg as? android.graphics.drawable.GradientDrawable)?.apply {
            setColor(com.masahhisabat.app.ui.ThemeHelper.inputFill(this@LoginActivity))
            setStroke(1, com.masahhisabat.app.ui.ThemeHelper.inputStroke(this@LoginActivity))
        }
        binding.usernameInput.background = inputBg
        binding.passwordInput.background = inputBg
    }

    private fun shakeError() {
        val v = getSystemService(Vibrator::class.java)
        v?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        binding.loginForm.animate()
            .translationX(0f)
            .setDuration(60)
            .withEndAction {
                binding.loginForm.animate().translationX(20f).setDuration(60).withEndAction {
                    binding.loginForm.animate().translationX(-20f).setDuration(60).withEndAction {
                        binding.loginForm.animate().translationX(0f).setDuration(60).start()
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
        val activeUser = activeUser(ctx)
        if (activeUser != null) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("role", activeUser.role.name)
                .apply()
            return activeUser.role
        }
        return com.masahhisabat.app.data.Role.VIEWER
    }
    fun currentUser(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("username", null)
    /** يعيد المستخدم المفعّل فقط؛ لا تعد بيانات الجلسة وحدها إثباتًا لصلاحية الحساب. */
    fun activeUser(ctx: Context): com.masahhisabat.app.data.User? {
        val username = currentUser(ctx) ?: return null
        return AppRepository.users().firstOrNull { user ->
            user.enabled && AppRepository.normalizeUsername(user.username) == AppRepository.normalizeUsername(username)
        }
    }
    fun lock(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("app_locked", true).apply()
    fun unlock(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("app_locked", false).apply()
    fun requiresUnlock(ctx: Context): Boolean =
        AppRepository.hasAppLock() && currentUser(ctx) != null &&
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("app_locked", false)
    fun logout(ctx: Context) = clear(ctx)
    fun clear(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}

/** تهدئة محلية خفيفة تقلل تخمين كلمات المرور دون حجب الحسابات أو الحاجة إلى خادم. */
private object LoginAttemptGuard {
    private const val PREFS = "login_attempt_guard"
    private const val KEY_FAILURES = "failures"
    private const val KEY_LOCK_UNTIL = "lock_until"
    private const val MAX_FAILURES = 5
    private const val LOCK_DURATION_MS = 90_000L

    fun remainingSeconds(ctx: Context): Long {
        val until = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LOCK_UNTIL, 0L)
        return ((until - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1_000L
    }

    fun recordFailure(ctx: Context) {
        val preferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val failures = preferences.getInt(KEY_FAILURES, 0) + 1
        val editor = preferences.edit()
        if (failures >= MAX_FAILURES) {
            editor.putInt(KEY_FAILURES, 0)
                .putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + LOCK_DURATION_MS)
        } else {
            editor.putInt(KEY_FAILURES, failures)
        }
        editor.apply()
    }

    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
