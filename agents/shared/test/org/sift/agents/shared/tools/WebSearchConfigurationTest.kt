package org.sift.agents.shared.tools

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.client.RestClient

class WebSearchConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withBean(RestClient.Builder::class.java, { RestClient.builder() })
        .withConfiguration(AutoConfigurations.of(WebSearchConfiguration::class.java))

    @Test
    fun `web search defaults enable the tool without configuration`() {
        assertThat(SearxngProperties().enabled).isTrue()
        runner.run { context ->
            assertThat(context).hasSingleBean(SearxngSearchTool::class.java)
            assertThat(context.getBean(SearxngProperties::class.java).enabled).isTrue()
        }
    }

    @Test
    fun `SearxngSearchTool bean is absent when web search is disabled`() {
        runner.withPropertyValues("sift.tools.web-search.enabled=false").run { context ->
            assertThat(context).doesNotHaveBean(SearxngSearchTool::class.java)
        }
    }

    @Test
    fun `SearxngSearchTool bean is present when web search is enabled`() {
        runner.withPropertyValues("sift.tools.web-search.enabled=true").run { context ->
            assertThat(context).hasSingleBean(SearxngSearchTool::class.java)
        }
    }
}
