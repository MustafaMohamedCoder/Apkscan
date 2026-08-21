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
        val activeUsername = AppRepository.normalizeUsername(SessionStore.currentUser(this).orEmpty())
        val users = AppRepository.users().sortedWith(
            compareByDescending<User> { AppRepository.normalizeUsername(it.username) == activeUsername }
                .thenBy { AppRepository.normalizeUsername(it.username) }
        )
        empty.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
        recycler.adapter = MembersAdapter(users)
        bindCurrentUser(activeUsername, users)
    }

    /** بطاقة الجلسة الحالية تصف المستخدم المحلي فقط؛ لا تدّعي وجود اتصال شبكي لبقية الحسابات. */
    private fun bindCurrentUser(activeUsername: String, users: List<User>) {
        val sessionRole = SessionStore.currentRole(this)
        val current = users.firstOrNull {
            AppRepository.normalizeUsername(it.username) == activeUsername
        }
        val displayName = current?.username ?: SessionStore.currentUser(this).orEmpty().ifBlank { "حساب غير محدد" }
        val avatar = displayName.take(1).uppercase()
        val role = current?.role ?: sessionRole

        findViewById<TextView>(R.id.current_user_avatar).text = avatar
        findViewById<TextView>(R.id.current_user_name).text = displayName
        findViewById<TextView>(R.id.current_user_role).text = "${roleLabel(role)} — ${rolePermissions(role)}"
        findViewById<TextView>(R.id.current_user_state).text = "متصل الآن على هذا الجهاز"
    }

    private fun showAddMemberDialog() {
        val ctx = this
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_member, null)
        val etUsername = view.findViewById<EditText>(R.id.et_username)
        val etPassword = view.findViewById<EditText>(R.id.et_password)
        val spRole = view.findViewById<Spinner>(R.id.sp_role)
        val roles = arrayOf("مشرف", "محرر", "مشاهد")
        spRole.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, roles)

        // ألوان دلالية تتكيف مع المظهر، بدل ألوان ثابتة تجعل النص غير واضح ليلًا.
        etUsername.setTextColor(ThemeHelper.text(ctx))
        etUsername.setHintTextColor(ThemeHelper.textSecondary(ctx))
        etPassword.setTextColor(ThemeHelper.text(ctx))
        etPassword.setHintTextColor(ThemeHelper.textSecondary(ctx))

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.add_member)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.save) { _, _ ->
                val username = AppRepository.normalizeUsername(etUsername.text.toString())
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
                if (AppRepository.users().any { AppRepository.normalizeUsername(it.username) == username }) {
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
        window.decorView.setBackgroundResource(ThemeHelper.backgroundRes())
        findViewById<View>(R.id.team_root).setBackgroundResource(ThemeHelper.backgroundRes())
        val text = ThemeHelper.text(this)
        findViewById<TextView>(R.id.tv_title).setTextColor(text)
        findViewById<MaterialCardView>(R.id.current_user_card).apply {
            setCardBackgroundColor(ThemeHelper.surfaceHigh(this@TeamActivity))
            strokeColor = ThemeHelper.cardStroke(this@TeamActivity)
        }
        findViewById<TextView>(R.id.current_user_name).setTextColor(text)
        findViewById<TextView>(R.id.current_user_role).setTextColor(ThemeHelper.textSecondary(this))
        findViewById<TextView>(R.id.current_user_state).setTextColor(getColor(R.color.water_deep))
        findViewById<TextView>(R.id.empty_members).setTextColor(ThemeHelper.textSecondary(this))
    }

    inner class MembersAdapter(private val users: List<User>) : RecyclerView.Adapter<MembersAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_member, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val user = users[position]
            val ctx = holder.itemView.context
            val card = holder.itemView as MaterialCardView
            val currentUsername = AppRepository.normalizeUsername(SessionStore.currentUser(ctx).orEmpty())
            val isCurrent = currentUsername.isNotBlank() &&
                AppRepository.normalizeUsername(user.username) == currentUsername
            card.setCardBackgroundColor(if (isCurrent) ThemeHelper.surfaceHigh(ctx) else ThemeHelper.surface(ctx))
            card.strokeColor = if (isCurrent) getColor(R.color.accent) else ThemeHelper.cardStroke(ctx)
            card.strokeWidth = if (isCurrent) 2 else 1
            val text = ThemeHelper.text(ctx)
            val textSec = ThemeHelper.textSecondary(ctx)

            holder.itemView.findViewById<TextView>(R.id.member_name).apply {
                this.text = user.username
                setTextColor(text)
            }
            holder.itemView.findViewById<TextView>(R.id.member_role).apply {
                this.text = "${roleLabel(user.role)} — ${rolePermissions(user.role)}"
                setTextColor(textSec)
            }
            holder.itemView.findViewById<TextView>(R.id.member_status).apply {
                this.text = when {
                    isCurrent -> "متصل الآن"
                    !user.enabled -> "موقوف"
                    user.role == Role.ADMIN -> "مالك"
                    else -> "مفعّل"
                }
                setTextColor(
                    when {
                        isCurrent -> getColor(R.color.water_deep)
                        !user.enabled -> getColor(R.color.error)
                        else -> textSec
                    }
                )
                setBackgroundResource(if (isCurrent) R.drawable.online_status_bg else R.drawable.member_status_bg)
            }

            holder.itemView.contentDescription = "${user.username}، ${roleLabel(user.role)}، ${holder.itemView.findViewById<TextView>(R.id.member_status).text}"

            // صف كلمة المرور: عرض/إخفاء بأيقونة العين داخل التطبيق فقط، دون نسخ للحافظة.
            val passwordText = holder.itemView.findViewById<TextView>(R.id.member_password)
            val ivPassword = holder.itemView.findViewById<ImageView>(R.id.iv_password_visibility)
            val decoded = com.masahhisabat.app.data.HashUtil.decodePlain(user.passwordHash)
            val hiddenMarker = "••••••••"
            var passwordHidden = true
            if (decoded != null) {
                passwordText.text = "$hiddenMarker (اضغط العين للإظهار)"
                passwordText.setTextColor(textSec)
                ivPassword.visibility = View.VISIBLE
                ivPassword.setColorFilter(textSec)
                ivPassword.contentDescription = "إظهار كلمة المرور"
            } else {
                passwordText.text = "كلمة المرور قديمة وغير قابلة للفك — عدّل العضو لتعيين كلمة مرور جديدة"
                passwordText.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.error))
                ivPassword.visibility = View.GONE
            }
            ivPassword.setOnClickListener {
                if (decoded != null) {
                    passwordHidden = !passwordHidden
                    passwordText.text = if (passwordHidden) "$hiddenMarker (اضغط العين للإظهار)" else "كلمة المرور: $decoded"
                    passwordText.setTextColor(if (passwordHidden) textSec else text)
                    ivPassword.contentDescription = if (passwordHidden) "إظهار كلمة المرور" else "إخفاء كلمة المرور"
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

    }

    private fun roleLabel(role: Role) = when (role) {
        Role.ADMIN -> "مالك"
        Role.SUPERVISOR -> "مشرف"
        Role.EDITOR -> "محرر"
        Role.VIEWER -> "مشاهد"
    }

    private fun rolePermissions(role: Role) = when (role) {
        Role.ADMIN -> "إدارة كاملة"
        Role.SUPERVISOR -> "مزامنة وتحرير وحذف"
        Role.EDITOR -> "إضافة وتعديل الرسائل"
        Role.VIEWER -> "قراءة فقط"
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
                setTextColor(ThemeHelper.text(ctx))
                setHintTextColor(ThemeHelper.textSecondary(ctx))
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

        // ألوان دلالية تمنع ظهور نص داكن على خلفية داكنة في الحوار الليلي.
        etUsername.setTextColor(ThemeHelper.text(ctx))
        etUsername.setHintTextColor(ThemeHelper.textSecondary(ctx))
        etPassword.setTextColor(ThemeHelper.text(ctx))
        etPassword.setHintTextColor(ThemeHelper.textSecondary(ctx))
        tvHint?.setTextColor(ThemeHelper.text(ctx))

        MaterialAlertDialogBuilder(ctx)
            .setTitle("تعديل العضو")
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val newUsername = AppRepository.normalizeUsername(etUsername.text.toString())
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
                val currentUsername = AppRepository.normalizeUsername(user.username)
                if (newUsername != currentUsername && AppRepository.users().any {
                        AppRepository.normalizeUsername(it.username) == newUsername
                    }) {
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
                AppRepository.updateUser(user.username, updated)
                val me = SessionStore.currentUser(ctx) ?: "?"
                AppRepository.logActivity(ActivityEntry(me, "عدّل $me العضو $newUsername"))
                Toast.makeText(ctx, "تم تحديث بيانات العضو", Toast.LENGTH_SHORT).show()
                refreshMembers()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
