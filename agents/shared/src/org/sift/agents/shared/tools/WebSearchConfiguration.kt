package org.sift.agents.shared.tools

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.web.client.RestClient

@AutoConfiguration(after = [RestClientAutoConfiguration::class])
@EnableConfigurationProperties(SearxngProperties::class)
class WebSearchConfiguration {
    @Bean
    @ConditionalOnMissingBean(SearxngSearchTool::class)
    @ConditionalOnProperty("sift.tools.web-search.enabled", havingValue = "true", matchIfMissing = true)
    fun searxngSearchTool(
        restClientBuilder: RestClient.Builder,
        properties: SearxngProperties,
    ): SearxngSearchTool = SearxngSearchTool(restClientBuilder, properties)
}
