package org.sift.operator

import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.javaoperatorsdk.operator.api.reconciler.Context
import org.sift.crds.CodeReview
import org.springframework.stereotype.Component

@Component
class ReviewJobDependent(private val resources: ReviewResources) : ImmutableReviewDependent<Job>(Job::class.java) {
    override fun desired(primary: CodeReview, context: Context<CodeReview>): Job = resources.job(primary)
}
