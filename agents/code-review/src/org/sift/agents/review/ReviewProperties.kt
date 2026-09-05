package org.sift.agents.review

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("sift.review")
data class ReviewProperties(
    val repositoryUrl: String,
    val branch: String,
    val baseBranch: String,
    val pullRequest: String? = null,
    val authToken: String? = null,
)
