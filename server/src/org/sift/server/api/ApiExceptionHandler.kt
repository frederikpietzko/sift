package org.sift.server.api

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Maps domain exceptions to RFC 7807 problem details. Extends [ResponseEntityExceptionHandler] so the
 * framework's own MVC exceptions keep their specific status codes instead of falling into the 500 fallback.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(exception: IllegalArgumentException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, exception.message)

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(exception: NoSuchElementException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, exception.message)

    @ExceptionHandler(IllegalStateException::class)
    fun conflict(exception: IllegalStateException): ProblemDetail =
        problem(HttpStatus.CONFLICT, exception.message)

    @ExceptionHandler(Exception::class)
    fun internalError(exception: Exception): ProblemDetail {
        log.error("Unhandled exception while processing request", exception)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred")
    }

    private fun problem(status: HttpStatus, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase).apply {
            title = status.reasonPhrase
        }
}
