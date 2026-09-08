package org.sift.server.api

/** One page of a listing; [page] is zero-based and [total] counts all matching rows, not just this page. */
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
)

/** Wire representation of a [Page] with the items mapped to their response DTOs. */
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
) {
    companion object {
        fun <T, R> from(page: Page<T>, transform: (T) -> R): PageResponse<R> = PageResponse(
            items = page.items.map(transform),
            page = page.page,
            size = page.size,
            total = page.total,
        )
    }
}
