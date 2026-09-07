package org.sift.server

import org.sift.server.api.ApiExceptionHandler
import org.sift.server.api.ConflictException
import org.sift.server.api.NotFoundException
import org.springframework.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiExceptionHandlerTest {
    private val handler = ApiExceptionHandler()

    @Test
    fun `maps domain exceptions to problem details`() {
        val badRequest = handler.badRequest(IllegalArgumentException("bad input"))
        assertEquals(HttpStatus.BAD_REQUEST.value(), badRequest.status)
        assertEquals("bad input", badRequest.detail)

        val notFound = handler.notFound(NotFoundException("repository missing"))
        assertEquals(HttpStatus.NOT_FOUND.value(), notFound.status)
        assertEquals("repository missing", notFound.detail)
        assertEquals("Not Found", notFound.title)

        val conflict = handler.conflict(ConflictException("already exists"))
        assertEquals(HttpStatus.CONFLICT.value(), conflict.status)
        assertEquals("already exists", conflict.detail)
    }

    @Test
    fun `hides internal error details`() {
        val problem = handler.internalError(RuntimeException("secret stack"))
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), problem.status)
        assertEquals("An unexpected error occurred", problem.detail)
    }
}
