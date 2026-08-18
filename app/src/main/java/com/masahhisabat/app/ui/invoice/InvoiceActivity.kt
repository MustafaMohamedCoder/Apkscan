package com.masahhisabat.app.ui.invoice

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.ActivityEntry
import com.masahhisabat.app.data.InvoiceExtractor
import com.masahhisabat.app.data.InvoiceItem
import com.masahhisabat.app.data.currentInvoiceName
import com.masahhisabat.app.data.generateId
import com.masahhisabat.app.image.ImageProcessor
import com.masahhisabat.app.image.ProcessMode
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.auth.SessionStore

/**
 * شاشة إنشاء فاتورة جديدة أو إضافة صورة إلى فاتورة قائمة:
 * - استخراج ذكي للبيانات مع إمكانية تعديلها قبل الحفظ
 * - اقتراح اسم افتراضي ذكي
 */
class InvoiceActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_ACTION = "action"
        const val EXTRA_GROUP_ID = "group_id"
        const val ACTION_CREATE = "create"
        const val ACTION_ADD = "add"
    }

    private lateinit var imgPreview: ImageView
    private lateinit var loadingPanel: LinearLayout
    private lateinit var extractPanel: LinearLayout
    private lateinit var saveBtn: MaterialButton

    private var imagePath: String = ""
    private var action: String = ACTION_CREATE
    private var extracted = InvoiceExtractor.Extracted()
    private lateinit var etName: EditText
    private lateinit var etStore: EditText
    private lateinit var etDate: EditText
    private lateinit var etTotal: EditText
    private lateinit var etCurrency: EditText
    private lateinit var etItems: EditText
    private var currentGroupId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        com.masahhisabat.app.data.AppRepository.initAppContext(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice)
        applyTheme()

        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: ""
        action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_CREATE
        currentGroupId = intent.getStringExtra(EXTRA_GROUP_ID)

        imgPreview = findViewById(R.id.img_preview)
        loadingPanel = findViewById(R.id.loading_panel)
        extractPanel = findViewById(R.id.card_preview)
        saveBtn = findViewById(R.id.btn_save)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        if (imagePath.isBlank()) {
            Toast.makeText(this, "الصورة غير متاحة", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val bmp = ImageProcessor.loadBitmap(imagePath, 1024)
        imgPreview.setImageBitmap(bmp)

        val lastMode = try { ProcessMode.valueOf(AppRepository.lastProcessMode()) } catch (e: Exception) { ProcessMode.AUTO }
        ImageProcessor.process(lastMode, bmp, object : ImageProcessor.Callback {
            override fun onDone(processed: android.graphics.Bitmap) {
                imgPreview.setImageBitmap(processed)
            }
            override fun onError() {}
        })

        etName = findViewById(R.id.et_name)
        etStore = findViewById(R.id.et_store)
        etDate = findViewById(R.id.et_date)
        etTotal = findViewById(R.id.et_total)
        etCurrency = findViewById(R.id.et_currency)
        etItems = findViewById(R.id.et_items)

        val suggestedName = currentGroupId?.let { AppRepository.lastInvoiceName() } ?: currentInvoiceName()
        etName.setText(suggestedName)

        // عرض الفواتير/المجموعات القائمة إذا كان الإجراء إضافة إلى فاتورة قائمة
        val groupSelector = findViewById<LinearLayout>(R.id.group_selector)
        if (action == ACTION_ADD) {
            groupSelector.visibility = View.VISIBLE
            populateGroupSelector()
        } else {
            groupSelector.visibility = View.GONE
        }

        extractPanel.visibility = View.GONE
        saveBtn.visibility = View.GONE

        // الاستخراج الذكي
        loadingPanel.visibility = View.VISIBLE
        val imageBmp = ImageProcessor.loadBitmap(imagePath, 2048)
        Thread {
            val result = InvoiceExtractor.extract(imageBmp)
            runOnUiThread {
                loadingPanel.visibility = View.GONE
                if (result.rawText.isBlank()) {
                    Toast.makeText(this, R.string.extract_failed, Toast.LENGTH_LONG).show()
                }
                extracted = result
                etStore.setText(result.storeName ?: "")
                etDate.setText(result.date ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()))
                etTotal.setText(result.total ?: "")
                etCurrency.setText(result.currency ?: "ر.س")
                etItems.setText(result.itemsText ?: "")
                extractPanel.visibility = View.VISIBLE
                saveBtn.visibility = View.VISIBLE
            }
        }.start()

        saveBtn.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(this, "أدخل اسم الفاتورة", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppRepository.setLastInvoiceName(name)

            val item = InvoiceItem(
                type = "image",
                imagePath = com.masahhisabat.app.data.AppRepository.persistAppImage(imagePath) ?: imagePath,
                processedPath = null,
                storeName = etStore.text.toString().trim().ifBlank { null },
                date = etDate.text.toString().trim().ifBlank { null },
                total = etTotal.text.toString().trim().ifBlank { null },
                currency = etCurrency.text.toString().trim().ifBlank { null },
                itemsText = etItems.text.toString().trim().ifBlank { null }
            )

            val groupId = if (action == ACTION_ADD) currentGroupId!!
            else {
                val g = com.masahhisabat.app.data.Group(name = name)
                AppRepository.addGroup(g)
                g.id
            }

            AppRepository.addItem(groupId, item)
            val user = SessionStore.currentUser(this) ?: "?"
            AppRepository.logActivity(ActivityEntry(user, getString(R.string.log_create_invoice, user, name)))
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                ?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            Toast.makeText(this, R.string.success, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun populateGroupSelector() {
        val groups = AppRepository.groups()
        val container = findViewById<LinearLayout>(R.id.group_selector_container)
        if (groups.isEmpty()) {
            findViewById<TextView>(R.id.no_groups_msg).visibility = View.VISIBLE
            currentGroupId = null
            saveBtn.isEnabled = false
            return
        }
        // اختيار أول مجموعة افتراضياً
        currentGroupId = groups.first().id
        groups.forEach { g ->
            val btn = MaterialButton(this).apply {
                text = g.name
                textSize = 13f
                setPadding(28, 0, 28, 0)
                typeface = resources.getFont(R.font.tajawal_medium)
                cornerRadius = 16
            }
            btn.setOnClickListener {
                currentGroupId = g.id
                for (i in 0 until container.childCount) {
                    val b = container.getChildAt(i) as? MaterialButton ?: continue
                    if (b === btn) {
                        b.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
                        b.setTextColor(getColor(R.color.white))
                    } else {
                        b.backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeHelper.surfaceHigh(this))
                        b.setTextColor(ThemeHelper.text(this))
                    }
                }
            }
            container.addView(btn)
        }
        // تفعيل الأول
        (container.getChildAt(0) as? MaterialButton)?.let {
            it.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
            it.setTextColor(getColor(R.color.white))
        }
    }

    private fun applyTheme() {
        window.decorView.setBackgroundColor(ThemeHelper.bg(this))
        val bg = findViewById<View>(R.id.invoice_root)
        bg.setBackgroundColor(ThemeHelper.bg(this))
        val surface = ThemeHelper.surface(this)
        val text = ThemeHelper.text(this)
        val textSec = ThemeHelper.textSecondary(this)
        // بطاقات preview وextract بخلفية السطح (من XML: strokeWidth=0)
        listOf(R.id.card_preview, R.id.extract_card).forEach { id ->
            (findViewById<View>(id) as? com.google.android.material.card.MaterialCardView)?.setCardBackgroundColor(surface)
        }
        // حقول الإدخال من input_bg — نلوّنها ديناميكيًا حسب الوضع
        listOf(R.id.et_name, R.id.et_store, R.id.et_date, R.id.et_total, R.id.et_currency, R.id.et_items).forEach { id ->
            findViewById<EditText>(id)?.apply {
                setTextColor(text)
                setHintTextColor(textSec)
                background?.setTint(ThemeHelper.inputFill(this@InvoiceActivity))
            }
        }
        findViewById<TextView>(R.id.tv_title).setTextColor(text)
        findViewById<TextView>(R.id.tv_extract_title).setTextColor(text)
        findViewById<TextView>(R.id.tv_store).setTextColor(textSec)
        findViewById<TextView>(R.id.tv_date).setTextColor(textSec)
        findViewById<TextView>(R.id.tv_total).setTextColor(textSec)
        findViewById<TextView>(R.id.tv_currency).setTextColor(textSec)
        findViewById<TextView>(R.id.tv_items).setTextColor(textSec)
    }
}
