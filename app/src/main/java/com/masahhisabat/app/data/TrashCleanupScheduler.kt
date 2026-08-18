package com.masahhisabat.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * ينفذ تنظيفًا محليًا مؤجلًا للسلة. لا يحتاج إنترنتًا ولا يوقظ الجهاز بموعد دقيق.
 * يبقى وقت التشغيل مرنًا كي يراعي أندرويد وضع توفير الطاقة.
 */
class TrashCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        AppRepository.initAppContext(applicationContext)
        AppRepository.purgeExpiredTrash()
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

/** يبقي مهمة تنظيف واحدة فقط ويزيلها فور إيقاف المستخدم للخيار. */
object TrashCleanupScheduler {
    private const val WORK_NAME = "masah_trash_cleanup"

    fun update(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!AppRepository.isAutoTrashPurgeEnabled()) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
