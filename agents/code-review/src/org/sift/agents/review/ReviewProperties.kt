package org.sift.agents.review

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("sift.review")
data class ReviewProperties(
    val repositoryUrl: String,
    val branch: String,
    val baseBranch: String,
    val commitSha: String,
    val executionId: String,
    val pullRequest: String? = null,
    val authToken: String? = null,
) {
    init {
        require(commitSha.matches(COMMIT_SHA_PATTERN)) {
            "sift.review.commit-sha must be a full 40-character hexadecimal Git commit SHA, but was '$commitSha'"
        }
    }

    private companion object {
        val COMMIT_SHA_PATTERN = Regex("^[0-9a-fA-F]{40}$")
    }
}
