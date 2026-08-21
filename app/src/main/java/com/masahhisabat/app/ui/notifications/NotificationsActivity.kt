package com.masahhisabat.app.ui.notifications

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.NotificationEvent
import com.masahhisabat.app.ui.ThemeHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** مركز واحد للنشاط الجديد: مجموعات ورسائل مباشرة ورسائل داخل المجموعات. */
class NotificationsActivity : AppCompatActivity() {
    private lateinit var adapter: NotificationAdapter
    private lateinit var unreadLabel: TextView
    private lateinit var markAllRead: Button
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        AppRepository.initAppContext(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 12, 18, 18); setBackgroundColor(getColor(com.masahhisabat.app.R.color.day_background)) }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(ImageButton(this).apply { setImageResource(R.drawable.ic_arrow_back); background = null; contentDescription = "رجوع"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(48, 48))
        toolbar.addView(TextView(this).apply { text = "الإشعارات"; textSize = 22f; typeface = resources.getFont(R.font.tajawal_bold); setTextColor(ThemeHelper.text(this@NotificationsActivity)) }, LinearLayout.LayoutParams(0, 56, 1f))
        toolbar.addView(TextView(this).apply { unreadLabel = this; textSize = 12f; setTextColor(ThemeHelper.accent(this@NotificationsActivity)) })
        root.addView(toolbar)
        val summary = TextView(this).apply { text = "آخر المجموعات والرسائل المضافة تظهر هنا تلقائيًا"; textSize = 14f; setTextColor(ThemeHelper.textSecondary(this@NotificationsActivity)); setPadding(8, 4, 8, 14) }
        root.addView(summary)
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@NotificationsActivity); setPadding(2, 4, 2, 12); clipToPadding = false }
        adapter = NotificationAdapter(); list.adapter = adapter; root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val clear = Button(this).apply { markAllRead = this; text = "تمييز الكل كمقروء"; setOnClickListener { AppRepository.markNotificationsRead(); refresh() } }
        root.addView(clear, LinearLayout.LayoutParams(-1, 50))
        setContentView(root); refresh()
    }
    override fun onResume() { super.onResume(); if (::adapter.isInitialized) refresh() }
    private fun refresh() {
        val centerState = NotificationCenterState.fromUnreadCount(AppRepository.unreadNotificationCount())
        unreadLabel.text = centerState.unreadLabel
        markAllRead.isEnabled = centerState.canMarkAllRead
        adapter.submit(AppRepository.notifications())
    }

    private class NotificationAdapter : RecyclerView.Adapter<NotificationHolder>() {
        private var data = emptyList<NotificationEvent>()
        fun submit(items: List<NotificationEvent>) {
            val newData = items.toList()
            val oldData = data
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = oldData.size
                override fun getNewListSize() = newData.size
                override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                    oldData[oldPosition].id == newData[newPosition].id

                override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                    oldData[oldPosition] == newData[newPosition]
            })
            data = newData
            diff.dispatchUpdatesTo(this)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = NotificationHolder(MaterialCardView(parent.context).apply { radius = 20f; setContentPadding(16, 12, 16, 12) })
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: NotificationHolder, position: Int) = holder.bind(data[position])
    }
    private class NotificationHolder(private val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
        fun bind(event: NotificationEvent) {
            val context = card.context
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(context).apply { text = event.title; textSize = 16f; typeface = context.resources.getFont(R.font.tajawal_bold); setTextColor(ThemeHelper.text(context)) })
            box.addView(TextView(context).apply { text = event.body; textSize = 14f; setTextColor(ThemeHelper.textSecondary(context)); setPadding(0, 5, 0, 5) })
            box.addView(TextView(context).apply { text = "${event.actor?.let { "من $it • " } ?: ""}${SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault()).format(Date(event.createdAt))}"; textSize = 11f; setTextColor(if (event.read) ThemeHelper.textSecondary(context) else Color.rgb(35, 160, 85)) })
            card.setCardBackgroundColor(if (event.read) ThemeHelper.surface(context) else ThemeHelper.surfaceHigh(context)); card.removeAllViews(); card.addView(box)
        }
    }
}
