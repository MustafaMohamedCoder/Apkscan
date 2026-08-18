package com.masahhisabat.app.ui.team

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.ActivityEntry
import com.masahhisabat.app.data.Role
import com.masahhisabat.app.data.User
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.auth.SessionStore

/**
 * إدارة الفريق: عرض الأعضاء، إضافة مستخدمين بأدوار، حذف.
 */
class TeamActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        com.masahhisabat.app.data.AppRepository.initAppContext(this)
        super.onCreate(savedInstanceState)
                setContentView(R.layout.activity_team)
        applyTheme()
        // إدارة الفريق للمالك mustafa فقط
        if (SessionStore.currentUser(this) != "mustafa") {
            Toast.makeText(this, "هذه الخاصية لحساب mustafa فقط", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btn_add_member).setOnClickListener { showAddMemberDialog() }

        refreshMembers()
    }

    private fun refreshMembers() {
        val recycler = findViewById<RecyclerView>(R.id.members_list)
        recycler.layoutManager = LinearLayoutManager(this)
        val empty = findViewById<TextView>(R.id.empty_members)
        val users = AppRepository.users()
        empty.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
        recycler.adapter = MembersAdapter(users)
    }

    private fun showAddMemberDialog() {
        val ctx = this
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_member, null)
        val etUsername = view.findViewById<EditText>(R.id.et_username)
        val etPassword = view.findViewById<EditText>(R.id.et_password)
        val spRole = view.findViewById<Spinner>(R.id.sp_role)
        val roles = arrayOf("مشرف", "محرر", "مشاهد")
        spRole.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, roles)

        // تلوين الكتابة أسودًا واضحًا في الحقول
        etUsername.setTextColor(android.graphics.Color.parseColor("#000000"))
        etUsername.setHintTextColor(android.graphics.Color.parseColor("#888888"))
        etPassword.setTextColor(android.graphics.Color.parseColor("#000000"))
        etPassword.setHintTextColor(android.graphics.Color.parseColor("#888888"))

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.add_member)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.save) { d, _ ->
                val username = etUsername.text.toString().trim().lowercase()
                val password = etPassword.text.toString().trim()
                val role = when (spRole.selectedItemPosition) {
                    0 -> Role.SUPERVISOR
                    1 -> Role.EDITOR
                    else -> Role.VIEWER
                }
                if (username.isBlank() || username.contains(" ")) {
                    Toast.makeText(ctx, "اسم المستخدم غير صالح", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (password.length < 3) {
                    Toast.makeText(ctx, "كلمة المرور يجب أن تكون 3 أحرف على الأقل", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (AppRepository.users().any { it.username == username }) {
                    Toast.makeText(ctx, "اسم المستخدم مستخدم بالفعل", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // تشفير قابل للفك حتى يمكن عرض كلمة المرور لاحقًا كما هي
                AppRepository.addUser(User(username, com.masahhisabat.app.data.HashUtil.encodePlain(password), role))
                val me = SessionStore.currentUser(ctx) ?: "?"
                AppRepository.logActivity(ActivityEntry(me, "أضاف $me العضو $username"))
                Toast.makeText(ctx, R.string.success, Toast.LENGTH_SHORT).show()
                refreshMembers()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun applyTheme() {
        window.decorView.setBackgroundColor(ThemeHelper.bg(this))
        findViewById<View>(R.id.team_root).setBackgroundColor(ThemeHelper.bg(this))
        val text = ThemeHelper.text(this)
        findViewById<TextView>(R.id.tv_title).setTextColor(text)
    }

    inner class MembersAdapter(private val users: List<User>) : RecyclerView.Adapter<MembersAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_member, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val user = users[position]
            val ctx = holder.itemView.context
            val card = holder.itemView as MaterialCardView
            card.setCardBackgroundColor(ThemeHelper.surface(ctx))
            card.strokeColor = ThemeHelper.cardStroke(ctx)
            card.strokeWidth = 1
            val text = ThemeHelper.text(ctx)
            val textSec = ThemeHelper.textSecondary(ctx)

            holder.itemView.findViewById<TextView>(R.id.member_name).apply {
                this.text = user.username
                setTextColor(text)
            }
            holder.itemView.findViewById<TextView>(R.id.member_role).apply {
                this.text = roleLabel(user.role)
                setTextColor(textSec)
            }
            holder.itemView.findViewById<TextView>(R.id.member_status).apply {
                this.text = if (user.role == com.masahhisabat.app.data.Role.ADMIN) "مالك" else "نشط"
                setTextColor(getColor(R.color.accent))
            }

            // صف كلمة المرور: عرضها الحالية + نسخ/إظهار + تعديل مباشر بالضغط
            // صف كلمة المرور: عرض/إخفاء بأيقونة العين (بدون نسخ وبدون تعديل منفصل)
            val passwordText = holder.itemView.findViewById<TextView>(R.id.member_password)
            val ivPassword = holder.itemView.findViewById<ImageView>(R.id.iv_copy_password)
            val decoded = com.masahhisabat.app.data.HashUtil.decodePlain(user.passwordHash)
            val hiddenMarker = "••••••••"
            var passwordHidden = true
            if (decoded != null) {
                passwordText.text = "$hiddenMarker (اضغط العين للإظهار)"
                passwordText.setTextColor(textSec)
                ivPassword.visibility = View.VISIBLE
                ivPassword.setColorFilter(textSec)
            } else {
                passwordText.text = "كلمة المرور: غير قابلة للعرض"
                passwordText.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.error))
                ivPassword.visibility = View.GONE
            }
            ivPassword.setOnClickListener {
                if (decoded != null) {
                    passwordHidden = !passwordHidden
                    passwordText.text = if (passwordHidden) "$hiddenMarker (اضغط العين للإظهار)" else "كلمة المرور: $decoded"
                    passwordText.setTextColor(if (passwordHidden) textSec else text)
                }
            }

            val delete = holder.itemView.findViewById<ImageView>(R.id.member_delete)
            val edit = holder.itemView.findViewById<ImageView>(R.id.member_edit)
            val me = SessionStore.currentUser(ctx)
            val isAdmin = SessionStore.currentRole(ctx) == com.masahhisabat.app.data.Role.ADMIN
            val canEditUser = isAdmin && user.username != me && user.role != com.masahhisabat.app.data.Role.ADMIN
            if (!canEditUser) {
                delete.visibility = View.GONE
                edit?.visibility = View.GONE
            } else {
                delete.visibility = View.VISIBLE
                delete.setOnClickListener {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.confirm_delete)
                        .setMessage("سيُحذف ${user.username} ولن يستطيع الدخول مجددًا")
                        .setPositiveButton(R.string.delete) { _, _ ->
                            AppRepository.removeUser(user.username)
                            AppRepository.logActivity(ActivityEntry(me ?: "?", "حذف ${me ?: "?"} العضو ${user.username}"))
                            Toast.makeText(ctx, R.string.success, Toast.LENGTH_SHORT).show()
                            refreshMembers()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                edit?.visibility = View.VISIBLE
                edit.setOnClickListener { showEditMemberDialog(user) }
            }
        }

        override fun getItemCount() = users.size

        private fun roleLabel(role: Role) = when (role) {
            Role.ADMIN -> "مالك"
            Role.SUPERVISOR -> "مشرف"
            Role.EDITOR -> "محرر"
            Role.VIEWER -> "مشاهد"
        }
    }

    private fun showEditMemberDialog(user: User) {
        val ctx = this
        // إذا كانت كلمة المرور القديمة غير قابلة للفك (SHA-256 قديم): حوار إلزامي لإعادة تعيينها أولًا
        val storedHash = com.masahhisabat.app.data.HashUtil.decodePlain(user.passwordHash)
        if (!com.masahhisabat.app.data.HashUtil.isDecodable(user.passwordHash) || storedHash == null) {
            val reinput = EditText(ctx).apply {
                setPadding(32, 24, 32, 24)
                hint = "كلمة المرور الجديدة (3 أحرف على الأقل)"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(android.graphics.Color.parseColor("#000000"))
                setHintTextColor(android.graphics.Color.parseColor("#888888"))
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle("كلمة المرور القديمة مشفرة")
                .setMessage("لا يمكن عرض كلمة المرور القديمة لهذا العضو لأنها مشفرة بطريقة قديمة. أدخل كلمة مرور جديدة حتى يتمكن من الدخول بها وتصبح قابلة للعرض مستقبلًا.")
                .setCancelable(false)
                .setView(reinput)
                .setPositiveButton(R.string.save) { _, _ ->
                    val newPass = reinput.text.toString().trim()
                    if (newPass.length < 3) {
                        Toast.makeText(ctx, "كلمة المرور يجب أن تكون 3 أحرف على الأقل", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    AppRepository.changePassword(user.username, com.masahhisabat.app.data.HashUtil.encodePlain(newPass))
                    AppRepository.logActivity(ActivityEntry(SessionStore.currentUser(ctx) ?: "?", "أعاد تعيين كلمة مرور ${user.username}"))
                    Toast.makeText(ctx, "تم تعيين كلمة المرور الجديدة", Toast.LENGTH_SHORT).show()
                    refreshMembers()
                }
                .setNegativeButton(R.string.cancel) { _, _ -> }
                .show()
            return
        }
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_member, null)
        val etUsername = view.findViewById<EditText>(R.id.et_username)
        val etPassword = view.findViewById<EditText>(R.id.et_password)
        val spRole = view.findViewById<Spinner>(R.id.sp_role)
        val tvHint = view.findViewById<TextView>(R.id.tv_hint)

        // عرض البيانات كاملة للتعديل
        tvHint?.visibility = View.VISIBLE
        tvHint?.text = "تعديل العضو: ${user.username}"
        etUsername.hint = "اسم المستخدم الحالي: ${user.username}"
        etUsername.setText(user.username)
        // تفعيل زر إظهار/إخفاء كلمة المرور (العين)
        val ivEye = view.findViewById<android.widget.ImageView>(R.id.iv_show_password)
        var passwordVisible = false // افتراضيًا مخفية، تُظهر بالنص عند الضغط
        etPassword.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        val roles = arrayOf("مشرف", "محرر", "مشاهد")
        spRole.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, roles)
        spRole.setSelection(when (user.role) {
            Role.SUPERVISOR -> 0
            Role.EDITOR -> 1
            else -> 2
        })

        // عرض كلمة المرور الأصلية كما هي (إن كانت قابلة للفك)
        val decoded = com.masahhisabat.app.data.HashUtil.decodePlain(user.passwordHash)
        if (decoded != null) {
            etPassword.hint = "كلمة المرور الحالية"
            etPassword.setText(decoded)
            etPassword.transformationMethod = null // ظاهرة نصية كما هي
            passwordVisible = true
        } else {
            // كلمة مرور قديمة غير قابلة للفك (SHA-256): طلب كلمة مرور جديدة فورًا
            etPassword.hint = "كلمة المرور القديمة مشفرة ولا يمكن عرضها — اكتب كلمة مرور جديدة"
        }
        ivEye?.setOnClickListener {
            passwordVisible = !passwordVisible
            if (passwordVisible) {
                etPassword.transformationMethod = null
            } else {
                etPassword.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            }
            etPassword.setSelection(etPassword.text.length)
        }

        // تلوين الكتابة أسودًا واضحًا في الحقول
        etUsername.setTextColor(android.graphics.Color.parseColor("#000000"))
        etUsername.setHintTextColor(android.graphics.Color.parseColor("#888888"))
        etPassword.setTextColor(android.graphics.Color.parseColor("#000000"))
        etPassword.setHintTextColor(android.graphics.Color.parseColor("#888888"))
        tvHint?.setTextColor(android.graphics.Color.parseColor("#000000"))

        MaterialAlertDialogBuilder(ctx)
            .setTitle("تعديل العضو")
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val newUsername = etUsername.text.toString().trim().lowercase()
                val newPassword = etPassword.text.toString().trim()
                val newRole = when (spRole.selectedItemPosition) {
                    0 -> Role.SUPERVISOR
                    1 -> Role.EDITOR
                    else -> Role.VIEWER
                }
                if (newUsername.isBlank() || newUsername.contains(" ")) {
                    Toast.makeText(ctx, "اسم المستخدم غير صالح", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newUsername != user.username && AppRepository.users().any { it.username == newUsername }) {
                    Toast.makeText(ctx, "اسم المستخدم مستخدم بالفعل", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val updated = user.copy(username = newUsername, role = newRole).let { u ->
                    if (newPassword.isBlank()) {
                        // المستخدم القديم غير القابل للفك: كلمة المرور الجديدة إلزامية
                        if (!com.masahhisabat.app.data.HashUtil.isDecodable(user.passwordHash)) {
                            Toast.makeText(ctx, "كلمة المرور القديمة مشفرة ولا يمكن الاحتفاظ بها — اكتب كلمة مرور جديدة", Toast.LENGTH_LONG).show()
                            return@setPositiveButton
                        }
                        u
                    } else {
                        if (newPassword.length < 3) {
                            Toast.makeText(ctx, "كلمة المرور يجب أن تكون 3 أحرف على الأقل", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        // تشفير جديد قابل للفك حتى يمكن عرضه لاحقًا كما هو
                        u.copy(passwordHash = com.masahhisabat.app.data.HashUtil.encodePlain(newPassword))
                    }
                }
                AppRepository.removeUser(user.username)
                AppRepository.addUser(updated)
                val me = SessionStore.currentUser(ctx) ?: "?"
                AppRepository.logActivity(ActivityEntry(me, "عدّل $me العضو $newUsername"))
                Toast.makeText(ctx, "تم تحديث بيانات العضو", Toast.LENGTH_SHORT).show()
                refreshMembers()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
