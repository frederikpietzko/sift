package org.sift.server.repositories

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration
import org.sift.server.api.ApiExceptionHandler
import org.sift.server.api.ConflictException
import org.sift.server.api.NotFoundException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test

/** `Application` imports [ExposedAutoConfiguration] explicitly; the MVC slice has no data source, so it is excluded. */
@WebMvcTest(controllers = [RepositoryController::class], excludeAutoConfiguration = [ExposedAutoConfiguration::class])
@Import(ApiExceptionHandler::class)
class RepositoryControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var service: RepositoryService

    private val id: UUID = UUID.fromString("9b2c0c8e-1f4e-4c21-a9c8-4b1b5e1f8d10")
    private val repository = Repository(
        id = id,
        name = "sift",
        url = "https://github.com/sift/sift.git",
        token = EncryptedToken(ciphertext = byteArrayOf(1, 2, 3), iv = byteArrayOf(4, 5, 6)),
        secretName = "sift-repo-$id",
        createdAt = OffsetDateTime.parse("2026-09-07T10:00:00Z"),
        updatedAt = OffsetDateTime.parse("2026-09-07T11:00:00Z"),
    )

    @Test
    fun `POST creates a repository and returns 201 with location and no token`() {
        every { service.create("sift", "https://github.com/sift/sift.git", "ghp_token") } returns repository

        mockMvc.post("/api/v1/repositories") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"sift","url":"https://github.com/sift/sift.git","token":"ghp_token"}"""
        }.andExpect {
            status { isCreated() }
            header { string("Location", "http://localhost/api/v1/repositories/$id") }
            jsonPath("$.id") { value(id.toString()) }
            jsonPath("$.name") { value("sift") }
            jsonPath("$.url") { value("https://github.com/sift/sift.git") }
            jsonPath("$.hasToken") { value(true) }
            jsonPath("$.secretName") { value("sift-repo-$id") }
            jsonPath("$.createdAt") { exists() }
            jsonPath("$.token") { doesNotExist() }
            content { string(not(containsString("ghp_token"))) }
        }
    }

    @Test
    fun `POST with invalid body yields problem detail 400 without calling the service`() {
        listOf(
            """{"name":"","url":"https://example.org/repo"}""",
            """{"name":"sift","url":""}""",
            """{"name":"${"a".repeat(101)}","url":"https://example.org/repo"}""",
            """{"url":"https://example.org/repo"}""",
            """{not json""",
        ).forEach { body ->
            mockMvc.post("/api/v1/repositories") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.status") { value(400) }
            }
        }
        verify(exactly = 0) { service.create(any(), any(), any()) }
    }

    @Test
    fun `domain exceptions map to 400 404 and 409 problem details`() {
        every { service.create(any(), any(), any()) } throws IllegalArgumentException("bad url")
        mockMvc.post("/api/v1/repositories") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"sift","url":"ftp://x"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("bad url") }
        }

        every { service.get(id) } throws NotFoundException("Repository $id not found")
        mockMvc.get("/api/v1/repositories/$id").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Repository $id not found") }
        }

        every { service.delete(id) } throws ConflictException("active runs")
        mockMvc.delete("/api/v1/repositories/$id").andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("active runs") }
        }
    }

    @Test
    fun `GET list and GET by id return responses`() {
        every { service.list() } returns listOf(repository)
        every { service.get(id) } returns repository

        mockMvc.get("/api/v1/repositories").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(id.toString()) }
            jsonPath("$[0].hasToken") { value(true) }
        }
        mockMvc.get("/api/v1/repositories/$id").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("sift") }
            jsonPath("$.updatedAt") { exists() }
        }
        mockMvc.get("/api/v1/repositories/not-a-uuid").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `PUT updates url token and clearToken and DELETE returns 204`() {
        every { service.update(id, "https://example.org/moved", "rotated", false) } returns repository
        mockMvc.put("/api/v1/repositories/$id") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.org/moved","token":"rotated"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(id.toString()) }
        }

        every { service.update(id, null, null, true) } returns repository
        mockMvc.put("/api/v1/repositories/$id") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"clearToken":true}"""
        }.andExpect { status { isOk() } }
        verify(exactly = 1) { service.update(id, null, null, true) }

        justRun { service.delete(id) }
        mockMvc.delete("/api/v1/repositories/$id").andExpect {
            status { isNoContent() }
            content { string("") }
        }
        verify(exactly = 1) { service.delete(id) }
    }
}
