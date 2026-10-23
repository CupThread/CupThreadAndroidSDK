package dev.cupthread.feedback.internal

import dev.cupthread.feedback.FeatureRequestItem
import dev.cupthread.feedback.ListFeatureRequestsResult

/**
 * One page request issued by [FeatureRequestPager].
 *
 * @property limit Maximum number of rows the API may return.
 * @property offset Number of rows to skip when [cursor] is absent.
 * @property cursor Opaque keyset cursor from the previous page, or `null` for offset paging.
 */
internal data class FeatureRequestPageRequest(
    val limit: Int,
    val offset: Int,
    val cursor: String?
)

/**
 * Sequential pager over `GET /api/v1/feature-requests`.
 *
 * The caller supplies [fetchPage], which carries the current search and version filter; call
 * [reset] whenever that filter changes so paging restarts from the first page. Pages advance by
 * keyset cursor when the server returns one and by offset otherwise, which keeps older deployments
 * working.
 *
 * The pager never requests another page after the API reported the last one, and it treats an empty
 * page as terminal even if a server wrongly reports `hasMore = true`.
 */
internal class FeatureRequestPager(
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val fetchPage: suspend (FeatureRequestPageRequest) -> ListFeatureRequestsResult
) {
    /** Total number of matching requests reported by the last page. */
    var total: Int = 0
        private set

    /** Whether a page after the ones already loaded exists. */
    var hasMore: Boolean = false
        private set

    private var offset = 0
    private var cursor: String? = null
    private var started = false
    private var generation = 0

    /** Forgets loaded pages so the next [loadFirstPage] starts from the beginning again. */
    fun reset() {
        generation++
        total = 0
        hasMore = false
        offset = 0
        cursor = null
        started = false
    }

    /** Loads the first page from a clean state, replacing any previous paging position. */
    suspend fun loadFirstPage(): ListFeatureRequestsResult {
        reset()
        return fetch(discardIfReset = false)!!
    }

    /** Loads the page after the ones already loaded, or returns `null` when the list is exhausted. */
    suspend fun loadNextPage(): ListFeatureRequestsResult? {
        if (started && !hasMore) return null
        return fetch(discardIfReset = true)
    }

    private suspend fun fetch(discardIfReset: Boolean): ListFeatureRequestsResult? {
        val requestGeneration = generation
        val page = fetchPage(FeatureRequestPageRequest(limit = pageSize, offset = offset, cursor = cursor))
        if (discardIfReset && requestGeneration != generation) return null
        started = true
        total = page.total
        cursor = page.nextCursor
        offset += page.requests.size
        hasMore = page.hasMore && page.requests.isNotEmpty()
        return page
    }

    companion object {
        /** Page size used by the bundled screens; matches the API default. */
        const val DEFAULT_PAGE_SIZE = 50

        /**
         * Appends [page] to [existing], skipping ids that are already present.
         *
         * Rows can shift between offset pages when new requests are created while the user pages, so
         * de-duplicating by id keeps list keys stable. Existing objects win, which preserves
         * optimistic vote state applied between page loads.
         */
        fun append(
            existing: List<FeatureRequestItem>,
            page: List<FeatureRequestItem>
        ): List<FeatureRequestItem> {
            if (page.isEmpty()) return existing
            val seen = existing.mapTo(HashSet(existing.size + page.size)) { it.id }
            val appended = page.filter { seen.add(it.id) }
            return if (appended.isEmpty()) existing else existing + appended
        }
    }
}
