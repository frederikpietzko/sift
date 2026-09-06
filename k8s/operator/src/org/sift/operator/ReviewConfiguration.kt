package org.sift.operator

import org.sift.crds.CodeReview
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

@Component
class ReviewConfiguration(private val properties: OperatorProperties) {
    fun yaml(review: CodeReview): String {
        ReviewExecution.validate(review)
        val execution = ReviewExecution(review)
        val spec = review.spec
        val services = properties.services
        val values = linkedMapOf<String, Any>(
            "sift.review.repository-url" to spec.repositoryUrl,
            "sift.review.branch" to spec.branch,
            "sift.review.base-branch" to spec.baseBranch,
            "sift.review.commit-sha" to spec.commitSha,
            "sift.review.execution-id" to execution.executionId,
            "spring.rabbitmq.host" to services.rabbitmqHost,
            "spring.rabbitmq.port" to services.rabbitmqPort,
            "spring.rabbitmq.username" to services.rabbitmqUsername,
            "spring.rabbitmq.virtual-host" to services.rabbitmqVirtualHost,
        )
        spec.pullRequest?.let { values["sift.review.pull-request"] = it }
        services.modelBaseUrl?.let {
            values["spring.ai.openai.base-url"] = it.replace("{proxyToken}", "\${SIFT_MODEL_PROXY_TOKEN}")
        }
        services.model?.let { values["spring.ai.openai.chat.options.model"] = it }
        services.searxngUrl?.let {
            values["sift.tools.web-search.base-url"] = it
            values["sift.tools.web-search.enabled"] = true
        }
        val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
        return Yaml(options).dump(values)
    }
}
