package com.masahhisabat.app.ui.auth

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.ui.ThemeHelper

/** شاشة مستقلة تمنع الوصول للواجهة عند تفعيل رمز قفل التطبيق. */
class LockActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (28 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(ThemeHelper.bg(this@LockActivity))
        }
        val title = TextView(this).apply {
            text = "التطبيق مقفل"
            textSize = 26f
            gravity = Gravity.CENTER
            typeface = resources.getFont(com.masahhisabat.app.R.font.tajawal_bold)
            setTextColor(ThemeHelper.text(this@LockActivity))
        }
        val hint = TextView(this).apply {
            text = "أدخل رمز PIN للمتابعة"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 18)
            setTextColor(ThemeHelper.textSecondary(this@LockActivity))
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setHint("رمز PIN")
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(ThemeHelper.text(this@LockActivity))
        }
        val action = MaterialButton(this).apply {
            text = "فتح التطبيق"
            setOnClickListener {
                if (AppRepository.verifyAppLockPin(input.text.toString())) {
                    SessionStore.unlock(this@LockActivity)
                    finish()
                } else {
                    input.error = "رمز PIN غير صحيح"
                    input.selectAll()
                }
            }
        }
        content.addView(title); content.addView(hint); content.addView(input)
        content.addView(action, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 })
        setContentView(content)
        setFinishOnTouchOutside(false)
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { moveTaskToBack(true) }
}
