package com.masahhisabat.app.ui.invoice

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.MotionEvent
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

/**
 * شاشة عرض الصور بالحجم الكامل مع التنقل بالسحب يمينًا ويسارًا.
 * تُفتح من داخل المجموعة عند الضغط على صورة، وتعرض صور المجموعة فقط
 * (بترتيبها الطبيعي) وتبدأ من الصورة المضغوطة.
 */
class ImageViewerActivity : AppCompatActivity() {

    private var groupId: String = ""
    private var images: List<String> = emptyList()
    private var startIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        com.masahhisabat.app.data.AppRepository.initAppContext(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        window.decorView.setBackgroundColor(Color.BLACK)
        window.statusBarColor = Color.BLACK
        if (window.navigationBarColor == Color.TRANSPARENT) window.navigationBarColor = Color.BLACK

        groupId = intent.getStringExtra("group_id") ?: ""
        startIndex = intent.getIntExtra("image_index", 0)

        // جمع مسارات كل الصور في المجموعة بترتيبها (من الأقدم للأحدث)
        images = AppRepository.items(groupId)
            .filter { it.type == "image" && (it.imagePath != null || it.processedPath != null) }
            .map { it.processedPath ?: it.imagePath!! }

        val pager = findViewById<ViewPager2>(R.id.image_pager)
        pager.adapter = ImagesPagerAdapter()
        // السحب للتنقل بين الصور مفعّل، وتتعطل مؤقتًا فقط داخل ZoomableImageView عند التكبير
        pager.isUserInputEnabled = true
        // عند بدء التكبير (أصبعان) نمنع تنقل ViewPager
        (pager.getChildAt(0) as? RecyclerView)?.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                val current = (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(pager.currentItem)
                val zoomImg = (current as? ImageViewHolder)?.img
                return zoomImg?.isZoomed == true
            }
        })
        pager.setPageTransformer { page, position ->
            page.alpha = 1f - kotlin.math.abs(position) * 0.2f
        }

        // البدء من الصورة المضغوطة (عكس الاتجاه لأن ViewPager يبدأ من 0)
        val startPos = if (images.isEmpty()) 0 else (images.size - 1 - startIndex)
        pager.setCurrentItem(startPos, false)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val imgIndex = images.size - 1 - position
                updateCounter(imgIndex, images.size)
            }
        })

        updateCounter(startIndex, images.size)

        findViewById<ImageButton>(R.id.btn_viewer_close).setOnClickListener { finish() }
    }

    private fun updateCounter(index: Int, total: Int) {
        findViewById<TextView>(R.id.viewer_counter)?.apply {
            text = "${index + 1} / $total"
            setTextColor(ThemeHelper.counterText(this.context))
            val bg = getDrawable(ThemeHelper.counterBgRes(this.context))?.mutate()
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
            val imgIndex = images.size - 1 - position
            val path = images.getOrElse(imgIndex) { null }
            // إعادة ضبط التكبير عند تبديل الصورة
            holder.img.resetZoom()
            if (path != null) {
                try {
                    holder.img.setImageBitmap(ImageProcessor.loadBitmap(path, 1600))
                } catch (e: Exception) {
                    holder.img.setImageResource(R.drawable.ic_invoice)
                }
            } else {
                holder.img.setImageResource(R.drawable.ic_invoice)
            }
        }

        override fun getItemCount() = images.size
    }

    private class ImageViewHolder(val img: ZoomableImageView) : RecyclerView.ViewHolder(img)
}
