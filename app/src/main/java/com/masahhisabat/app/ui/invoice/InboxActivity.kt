package com.masahhisabat.app.ui.invoice

import android.content.Intent
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.Group
import com.masahhisabat.app.data.InvoiceItem
import com.masahhisabat.app.data.InvoiceWorkflow
import com.masahhisabat.app.databinding.ActivityInvoiceInboxBinding
import com.masahhisabat.app.databinding.ItemInboxInvoiceBinding
import com.masahhisabat.app.ui.ThemeHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** شاشة موحدة تتيح فرز الفواتير الجديدة وتحديث حالة متابعتها محليًا. */
class InboxActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInvoiceInboxBinding
    private lateinit var adapter: InboxAdapter
    private var selectedStatus: String = InvoiceWorkflow.NEW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppRepository.initAppContext(this)
        binding = ActivityInvoiceInboxBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyTheme()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.list.layoutManager = LinearLayoutManager(this)
        adapter = InboxAdapter(::openGroup, ::chooseStatus)
        binding.list.adapter = adapter
        binding.refresh.setOnClickListener { refresh(animated = true, fromUser = true) }
        bindFilters()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) refresh()
    }

    private fun bindFilters() {
        listOf(
            binding.filterNew to InvoiceWorkflow.NEW,
            binding.filterReview to InvoiceWorkflow.IN_REVIEW,
            binding.filterCompleted to InvoiceWorkflow.COMPLETED,
            binding.filterPaid to InvoiceWorkflow.PAID
        ).forEach { (button, status) -> button.setOnClickListener {
            if (selectedStatus != status) {
                selectedStatus = status
                refresh(animated = true)
            }
        } }
    }

    private fun refresh(animated: Boolean = false, fromUser: Boolean = false) {
        val items = AppRepository.invoiceWorkItems().filter { (_, item) -> item.status == selectedStatus }
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.empty.text = InvoiceCardAccessibilityPolicy.emptyMessage(InvoiceWorkflow.label(selectedStatus))
        binding.summary.text = when (selectedStatus) {
            InvoiceWorkflow.NEW -> "${items.size} فواتير جديدة بانتظار الفرز أو المراجعة"
            InvoiceWorkflow.IN_REVIEW -> "${items.size} فواتير تحتاج قرارًا أو متابعة"
            InvoiceWorkflow.COMPLETED -> "${items.size} فواتير مكتملة بانتظار الإقفال"
            else -> "${items.size} فواتير مدفوعة محفوظة للرجوع إليها"
        }
        adapter.submit(items)
        updateFilterStyles()
        if (animated && motionEnabled()) animateRefresh(items.isEmpty(), fromUser)
    }

    /** حركات قصيرة لا تعمل إذا عطّل المستخدم حركة النظام من إعدادات الوصول. */
    private fun motionEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    private fun animateRefresh(isEmpty: Boolean, fromUser: Boolean) {
        if (fromUser) {
            binding.refresh.isEnabled = false
            binding.refresh.rotation = 0f
            binding.refresh.animate()
                .rotationBy(360f)
                .setDuration(420L)
                .setInterpolator(LinearInterpolator())
                .withEndAction {
                    binding.refresh.rotation = 0f
                    binding.refresh.isEnabled = true
                }
                .start()
        }

        binding.summary.animate().cancel()
        binding.summary.alpha = 0.55f
        binding.summary.scaleX = 0.985f
        binding.summary.scaleY = 0.985f
        binding.summary.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start()

        val target = if (isEmpty) binding.empty else binding.list
        target.animate().cancel()
        target.alpha = 0f
        target.translationY = 12f
        target.animate().alpha(1f).translationY(0f).setDuration(220L).start()
    }

    private fun updateFilterStyles() {
        listOf(
            binding.filterNew to InvoiceWorkflow.NEW,
            binding.filterReview to InvoiceWorkflow.IN_REVIEW,
            binding.filterCompleted to InvoiceWorkflow.COMPLETED,
            binding.filterPaid to InvoiceWorkflow.PAID
        ).forEach { (button, status) ->
            val active = status == selectedStatus
            button.backgroundTintList = ColorStateList.valueOf(if (active) ThemeHelper.accent(this) else ThemeHelper.surfaceHigh(this))
            button.setTextColor(if (active) android.graphics.Color.WHITE else ThemeHelper.text(this))
        }
    }

    private fun openGroup(group: Group) {
        startActivity(Intent(this, GroupActivity::class.java).putExtra("group_id", group.id))
    }

    private fun chooseStatus(group: Group, item: InvoiceItem) {
        val labels = InvoiceWorkflow.statuses.map(InvoiceWorkflow::label).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("تحديث حالة المتابعة")
            .setSingleChoiceItems(labels, InvoiceWorkflow.statuses.indexOf(item.status).coerceAtLeast(0)) { dialog, which ->
                InvoiceWorkflow.updateStatus(this, group.id, item.id, InvoiceWorkflow.statuses[which])
                AppRepository.logActivity(com.masahhisabat.app.data.ActivityEntry("local", "حدّث حالة فاتورة لدى ${group.name}"))
                dialog.dismiss()
                refresh(animated = true)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun applyTheme() {
        binding.root.setBackgroundResource(ThemeHelper.backgroundRes())
        binding.toolbar.setBackgroundColor(ThemeHelper.surface(this))
        binding.title.setTextColor(ThemeHelper.text(this))
        binding.summary.setTextColor(ThemeHelper.textSecondary(this))
        binding.empty.setTextColor(ThemeHelper.textSecondary(this))
    }

    private class InboxAdapter(
        private val onOpen: (Group) -> Unit,
        private val onStatus: (Group, InvoiceItem) -> Unit
    ) : RecyclerView.Adapter<InboxAdapter.Holder>() {
        private var rows: List<Pair<Group, InvoiceItem>> = emptyList()
        fun submit(newRows: List<Pair<Group, InvoiceItem>>) {
            val nextRows = newRows.toList()
            val previousRows = rows
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previousRows.size
                override fun getNewListSize() = nextRows.size
                override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                    previousRows[oldPosition].first.id == nextRows[newPosition].first.id &&
                        previousRows[oldPosition].second.id == nextRows[newPosition].second.id
                override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                    previousRows[oldPosition] == nextRows[newPosition]
            })
            rows = nextRows
            diff.dispatchUpdatesTo(this)
        }
        class Holder(val binding: ItemInboxInvoiceBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemInboxInvoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (group, item) = rows[position]
            val context = holder.itemView.context
            val title = item.storeName ?: item.text ?: "فاتورة بدون عنوان"
            holder.binding.title.text = title
            val date = item.date ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(item.createdAt))
            val totalLabel = item.total?.let { "$it ${item.currency.orEmpty()}".trim() }
            val total = totalLabel?.let { " • $it" }.orEmpty()
            val statusLabel = InvoiceWorkflow.label(item.status)
            holder.binding.detail.text = "${group.name} • $date$total"
            holder.binding.status.text = statusLabel
            holder.binding.root.contentDescription = InvoiceCardAccessibilityPolicy.cardDescription(
                invoiceTitle = title,
                groupName = group.name,
                date = date,
                total = totalLabel,
                statusLabel = statusLabel
            )
            holder.binding.status.contentDescription = InvoiceCardAccessibilityPolicy.statusDescription(title, statusLabel)
            holder.binding.status.backgroundTintList = ColorStateList.valueOf(ThemeHelper.surfaceHigh(context))
            holder.binding.status.setTextColor(ThemeHelper.accent(context))
            holder.binding.root.setOnClickListener { onOpen(group) }
            holder.binding.status.setOnClickListener { onStatus(group, item) }
        }
        override fun getItemCount() = rows.size
    }
}
