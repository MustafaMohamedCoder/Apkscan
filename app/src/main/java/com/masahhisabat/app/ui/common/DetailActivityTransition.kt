package com.masahhisabat.app.ui.common

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import com.masahhisabat.app.R

/**
 * انتقالات قصيرة لشاشات التفاصيل. عند تعطيل حركة النظام يبقى الانتقال فوريًا
 * حتى لا تتحول الحركة المرئية إلى عائق أمام المستخدم.
 */
object DetailActivityTransition {
    fun start(activity: Activity, intent: Intent) {
        activity.startActivity(intent)
        if (DetailMotionPolicy.resolve(ValueAnimator.areAnimatorsEnabled()) == DetailMotion.ANIMATED) {
            activity.overridePendingTransition(R.anim.detail_enter, R.anim.detail_exit)
        }
    }

    fun finish(activity: Activity) {
        activity.finish()
        if (DetailMotionPolicy.resolve(ValueAnimator.areAnimatorsEnabled()) == DetailMotion.ANIMATED) {
            activity.overridePendingTransition(R.anim.detail_return_enter, R.anim.detail_return_exit)
        }
    }
}
