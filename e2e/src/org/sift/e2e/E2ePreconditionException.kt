package org.sift.e2e

/**
 * Raised when the e2e harness cannot proceed because a prerequisite is missing
 * (tool not installed, credentials unset, external command failed). The message is
 * meant to be actionable for the developer running the suite and never contains secrets.
 */
class E2ePreconditionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
