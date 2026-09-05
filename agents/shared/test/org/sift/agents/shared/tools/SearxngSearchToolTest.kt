package org.sift.agents.shared.tools

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearxngSearchToolTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()

    @Test
    fun `searchWeb encodes the query, requests json format and maps the results`() {
        val tool = SearxngSearchTool(builder, SearxngProperties())
        server.expect(requestTo("http://localhost:8888/search?q=kotlin%20coroutines&format=json"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(SEARXNG_PAYLOAD, MediaType.APPLICATION_JSON))

        val results = tool.searchWeb("kotlin coroutines")

        server.verify()
        assertEquals(
            listOf(
                WebSearchResult(
                    title = "Kotlin Coroutines",
                    url = "https://kotlinlang.org/docs/coroutines-overview.html",
                    content = "Coroutines are light-weight threads.",
                ),
                WebSearchResult(
                    title = "Coroutines Guide",
                    url = "https://kotlinlang.org/docs/coroutines-guide.html",
                    content = "",
                ),
            ),
            results,
        )
    }

    @Test
    fun `searchWeb limits the number of results to maxResults`() {
        val tool = SearxngSearchTool(builder, SearxngProperties(maxResults = 1))
        server.expect(requestTo("http://localhost:8888/search?q=kotlin&format=json"))
            .andRespond(withSuccess(SEARXNG_PAYLOAD, MediaType.APPLICATION_JSON))

        val results = tool.searchWeb("kotlin")

        assertEquals(1, results.size)
        assertEquals("Kotlin Coroutines", results.single().title)
    }

    @Test
    fun `searchWeb returns an error describing result when the request fails`() {
        val tool = SearxngSearchTool(builder, SearxngProperties())
        server.expect(requestTo("http://localhost:8888/search?q=kotlin&format=json"))
            .andRespond(withServerError())

        val results = tool.searchWeb("kotlin")

        assertEquals(1, results.size)
        assertEquals("Web search failed", results.single().title)
        assertTrue(results.single().content.contains("Proceed without web search results."))
    }

    private companion object {
        @Suppress("MaxLineLength")
        private const val SEARXNG_PAYLOAD = """
            {
              "query": "kotlin coroutines",
              "number_of_results": 2,
              "results": [
                {
                  "title": "Kotlin Coroutines",
                  "url": "https://kotlinlang.org/docs/coroutines-overview.html",
                  "content": "Coroutines are light-weight threads.",
                  "engine": "duckduckgo",
                  "score": 1.0
                },
                {
                  "title": "Coroutines Guide",
                  "url": "https://kotlinlang.org/docs/coroutines-guide.html",
                  "engine": "brave"
                }
              ]
            }
        """
    }
}
