package com.masahhisabat.app.ui.messages

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.card.MaterialCardView
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.User
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.auth.SessionStore
import com.masahhisabat.app.ui.common.DetailActivityTransition
import com.masahhisabat.app.ui.common.LocalContentRefreshState

/** قائمة محلية للمراسلة الفردية؛ تعيد استخدام بيانات الحضور المتزامنة داخل الشبكة فقط. */
class DirectMessageUsersActivity : AppCompatActivity() {
    private lateinit var usersList: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var adapter: UserAdapter
    private var currentUser = ""
    private lateinit var contentRefresh: SwipeRefreshLayout
    private val refreshState = LocalContentRefreshState()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppRepository.initAppContext(this)
        currentUser = SessionStore.currentUser(this).orEmpty()
        AppRepository.touchPresence(currentUser)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
            setBackgroundResource(ThemeHelper.backgroundRes())
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.direct_back)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                DetailActivityTransition.finish(this@DirectMessageUsersActivity)
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(TextView(this).apply {
            text = getString(R.string.direct_messages_list_title)
            textSize = 22f
            typeface = resources.getFont(R.font.tajawal_bold)
            setTextColor(ThemeHelper.text(this@DirectMessageUsersActivity))
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(toolbar)
        root.addView(TextView(this).apply {
            text = getString(R.string.direct_messages_list_subtitle)
            textSize = 13f
            setPadding(dp(8), 0, dp(8), dp(12))
            setTextColor(ThemeHelper.textSecondary(this@DirectMessageUsersActivity))
        })

        emptyState = TextView(this).apply {
            text = getString(R.string.direct_no_users_available)
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(ThemeHelper.textSecondary(this@DirectMessageUsersActivity))
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, R.drawable.ic_chat_bubble, 0, 0)
            compoundDrawablePadding = dp(12)
            TextViewCompat.setCompoundDrawableTintList(
                this,
                android.content.res.ColorStateList.valueOf(ThemeHelper.accent(this@DirectMessageUsersActivity))
            )
            visibility = View.GONE
        }
        usersList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DirectMessageUsersActivity)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(12))
        }
        val content = FrameLayout(this).apply {
            addView(emptyState, FrameLayout.LayoutParams(-1, -1))
            addView(usersList, FrameLayout.LayoutParams(-1, -1))
        }
        contentRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(ThemeHelper.accent(this@DirectMessageUsersActivity))
            setOnChildScrollUpCallback { _, _ -> usersList.canScrollVertically(-1) }
            setOnRefreshListener { refreshFromSwipeGesture() }
            addView(content)
        }
        root.addView(contentRefresh, LinearLayout.LayoutParams(-1, 0, 1f))
        adapter = UserAdapter()
        usersList.adapter = adapter
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (currentUser.isNotBlank()) AppRepository.touchPresence(currentUser)
        refreshUsers()
    }

    override fun onDestroy() {
        refreshState.cancel()
        super.onDestroy()
    }

    private fun refreshUsers() {
        val users = AppRepository.users()
            .filter { it.enabled && !it.username.equals(currentUser, ignoreCase = true) }
            .sortedWith(compareByDescending<User> { AppRepository.isUserOnline(it.username) }.thenBy { it.username.lowercase() })
        emptyState.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
        usersList.visibility = if (users.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(users)
    }

    private fun refreshFromSwipeGesture() {
        if (!refreshState.tryStart()) {
            contentRefresh.isRefreshing = false
            return
        }
        try {
            refreshUsers()
        } finally {
            refreshState.finish()
            contentRefresh.isRefreshing = false
        }
    }

    private inner class UserAdapter : RecyclerView.Adapter<UserHolder>() {
        private var users: List<User> = emptyList()
        private var onlineStates: Map<String, Boolean> = emptyMap()

        fun submit(items: List<User>) {
            val newUsers = items.toList()
            val newOnlineStates = newUsers.associate { user ->
                user.username.lowercase() to AppRepository.isUserOnline(user.username)
            }
            val oldUsers = users
            val oldOnlineStates = onlineStates
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = oldUsers.size
                override fun getNewListSize() = newUsers.size
                override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                    oldUsers[oldPosition].username.equals(newUsers[newPosition].username, ignoreCase = true)

                override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean {
                    val oldUser = oldUsers[oldPosition]
                    val newUser = newUsers[newPosition]
                    return oldUser == newUser &&
                        oldOnlineStates[oldUser.username.lowercase()] == newOnlineStates[newUser.username.lowercase()]
                }
            })
            users = newUsers
            onlineStates = newOnlineStates
            diff.dispatchUpdatesTo(this)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserHolder {
            val card = MaterialCardView(parent.context).apply {
                radius = dp(22).toFloat()
                strokeWidth = dp(1)
                strokeColor = ThemeHelper.cardStroke(parent.context)
                setCardBackgroundColor(ThemeHelper.surface(parent.context))
                layoutParams = RecyclerView.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
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
                setPadding(dp(18), dp(14), dp(18), dp(14))
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (online) Color.rgb(44, 174, 92) else Color.rgb(150, 160, 165))
                }
                contentDescription = getString(
                    if (online) R.string.direct_presence_online else R.string.direct_presence_offline_now
                )
            }
            row.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) })
            val textBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            textBox.addView(TextView(context).apply {
                text = user.username
                textSize = 17f
                typeface = resources.getFont(R.font.tajawal_bold)
                setTextColor(ThemeHelper.text(context))
            })
            textBox.addView(TextView(context).apply {
                text = getString(if (online) R.string.direct_presence_online else R.string.direct_presence_offline)
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
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                DetailActivityTransition.start(
                    this@DirectMessageUsersActivity,
                    Intent(this@DirectMessageUsersActivity, DirectMessagesActivity::class.java).apply {
                        putExtra(DirectMessagesActivity.EXTRA_TARGET_USER, user.username)
                    }
                )
            }
        }
    }
}
