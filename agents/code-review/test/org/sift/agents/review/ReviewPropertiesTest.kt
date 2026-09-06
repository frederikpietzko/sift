package org.sift.agents.review

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReviewPropertiesTest {
    @Test
    fun `accepts a full 40-character hexadecimal commit sha`() {
        val properties = reviewProperties(commitSha = "0123456789abcdef0123456789ABCDEF01234567")

        assertEquals("0123456789abcdef0123456789ABCDEF01234567", properties.commitSha)
    }

    @Test
    fun `rejects an abbreviated commit sha`() {
        assertFailsWith<IllegalArgumentException> {
            reviewProperties(commitSha = "0123456")
        }
    }

    @Test
    fun `rejects a blank commit sha`() {
        assertFailsWith<IllegalArgumentException> {
            reviewProperties(commitSha = "   ")
        }
    }

    @Test
    fun `rejects a non hexadecimal commit sha`() {
        assertFailsWith<IllegalArgumentException> {
            reviewProperties(commitSha = "g123456789abcdef0123456789abcdef01234567")
        }
    }

    private fun reviewProperties(commitSha: String) = ReviewProperties(
        repositoryUrl = "https://example.com/org/repo.git",
        branch = "feature/validation",
        baseBranch = "main",
        commitSha = commitSha,
        executionId = "review-uid:1",
    )
}
