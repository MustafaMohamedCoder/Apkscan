package com.masahhisabat.app.ui.messages

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.DirectMessage
import com.masahhisabat.app.data.Role
import com.masahhisabat.app.data.ShareCard
import com.masahhisabat.app.data.User
import com.masahhisabat.app.ui.auth.SessionStore
import com.masahhisabat.app.ui.invoice.GroupActivity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * تغطية تفاعلية للتدفقات المحلية الحرجة: تأليف رسالة، ثم عرض بطاقة مشاركة وفتح مصدرها.
 * تستخدم بيانات فريدة في كل تشغيل كي لا تعتمد على حالة جهاز أو محاكي سابق.
 */
@RunWith(AndroidJUnit4::class)
class DirectMessagesFlowTest {
    private val targetUser = "ui_test_recipient"
    private lateinit var context: Context
    private var scenario: ActivityScenario<DirectMessagesActivity>? = null

    @Before
    fun prepareSession() {
        context = ApplicationProvider.getApplicationContext()
        AppRepository.initAppContext(context)
        if (AppRepository.users().none { it.username.equals(targetUser, ignoreCase = true) }) {
            AppRepository.addUser(User(targetUser, "ui-test", Role.VIEWER))
        }
        val sender = AppRepository.users().first { it.username.equals("mustafa", ignoreCase = true) }
        SessionStore.save(context, sender)
        Intents.init()
    }

    @After
    fun closeScenario() {
        scenario?.close()
        Intents.release()
        SessionStore.clear(context)
    }

    @Test
    fun sendLocalTextMessage_displaysItInTheConversation() {
        val body = "رسالة واجهة محلية ${System.currentTimeMillis()}"
        launchConversation()

        onView(withId(R.id.direct_message_input)).perform(replaceText(body))
        closeSoftKeyboard()
        onView(withId(R.id.direct_message_send)).perform(click())

        onView(withText(body)).check(matches(isDisplayed()))
    }

    @Test
    fun sharedGroupCard_displaysAndNavigatesToItsSourceGroup() {
        val groupId = "ui-group-${System.currentTimeMillis()}"
        val title = "فواتير اختبار الواجهة"
        AppRepository.addDirectMessage(
            DirectMessage(
                fromUser = "mustafa",
                toUser = targetUser,
                shareCard = ShareCard(
                    kind = "group",
                    sourceGroupId = groupId,
                    title = title,
                    preview = "بطاقة مرجعية دون نسخ المرفق"
                )
            )
        )
        launchConversation()

        onView(withText(title)).check(matches(isDisplayed()))
        onView(withId(R.id.direct_message_share_card)).perform(click())

        intended(hasComponent(GroupActivity::class.java.name))
        intended(hasExtra("group_id", groupId))
    }

    private fun launchConversation() {
        scenario = ActivityScenario.launch(
            Intent(context, DirectMessagesActivity::class.java)
                .putExtra(DirectMessagesActivity.EXTRA_TARGET_USER, targetUser)
        )
    }
}
