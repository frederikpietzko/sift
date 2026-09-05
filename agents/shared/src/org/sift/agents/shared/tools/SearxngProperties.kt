package org.sift.agents.shared.tools

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("sift.tools.web-search")
data class SearxngProperties(
    val enabled: Boolean = true,
    val baseUrl: String = "http://localhost:8888",
    val maxResults: Int = 5,
)
