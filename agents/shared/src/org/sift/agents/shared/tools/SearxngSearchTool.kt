package org.sift.agents.shared.tools

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

data class WebSearchResult(
    val title: String,
    val url: String,
    val content: String,
)

class SearxngSearchTool(
    restClientBuilder: RestClient.Builder,
    private val properties: SearxngProperties,
) {
    private val restClient = restClientBuilder.baseUrl(properties.baseUrl).build()

    @Tool(
        description = "Searches the web for the given query and returns a list of results " +
            "containing a title, a url and a short content snippet.",
    )
    fun searchWeb(
        @ToolParam(description = "The web search query.") query: String,
    ): List<WebSearchResult> =
        try {
            val response = restClient.get()
                .uri("/search?q={query}&format=json", query)
                .retrieve()
                .body(SearxngResponse::class.java)

            response?.results
                .orEmpty()
                .take(properties.maxResults)
                .map { result ->
                    WebSearchResult(
                        title = result.title.orEmpty(),
                        url = result.url.orEmpty(),
                        content = result.content.orEmpty(),
                    )
                }
        } catch (exception: RestClientException) {
            logger.warn("Web search via SearXNG failed for query '{}'", query, exception)
            listOf(
                WebSearchResult(
                    title = "Web search failed",
                    url = "",
                    content = "The web search could not be executed: ${exception.message}. " +
                        "Proceed without web search results.",
                ),
            )
        }

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class SearxngResponse(
        val results: List<SearxngResult> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class SearxngResult(
        val title: String? = null,
        val url: String? = null,
        val content: String? = null,
    )

    private companion object {
        private val logger = LoggerFactory.getLogger(SearxngSearchTool::class.java)
    }
}
