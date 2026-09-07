package org.sift.server.config

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Single injection point for the blocking-work dispatcher; every other class receives it via its constructor. */
@Configuration(proxyBeanMethods = false)
class CoroutinesConfiguration {
    @Bean
    @Suppress("InjectDispatcher")
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
