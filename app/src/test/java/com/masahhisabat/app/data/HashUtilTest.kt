package com.masahhisabat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات ثابتة لحماية توافق بيانات الدخول المحلية بين الإصدارات. */
class HashUtilTest {

    @Test
    fun hash_matchesIndependentSha256TestVector() {
        assertEquals(
            "a80f77edec591a077f2e9a46f9315b037cd49498b63e761964e5baa4e872105a",
            HashUtil.hash("password")
        )
    }

    @Test
    fun hash_changesWhenPasswordChanges() {
        assertNotEquals(HashUtil.hash("0"), HashUtil.hash("00"))
    }

    @Test
    fun decodableFlagRequiresValidVersionTwoPayload() {
        assertTrue(HashUtil.isDecodable(HashUtil.encodePlain("كلمة مرور")))
        assertFalse(HashUtil.isDecodable("v2:not-valid-base64@@"))
        assertFalse(HashUtil.isDecodable("v1:legacy-value"))
        assertFalse(HashUtil.isDecodable(""))
    }
}
