package org.sift.server.users.web

import org.sift.server.users.CurrentUser
import org.sift.server.users.User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Profile of the caller as provisioned from its bearer token; lets the web client show who is logged in. */
@RestController
@RequestMapping("/api/v1/me")
class MeController {
    @GetMapping
    fun me(@CurrentUser user: User): UserResponse = UserResponse.from(user)
}
