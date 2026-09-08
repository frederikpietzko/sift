package org.sift.server.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.sift.server.users.User
import org.sift.server.users.UserService
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Runs right after the bearer token has been authenticated and upserts the caller into `users`, exposing the
 * resulting [User] as the [CURRENT_USER_ATTRIBUTE] request attribute for [CurrentUserArgumentResolver].
 * Anonymous requests (open probes, discovery endpoint) pass through untouched.
 */
class UserProvisioningFilter(private val users: UserService) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication is JwtAuthenticationToken) {
            request.setAttribute(CURRENT_USER_ATTRIBUTE, users.provision(authentication.token))
        }
        chain.doFilter(request, response)
    }

    companion object {
        val CURRENT_USER_ATTRIBUTE: String = "${UserProvisioningFilter::class.qualifiedName}.CURRENT_USER"

        /** The provisioned caller of [request], or `null` when the request was not authenticated with a JWT. */
        fun currentUser(request: HttpServletRequest): User? = request.getAttribute(CURRENT_USER_ATTRIBUTE) as User?
    }
}
