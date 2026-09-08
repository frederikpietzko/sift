package org.sift.server.security

import org.sift.server.users.UserProvisioningFilter
import org.sift.server.users.UserService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import tools.jackson.databind.json.JsonMapper

/**
 * OAuth2 resource server: every request carries a JWT bearer token issued by the configured provider
 * (`spring.security.oauth2.resourceserver.jwt.*`), so there is no session, no CSRF surface and no login form.
 * Only the health/info probes, the anonymous auth discovery endpoint the web client bootstraps from and the OpenAPI
 * description (`/v3/api-docs`, the contract clients are generated from) stay open.
 * Every authenticated caller is upserted into `users` by [UserProvisioningFilter] so controllers can inject it with
 * [CurrentUser]. Authorization is flat for now: any authenticated user may use the whole API (ADR 0016).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    fun authenticationEntryPoint(mapper: JsonMapper): ProblemDetailAuthenticationEntryPoint =
        ProblemDetailAuthenticationEntryPoint(mapper)

    @Bean
    fun accessDeniedHandler(mapper: JsonMapper): ProblemDetailAccessDeniedHandler =
        ProblemDetailAccessDeniedHandler(mapper)

    @Bean
    fun apiSecurityFilterChain(
        http: HttpSecurity,
        entryPoint: ProblemDetailAuthenticationEntryPoint,
        accessDeniedHandler: ProblemDetailAccessDeniedHandler,
        users: UserService,
    ): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                ANONYMOUS_GET_PATHS.forEach { path -> authorize(HttpMethod.GET, path, permitAll) }
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer {
                jwt { }
                authenticationEntryPoint = entryPoint
                this.accessDeniedHandler = accessDeniedHandler
            }
            exceptionHandling {
                authenticationEntryPoint = entryPoint
                this.accessDeniedHandler = accessDeniedHandler
            }
            addFilterAfter<BearerTokenAuthenticationFilter>(UserProvisioningFilter(users))
        }
        return http.build()
    }

    companion object {
        val ANONYMOUS_GET_PATHS: List<String> = listOf(
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            AuthConfigController.PATH,
            OPENAPI_PATH,
            "$OPENAPI_PATH/**",
            "$OPENAPI_PATH.yaml",
        )

        /** Matches `springdoc.api-docs.path`; the `.yaml` rendering and grouped sub-paths are permitted as well. */
        const val OPENAPI_PATH = "/v3/api-docs"
    }
}
