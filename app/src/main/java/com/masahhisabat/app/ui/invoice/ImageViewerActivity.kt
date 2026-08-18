package com.masahhisabat.app.ui.invoice

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.image.ImageProcessor
import com.masahhisabat.app.ui.ThemeHelper
import android.graphics.drawable.GradientDrawable
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
                updateCounter(position, images.size)
            }
        })

        updateCounter(startPos, images.size)

        findViewById<ImageButton>(R.id.btn_viewer_close).setOnClickListener { finish() }
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
