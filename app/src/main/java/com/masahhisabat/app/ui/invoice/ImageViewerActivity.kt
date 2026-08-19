package com.masahhisabat.app.ui.invoice

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.OcrHelper
import com.masahhisabat.app.image.ImageProcessor
import com.masahhisabat.app.ui.ThemeHelper
import android.widget.Toast
import java.io.File

/**
 * شاشة عرض الصور بالحجم الكامل مع التنقل بالسحب يمينًا ويسارًا.
 * تُفتح من داخل المجموعة عند الضغط على صورة، وتعرض صور المجموعة فقط
 * (بترتيبها الطبيعي) وتبدأ من الصورة المضغوطة.
 */
class ImageViewerActivity : AppCompatActivity() {

    private var groupId: String = ""
    private var images: List<String> = emptyList()
    private var startIndex: Int = 0
    private var selectedPath: String? = null
    private var currentIndex = 0
    private val extractedTextByPath = mutableMapOf<String, String>()
    private val extractionErrorsByPath = mutableMapOf<String, String>()
    private var isExtracting = false
    private var extractingPath: String? = null

    private lateinit var searchPanel: View
    private lateinit var searchInput: EditText
    private lateinit var searchStatus: TextView
    private lateinit var searchResults: TextView
    private lateinit var searchButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        com.masahhisabat.app.data.AppRepository.initAppContext(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        window.decorView.setBackgroundColor(Color.BLACK)
        window.statusBarColor = Color.BLACK
        if (window.navigationBarColor == Color.TRANSPARENT) window.navigationBarColor = Color.BLACK

        groupId = intent.getStringExtra("group_id") ?: ""
        startIndex = intent.getIntExtra("image_index", 0)
        selectedPath = intent.getStringExtra("image_path")

        // تعتمد أولوية العارض على المسارات التي ضغطها المستخدم في المجموعة؛
        // ويكون الرجوع إلى المستودع فقط للتوافق مع فتحات قديمة.
        val intentPaths = intent.getStringArrayListExtra("image_paths")
            ?.filter { File(it).isFile && File(it).length() > 0L }
            .orEmpty()
        images = intentPaths.ifEmpty {
            AppRepository.items(groupId)
                .filter { it.type == "image" && (it.imagePath != null || it.processedPath != null) }
                .mapNotNull { AppRepository.availableImagePath(it) }
        }.distinct()

        if (images.isEmpty()) {
            Toast.makeText(this, "لا توجد صورة متاحة للعرض", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val pager = findViewById<ViewPager2>(R.id.image_pager)
        pager.adapter = ImagesPagerAdapter()
        // السحب للتنقل بين الصور مفعّل. عند التكبير تتولى الصورة نفسها منع اعتراض الأب للتمرير.
        pager.isUserInputEnabled = true
        pager.setPageTransformer { page, position ->
            page.alpha = 1f - kotlin.math.abs(position) * 0.2f
        }

        // البدء من الصورة التي ضغط عليها المستخدم، ثم استخدام الفهرس القديم كحل احتياطي.
        val startPos = selectedPath?.let { images.indexOf(it) }?.takeIf { it >= 0 }
            ?: startIndex.coerceIn(0, images.lastIndex)
        pager.setCurrentItem(startPos, false)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateCounter(position, images.size)
                if (searchPanel.visibility == View.VISIBLE) updateSearchResult()
            }
        })

        currentIndex = startPos
        updateCounter(startPos, images.size)

        findViewById<ImageButton>(R.id.btn_viewer_close).setOnClickListener { finish() }
        bindImageSearch()
    }

    private fun updateCounter(index: Int, total: Int) {
        findViewById<TextView>(R.id.viewer_counter)?.apply {
            text = "${index + 1} / $total"
            setTextColor(ThemeHelper.counterText(this.context))
            val bg = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(this.context, ThemeHelper.counterBgRes(this.context))?.mutate()
            background = bg
        }
    }

    /**
     * بحث محلي في الصورة المفتوحة فقط. يبقى النص المستخرج في ذاكرة العارض
     * خلال الجلسة ولا يُرفع أو يُحفظ في خدمة خارجية.
     */
    private fun bindImageSearch() {
        searchPanel = findViewById(R.id.image_search_panel)
        searchInput = findViewById(R.id.image_search_input)
        searchStatus = findViewById(R.id.image_search_status)
        searchResults = findViewById(R.id.image_search_results)
        searchButton = findViewById(R.id.btn_extract_and_search)

        findViewById<ImageButton>(R.id.btn_viewer_search).setOnClickListener {
            searchPanel.visibility = View.VISIBLE
            updateSearchResult()
        }
        findViewById<ImageButton>(R.id.btn_close_image_search).setOnClickListener {
            searchInput.clearFocus()
            searchPanel.visibility = View.GONE
        }
        searchButton.setOnClickListener { extractAndSearchCurrentImage() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateSearchResult()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        searchInput.setOnEditorActionListener { _, _, _ ->
            extractAndSearchCurrentImage()
            true
        }
    }

    private fun currentImagePath(): String? = images.getOrNull(currentIndex)

    private fun extractAndSearchCurrentImage() {
        val path = currentImagePath() ?: return
        if (extractedTextByPath.containsKey(path)) {
            updateSearchResult()
            return
        }
        if (isExtracting) return

        isExtracting = true
        extractingPath = path
        extractionErrorsByPath.remove(path)
        searchButton.isEnabled = false
        searchStatus.text = "يتم استخراج النص محلياً من الصورة…"
        searchResults.text = ""
        Thread {
            var extractionError: String? = null
            val extracted = try {
                val bitmap = ImageProcessor.loadBitmap(path, 1800)
                try {
                    OcrHelper.recognize(this@ImageViewerActivity, bitmap)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            } catch (error: Exception) {
                extractionError = error.message?.takeIf { it.isNotBlank() }
                ""
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                isExtracting = false
                extractingPath = null
                searchButton.isEnabled = true
                if (extractionError == null) {
                    extractedTextByPath[path] = extracted
                } else {
                    extractionErrorsByPath[path] = extractionError.orEmpty()
                }
                updateSearchResult()
            }
        }.apply {
            name = "image-ocr-search"
            start()
        }
    }

    private fun updateSearchResult() {
        if (!::searchPanel.isInitialized || searchPanel.visibility != View.VISIBLE) return
        val path = currentImagePath() ?: return
        val extracted = extractedTextByPath[path]
        val extractionError = extractionErrorsByPath[path]
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val extractingCurrentImage = isExtracting && extractingPath == path
        searchButton.isEnabled = !isExtracting
        searchButton.text = when {
            isExtracting -> "جارٍ الاستخراج…"
            extracted == null && extractionError != null -> "إعادة محاولة الاستخراج"
            extracted == null -> "استخراج النص والبحث"
            else -> "تحديث النتائج"
        }

        when {
            extractingCurrentImage -> {
                searchStatus.text = "يتم استخراج النص محلياً من الصورة…"
                searchResults.text = ""
            }
            extractionError != null -> {
                searchStatus.text = "تعذر استخراج النص من هذه الصورة."
                searchResults.text = "تأكد من أن ملف الصورة ما زال متاحاً، ثم أعد المحاولة."
            }
            extracted == null -> {
                searchStatus.text = "ابحث في الصورة ${currentIndex + 1} من ${images.size}: اكتب كلمة ثم اضغط استخراج النص والبحث."
                searchResults.text = "لن تُرسل الصورة أو النص إلى الإنترنت."
            }
            extracted.isBlank() -> {
                searchStatus.text = "لم يتم العثور على نص قابل للقراءة في هذه الصورة."
                searchResults.text = "جرّب صورة أوضح أو استخدم تحسين المستند قبل البحث."
            }
            query.isBlank() -> {
                searchStatus.text = "تم استخراج النص. اكتب كلمة أو عبارة للبحث داخل الصورة."
                searchResults.text = extracted
            }
            else -> showMatches(extracted, query)
        }
    }

    private fun showMatches(extracted: String, query: String) {
        val matchingLines = extracted.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains(query, ignoreCase = true) }
            .toList()
        if (matchingLines.isEmpty()) {
            searchStatus.text = "لا توجد نتيجة مطابقة لـ «$query» في الصورة الحالية."
            searchResults.text = "يمكنك تغيير العبارة أو قراءة النص المستخرج كاملاً بعد مسح كلمة البحث."
            return
        }
        val result = matchingLines.joinToString("\n\n")
        searchStatus.text = "${matchingLines.size} نتيجة مطابقة لـ «$query» داخل النص المستخرج."
        searchResults.text = highlightMatches(result, query)
    }

    private fun highlightMatches(text: String, query: String): SpannableString {
        val result = SpannableString(text)
        var start = 0
        while (true) {
            val index = text.indexOf(query, start, ignoreCase = true)
            if (index < 0) break
            val end = index + query.length
            result.setSpan(BackgroundColorSpan(getColor(R.color.accent)), index, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            result.setSpan(ForegroundColorSpan(Color.BLACK), index, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = end
        }
        return result
    }

    private inner class ImagesPagerAdapter : RecyclerView.Adapter<ImageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
            val img = ZoomableImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return ImageViewHolder(img)
        }

        override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
            val path = images.getOrElse(position) { null }
            // إعادة ضبط التكبير عند تبديل الصورة
            holder.img.resetZoom()
            if (path != null) {
                try {
                    holder.img.setImageBitmap(ImageProcessor.loadBitmap(path, 1600))
                } catch (e: Exception) {
                    holder.img.setImageResource(R.drawable.ic_invoice)
                    Toast.makeText(this@ImageViewerActivity, "تعذر عرض هذه الصورة", Toast.LENGTH_SHORT).show()
                }
            } else {
                holder.img.setImageResource(R.drawable.ic_invoice)
            }
        }

        override fun getItemCount() = images.size
    }

    private class ImageViewHolder(val img: ZoomableImageView) : RecyclerView.ViewHolder(img)
}
