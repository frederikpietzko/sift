package org.sift.server.users

import jakarta.servlet.http.HttpServletRequest
import org.sift.server.users.User
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.core.MethodParameter
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Injects the [User] provisioned by [UserProvisioningFilter] into a controller method:
 * `fun create(@CurrentUser user: User)`. Only meaningful on endpoints that require authentication.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUser

class CurrentUserArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUser::class.java) &&
            User::class.java.isAssignableFrom(parameter.parameterType)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): User {
        val request = checkNotNull(webRequest.getNativeRequest(HttpServletRequest::class.java)) {
            "@CurrentUser is only supported for servlet requests"
        }
        return UserProvisioningFilter.currentUser(request)
            ?: throw AuthenticationCredentialsNotFoundException("No authenticated user is bound to this request")
    }
}

/**
 * Picked up by the MVC slice and the full application alike, so `@CurrentUser` works wherever MVC runs. The
 * parameter is resolved from the bearer token, so springdoc must not document it as a query parameter.
 */
@Component
class CurrentUserWebMvcConfigurer : WebMvcConfigurer {
    init {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUser::class.java)
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(CurrentUserArgumentResolver())
    }
}
