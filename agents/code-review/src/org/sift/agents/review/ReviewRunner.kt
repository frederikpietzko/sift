package org.sift.agents.review

import org.sift.agents.shared.messaging.EventPublisher
import org.sift.events.CodeReviewCompletedEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Orchestrates a single code review run: checks out the repository, lets the [ReviewAgent]
 * review the change set, and publishes the resulting [CodeReviewCompletedEvent].
 *
 * Any exception thrown here aborts application startup, so `runApplication` rethrows it and the
 * JVM exits with a non-zero exit code. If publishing fails, the full event payload is logged as
 * JSON before rethrowing so the review result is not lost.
 */
@Component
class ReviewRunner(
    private val properties: ReviewProperties,
    private val gitCheckoutService: GitCheckoutService,
    private val reviewAgent: ReviewAgent,
    private val eventPublisher: EventPublisher,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        logger.info(
            "Starting code review for pull request {}: {} against {}",
            properties.pullRequest,
            properties.branch,
            properties.baseBranch,
        )
        logger.info("Checking out repository")
        val checkout = gitCheckoutService.checkout(properties)
        try {
            logger.info("Checkout complete; diff contains {} characters. Starting AI review", checkout.diff.length)
            val result = reviewAgent.review(checkout)
            logger.info("AI review complete: {} findings. Summary: {}", result.findings.size, result.summary)
            val event = reviewAgent.toEvent(properties, result)
            logger.info("Publishing code review result")
            publish(event)
            logger.info("Code review result published")
        } finally {
            logger.info("Cleaning up checkout")
            checkout.cleanup()
            logger.info("Checkout cleanup complete")
        }
    }

    private fun publish(event: CodeReviewCompletedEvent) {
        try {
            eventPublisher.publish(event)
        } catch (@Suppress("TooGenericExceptionCaught") exception: RuntimeException) {
            logger.error("Failed to publish code review result, payload: {}", toJson(event), exception)
            throw exception
        }
    }

    private fun toJson(event: CodeReviewCompletedEvent): String =
        runCatching { jsonMapper.writeValueAsString(event) }.getOrElse { event.toString() }

    companion object {
        private val logger = LoggerFactory.getLogger(ReviewRunner::class.java)
        private val jsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()
    }
}
