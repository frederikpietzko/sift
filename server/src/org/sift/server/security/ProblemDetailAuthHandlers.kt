package org.sift.server.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import tools.jackson.databind.json.JsonMapper
import java.net.URI

/**
 * Security failures happen in the filter chain, before any `@RestControllerAdvice` can run, so the RFC 7807 shape
 * `ApiExceptionHandler` produces for domain errors is reproduced here. The bearer-token entry point is delegated to
 * for the `WWW-Authenticate` header (RFC 6750 `error`/`error_description` attributes), then the body is written.
 * Both handlers are registered as beans by [SecurityConfiguration].
 */
class ProblemDetailAuthenticationEntryPoint(private val mapper: JsonMapper) : AuthenticationEntryPoint {
    private val bearer = BearerTokenAuthenticationEntryPoint()

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        bearer.commence(request, response, authException)
        response.writeProblem(mapper, request, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_DETAIL)
    }
}

class ProblemDetailAccessDeniedHandler(private val mapper: JsonMapper) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        response.writeProblem(mapper, request, HttpStatus.FORBIDDEN, FORBIDDEN_DETAIL)
    }
}

private const val UNAUTHORIZED_DETAIL = "Authentication is required to access this resource"
private const val FORBIDDEN_DETAIL = "Access to this resource is denied"

private fun HttpServletResponse.writeProblem(
    mapper: JsonMapper,
    request: HttpServletRequest,
    status: HttpStatus,
    detail: String,
) {
    val problem = ProblemDetail.forStatusAndDetail(status, detail).apply {
        title = status.reasonPhrase
        instance = URI.create(request.requestURI)
    }
    setStatus(status.value())
    setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    characterEncoding = Charsets.UTF_8.name()
    writer.write(mapper.writeValueAsString(problem.toBody()))
    writer.flush()
}

/** Flat RFC 7807 members, matching what Spring MVC writes for [ProblemDetail] (no nested `properties`). */
private fun ProblemDetail.toBody(): Map<String, Any?> = mapOf(
    "type" to type.toString(),
    "title" to title,
    "status" to status,
    "detail" to detail,
    "instance" to instance?.toString(),
)
