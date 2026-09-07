package org.sift.server.config

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import kotlin.coroutines.CoroutineContext

/**
 * Application-wide [CoroutineScope] for long-running background work (e.g. the Postgres notification
 * listener). A [SupervisorJob] keeps one failing child from taking the others down; the scope is cancelled
 * when the application context closes, which cancels every coroutine launched in it.
 */
@Component
class ApplicationCoroutineScope(ioDispatcher: CoroutineDispatcher) : CoroutineScope, DisposableBean {
    override val coroutineContext: CoroutineContext = SupervisorJob() + ioDispatcher + CoroutineName("sift-server")

    override fun destroy() = cancel("Application context is closing")
}
