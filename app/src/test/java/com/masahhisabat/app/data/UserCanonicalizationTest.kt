package com.masahhisabat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserCanonicalizationTest {

    @Test
    fun `canonical users trims normalizes and keeps the first duplicate`() {
        val earliest = User("  Mustafa  ", "first", Role.ADMIN, createdAt = 1L)
        val duplicate = User("mustafa", "second", Role.VIEWER, createdAt = 2L)
        val editor = User("Editor", "third", Role.EDITOR)

        val result = AppRepository.canonicalUsersForSync(listOf(earliest, duplicate, editor))

        assertEquals(listOf("mustafa", "editor"), result.map { it.username })
        assertEquals("first", result.first().passwordHash)
        assertEquals(Role.ADMIN, result.first().role)
    }

    @Test
    fun `canonical users rejects blank normalized names`() {
        val result = AppRepository.canonicalUsersForSync(
            listOf(
                User("   ", "ignored", Role.VIEWER),
                User("Valid", "kept", Role.EDITOR)
            )
        )

        assertEquals(1, result.size)
        assertEquals("valid", result.single().username)
        assertTrue(result.none { it.username.isBlank() })
    }
}
