package org.sift.server

import org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration

@SpringBootApplication(
    proxyBeanMethods = false,
    exclude = [DataSourceTransactionManagerAutoConfiguration::class],
)
@ImportAutoConfiguration(ExposedAutoConfiguration::class)
@ConfigurationPropertiesScan
class Application
