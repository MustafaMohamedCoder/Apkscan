package com.masahhisabat.app.ui.invoice

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
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
        ).forEach { (button, status) -> button.setOnClickListener { selectedStatus = status; refresh() } }
    }

    private fun refresh() {
        val items = AppRepository.invoiceWorkItems().filter { (_, item) -> item.status == selectedStatus }
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.summary.text = when (selectedStatus) {
            InvoiceWorkflow.NEW -> "${items.size} فواتير جديدة بانتظار الفرز أو المراجعة"
            InvoiceWorkflow.IN_REVIEW -> "${items.size} فواتير تحتاج قرارًا أو متابعة"
            InvoiceWorkflow.COMPLETED -> "${items.size} فواتير مكتملة بانتظار الإقفال"
            else -> "${items.size} فواتير مدفوعة محفوظة للرجوع إليها"
        }
        adapter.submit(items)
        updateFilterStyles()
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
                refresh()
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
        fun submit(newRows: List<Pair<Group, InvoiceItem>>) { rows = newRows; notifyDataSetChanged() }
        class Holder(val binding: ItemInboxInvoiceBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemInboxInvoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (group, item) = rows[position]
            val context = holder.itemView.context
            holder.binding.title.text = item.storeName ?: item.text ?: "فاتورة بدون عنوان"
            val date = item.date ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(item.createdAt))
            val total = item.total?.let { " • $it ${item.currency ?: ""}" } ?: ""
            holder.binding.detail.text = "${group.name} • $date$total"
            holder.binding.status.text = InvoiceWorkflow.label(item.status)
            holder.binding.status.backgroundTintList = ColorStateList.valueOf(ThemeHelper.surfaceHigh(context))
            holder.binding.status.setTextColor(ThemeHelper.accent(context))
            holder.binding.root.setOnClickListener { onOpen(group) }
            holder.binding.status.setOnClickListener { onStatus(group, item) }
        }
        override fun getItemCount() = rows.size
    }
}
