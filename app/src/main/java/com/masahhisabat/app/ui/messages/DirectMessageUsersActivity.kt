package com.masahhisabat.app.ui.messages

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.User
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.auth.SessionStore

/** قائمة محلية للمراسلة الفردية؛ تعيد استخدام بيانات الحضور المتزامنة داخل الشبكة فقط. */
class DirectMessageUsersActivity : AppCompatActivity() {
    private lateinit var usersList: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var adapter: UserAdapter
    private var currentUser = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppRepository.initAppContext(this)
        currentUser = SessionStore.currentUser(this).orEmpty()
        AppRepository.touchPresence(currentUser)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 18)
            setBackgroundResource(ThemeHelper.backgroundRes())
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "رجوع"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(48, 48))
        toolbar.addView(TextView(this).apply {
            text = "المراسلات"
            textSize = 22f
            typeface = resources.getFont(R.font.tajawal_bold)
            setTextColor(ThemeHelper.text(this@DirectMessageUsersActivity))
        }, LinearLayout.LayoutParams(0, 56, 1f))
        root.addView(toolbar)
        root.addView(TextView(this).apply {
            text = "اختر مستخدمًا لبدء محادثة محلية. النقطة الخضراء تعني أنه متصل الآن."
            textSize = 13f
            setPadding(8, 0, 8, 12)
            setTextColor(ThemeHelper.textSecondary(this@DirectMessageUsersActivity))
        })

        emptyState = TextView(this).apply {
            text = "لا يوجد مستخدمون آخرون متاحون للمراسلة"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(ThemeHelper.textSecondary(this@DirectMessageUsersActivity))
            visibility = View.GONE
        }
        root.addView(emptyState, LinearLayout.LayoutParams(-1, 0, 1f))
        usersList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DirectMessageUsersActivity)
            clipToPadding = false
            setPadding(0, 4, 0, 12)
        }
        root.addView(usersList, LinearLayout.LayoutParams(-1, 0, 1f))
        adapter = UserAdapter()
        usersList.adapter = adapter
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (currentUser.isNotBlank()) AppRepository.touchPresence(currentUser)
        refreshUsers()
    }

    private fun refreshUsers() {
        val users = AppRepository.users()
            .filter { it.enabled && !it.username.equals(currentUser, ignoreCase = true) }
            .sortedWith(compareByDescending<User> { AppRepository.isUserOnline(it.username) }.thenBy { it.username.lowercase() })
        emptyState.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
        usersList.visibility = if (users.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(users)
    }

    private inner class UserAdapter : RecyclerView.Adapter<UserHolder>() {
        private var users: List<User> = emptyList()
        fun submit(items: List<User>) { users = items; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserHolder {
            val card = MaterialCardView(parent.context).apply {
                radius = 22f
                strokeWidth = 1
                strokeColor = ThemeHelper.cardStroke(parent.context)
                setCardBackgroundColor(ThemeHelper.surface(parent.context))
                layoutParams = RecyclerView.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 10
                }
            }
            return UserHolder(card)
        }
        override fun getItemCount() = users.size
        override fun onBindViewHolder(holder: UserHolder, position: Int) = holder.bind(users[position])
    }

    private inner class UserHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(user: User) {
            val context = itemView.context
            val online = AppRepository.isUserOnline(user.username)
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18, 14, 18, 14)
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (online) Color.rgb(44, 174, 92) else Color.rgb(150, 160, 165))
                }
                contentDescription = if (online) "متصل الآن" else "غير متصل الآن"
            }
            row.addView(dot, LinearLayout.LayoutParams(12, 12).apply { marginEnd = 12 })
            val textBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            textBox.addView(TextView(context).apply {
                text = user.username
                textSize = 17f
                typeface = resources.getFont(R.font.tajawal_bold)
                setTextColor(ThemeHelper.text(context))
            })
            textBox.addView(TextView(context).apply {
                text = if (online) "متصل الآن" else "غير متصل"
                textSize = 13f
                setTextColor(if (online) Color.rgb(44, 174, 92) else ThemeHelper.textSecondary(context))
            })
            row.addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val card = itemView as MaterialCardView
            card.removeAllViews()
            card.addView(row)
            card.isClickable = true
            card.isFocusable = true
            card.setOnClickListener {
                startActivity(Intent(this@DirectMessageUsersActivity, DirectMessagesActivity::class.java).apply {
                    putExtra(DirectMessagesActivity.EXTRA_TARGET_USER, user.username)
                })
            }
        }
    }
}
