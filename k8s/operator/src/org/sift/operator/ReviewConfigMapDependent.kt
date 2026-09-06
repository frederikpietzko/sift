package org.sift.operator

import io.fabric8.kubernetes.api.model.ConfigMap
import io.javaoperatorsdk.operator.api.reconciler.Context
import org.sift.crds.CodeReview
import org.springframework.stereotype.Component

@Component
class ReviewConfigMapDependent(private val resources: ReviewResources) :
    ImmutableReviewDependent<ConfigMap>(ConfigMap::class.java) {
    override fun desired(primary: CodeReview, context: Context<CodeReview>): ConfigMap = resources.configMap(primary)
}
