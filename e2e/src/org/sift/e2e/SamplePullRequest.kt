package org.sift.e2e

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Coordinates the scenario sends to `POST /api/v1/agents`; resolved from a live GitHub pull request. */
data class ReviewSpec(
    val repositoryUrl: String,
    val branch: String,
    val baseBranch: String,
    val commitSha: String,
    val pullRequest: String,
)

/** `owner/repo#number` parsed from [SamplePullRequest.DEFAULT] or the `SIFT_E2E_PR` override. */
data class PullRequestCoordinates(val owner: String, val repo: String, val number: Int) {
    val apiUrl: String get() = "https://api.github.com/repos/$owner/$repo/pulls/$number"

    companion object {
        private val pattern = Regex("""^(?<owner>[A-Za-z0-9_.-]+)/(?<repo>[A-Za-z0-9_.-]+)#(?<number>\d+)$""")

        fun parse(value: String): PullRequestCoordinates {
            val groups = pattern.matchEntire(value.trim())?.groups
                ?: throw E2ePreconditionException("Expected a pull request as owner/repo#number, got '$value'")
            return PullRequestCoordinates(
                owner = groups["owner"]?.value.orEmpty(),
                repo = groups["repo"]?.value.orEmpty(),
                number = groups["number"]?.value.orEmpty().toInt(),
            )
        }
    }
}

/**
 * Resolves the sample pull request at runtime from the GitHub API (same source as
 * `k8s/local/acceptance.py`), so branch names and SHAs never go stale in the test. A PR whose head
 * lives in a fork is rejected because the review agent's checkout contract only covers same-repo
 * branches. `GITHUB_TOKEN`, when present, is sent as a bearer token to avoid rate limits.
 */
object SamplePullRequest {
    const val DEFAULT = "frederikpietzko/ebfs-jpa#1"
    const val OVERRIDE_VARIABLE = "SIFT_E2E_PR"
    const val TOKEN_VARIABLE = "GITHUB_TOKEN"
    private const val REQUEST_TIMEOUT_SECONDS = 20L
    private val requestTimeout = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)
    private val mapper = JsonMapper.builder().build()

    fun resolve(env: Map<String, String> = System.getenv()): ReviewSpec {
        val coordinates = PullRequestCoordinates.parse(env[OVERRIDE_VARIABLE]?.takeIf { it.isNotBlank() } ?: DEFAULT)
        val body = fetch(coordinates, env[TOKEN_VARIABLE]?.takeIf { it.isNotBlank() })
        return fromJson(mapper.readTree(body))
    }

    /** Maps the GitHub `pulls` payload to a [ReviewSpec]; pure so it can be unit tested. */
    fun fromJson(pull: JsonNode): ReviewSpec {
        val head = pull.path("head")
        val base = pull.path("base")
        val headClone = head.path("repo").path("clone_url").asString()
        val baseClone = base.path("repo").path("clone_url").asString()
        if (headClone.isBlank() || baseClone.isBlank()) {
            throw E2ePreconditionException("GitHub pull request payload lacks head/base repository clone URLs")
        }
        if (headClone != baseClone) {
            throw E2ePreconditionException(
                "Sample PR head lives in a fork ($headClone); explicit checkout-contract review required",
            )
        }
        return ReviewSpec(
            repositoryUrl = baseClone,
            branch = head.path("ref").asString(),
            baseBranch = base.path("ref").asString(),
            commitSha = head.path("sha").asString(),
            pullRequest = pull.path("number").asString(),
        )
    }

    private fun fetch(coordinates: PullRequestCoordinates, token: String?): String {
        val request = HttpRequest.newBuilder(URI.create(coordinates.apiUrl))
            .timeout(requestTimeout)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "sift-e2e")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .GET()
            .build()
        val response = send(request)
        if (response.statusCode() != HTTP_OK) {
            throw E2ePreconditionException(
                "GitHub returned HTTP ${response.statusCode()} for ${coordinates.apiUrl}; " +
                    "check connectivity or set $TOKEN_VARIABLE to lift rate limits",
            )
        }
        return response.body()
    }

    private fun send(request: HttpRequest): HttpResponse<String> =
        try {
            HttpClient.newBuilder().connectTimeout(requestTimeout).build()
                .send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw E2ePreconditionException("GitHub is unreachable (${request.uri()}): ${e.message}", e)
        }

    private const val HTTP_OK = 200
}
