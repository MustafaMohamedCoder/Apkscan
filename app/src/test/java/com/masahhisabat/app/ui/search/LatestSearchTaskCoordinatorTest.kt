package com.masahhisabat.app.ui.search

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestSearchTaskCoordinatorTest {

    @Test
    fun submit_cancelsPreviousWorkSoTheLatestSearchCanRun() {
        val coordinator = LatestSearchTaskCoordinator()
        val firstStarted = CountDownLatch(1)
        val latestRan = CountDownLatch(1)

        coordinator.submit {
            firstStarted.countDown()
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(5))
            } catch (_: InterruptedException) {
                // إلغاء الطلب السابق هو السلوك المطلوب عند وصول طلب أحدث.
            }
        }
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS))

        coordinator.submit { latestRan.countDown() }

        assertTrue(latestRan.await(1, TimeUnit.SECONDS))
        coordinator.close()
    }
}
