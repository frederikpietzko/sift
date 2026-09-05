package org.sift.agents.review

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Main

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<Main>(*args).close()
}
