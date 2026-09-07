package org.sift.e2e

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SamplePullRequestTest {

    private val mapper = JsonMapper.builder().build()

    private fun pull(headClone: String, baseClone: String = "https://github.com/o/r.git") = mapper.readTree(
        """
        {"number": 1,
         "head": {"ref": "feature/x", "sha": "abc123", "repo": {"clone_url": "$headClone"}},
         "base": {"ref": "main", "repo": {"clone_url": "$baseClone"}}}
        """.trimIndent(),
    )

    @Test
    fun `maps the GitHub payload to a review spec`() {
        val spec = SamplePullRequest.fromJson(pull("https://github.com/o/r.git"))

        assertEquals(
            ReviewSpec(
                repositoryUrl = "https://github.com/o/r.git",
                branch = "feature/x",
                baseBranch = "main",
                commitSha = "abc123",
                pullRequest = "1",
            ),
            spec,
        )
    }

    @Test
    fun `rejects pull requests whose head lives in a fork`() {
        val failure = assertThrows<E2ePreconditionException> {
            SamplePullRequest.fromJson(pull("https://github.com/fork/r.git"))
        }

        assertTrue(failure.message.orEmpty().contains("fork"), failure.message)
    }

    @Test
    fun `rejects payloads without clone urls`() {
        assertThrows<E2ePreconditionException> { SamplePullRequest.fromJson(mapper.readTree("""{"number":1}""")) }
    }

    @Test
    fun `parses owner repo and number`() {
        val coordinates = PullRequestCoordinates.parse(SamplePullRequest.DEFAULT)

        assertEquals(PullRequestCoordinates("frederikpietzko", "ebfs-jpa", 1), coordinates)
        assertEquals("https://api.github.com/repos/frederikpietzko/ebfs-jpa/pulls/1", coordinates.apiUrl)
        assertThrows<E2ePreconditionException> { PullRequestCoordinates.parse("not-a-pr") }
    }
}
