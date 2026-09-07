package org.sift.server.config

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class KubernetesConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun kubernetesClient(): KubernetesClient = KubernetesClientBuilder().build()
}
