package org.sift.server.api

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.web.method.HandlerMethod

/**
 * Shapes the generated OpenAPI description (`GET /v3/api-docs[.yaml]`) that is committed as `api/openapi.yaml`
 * and consumed by the web client and IDE plugins. Every operation is protected by the `bearerAuth` JWT scheme
 * unless it opts out with `@SecurityRequirements` (the anonymous auth discovery endpoint). Operation ids are
 * derived from the handler rather than springdoc's `method_n` counters so generated client code stays stable
 * across refactorings: `<Resource><Method>` with the controller's `Controller` suffix stripped, e.g.
 * `AgentRunController.list` becomes `agentRunList`.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
    @Bean
    fun siftOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Sift Server API")
                .version("v1")
                .description(
                    "REST and Server-Sent Events API of the Sift server. Errors are RFC 9457 problem details " +
                        "(`application/problem+json`).",
                ),
        )
        .components(
            Components().addSecuritySchemes(
                BEARER_AUTH,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Access token issued by the OIDC provider announced by `GET /api/v1/auth/config`."),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER_AUTH))

    /** Registers the shared [ProblemDetail] schema every error response refers to. */
    @Bean
    fun problemDetailSchemaCustomizer(): OpenApiCustomizer = OpenApiCustomizer { api ->
        val resolved = ModelConverters.getInstance().resolveAsResolvedSchema(AnnotatedType(ProblemDetail::class.java))
        api.components.addSchemas(PROBLEM_DETAIL, resolved.schema.also { it.addType("object") })
        resolved.referencedSchemas.forEach { (key, value) -> api.components.addSchemas(key, value) }
    }

    /**
     * Stable operation ids and uniform error documentation: every response with a 4xx/5xx status renders as an
     * `application/problem+json` [ProblemDetail], and every secured operation additionally documents the `401`
     * the resource server produces for missing or invalid tokens.
     */
    @Bean
    fun operationCustomizer(): OperationCustomizer = OperationCustomizer { operation, handlerMethod ->
        operation.operationId = operationId(handlerMethod)
        if (operation.security?.isEmpty() != true) {
            val unauthorized = ApiResponse().description("Missing or invalid bearer token")
            operation.responses.addApiResponse(UNAUTHORIZED, unauthorized)
        }
        operation.responses
            .filterKeys { code -> code.toIntOrNull()?.let { it >= HttpStatus.BAD_REQUEST.value() } == true }
            .values.forEach { response -> response.content = problemContent() }
        operation
    }

    private fun problemContent(): Content = Content().addMediaType(
        MediaType.APPLICATION_PROBLEM_JSON_VALUE,
        io.swagger.v3.oas.models.media.MediaType().schema(Schema<Any>().`$ref`(PROBLEM_DETAIL)),
    )

    companion object {
        const val BEARER_AUTH = "bearerAuth"
        const val PROBLEM_DETAIL = "ProblemDetail"
        private val UNAUTHORIZED = HttpStatus.UNAUTHORIZED.value().toString()

        fun operationId(handler: HandlerMethod): String {
            val resource = handler.beanType.simpleName.removeSuffix("Controller").replaceFirstChar(Char::lowercaseChar)
            return resource + handler.method.name.replaceFirstChar(Char::uppercaseChar)
        }
    }
}
