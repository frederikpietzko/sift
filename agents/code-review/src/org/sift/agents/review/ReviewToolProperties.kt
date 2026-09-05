package org.sift.agents.review

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("sift.review.tools")
data class ReviewToolProperties(
    val allowedShellCommands: Set<String> = emptySet(),
)
