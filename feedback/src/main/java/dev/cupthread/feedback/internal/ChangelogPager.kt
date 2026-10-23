package dev.cupthread.feedback.internal

import dev.cupthread.feedback.ChangelogEntry
import dev.cupthread.feedback.ListChangelogResult

/**
 * Sequential keyset pager over `GET /api/v1/public/apps/{appKey}/changelog`.
 *
 * Pages advance by keyset cursor returned by the API. The pager never requests
 * another page after the API reported the last one, and treats an empty page as
 * terminal even if a server wrongly reports `hasMore = true`.
 */
internal class ChangelogPager(
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val fetchPage: suspend (limit: Int, cursor: String?) -> ListChangelogResult
) {
    /** Whether a page after the ones already loaded exists. */
    var hasMore: Boolean = false
        private set

    private var cursor: String? = null
    private var started = false
    private var generation = 0

    /** Forgets loaded pages so the next [loadFirstPage] starts from the beginning again. */
    fun reset() {
        generation++
        hasMore = false
        cursor = null
        started = false
    }

    /** Loads the first page from a clean state, replacing any previous paging position. */
    suspend fun loadFirstPage(): ListChangelogResult {
        reset()
        return fetch(discardIfReset = false)!!
    }

    /** Loads the page after the ones already loaded, or returns `null` when the list is exhausted. */
    suspend fun loadNextPage(): ListChangelogResult? {
        if (started && !hasMore) return null
        return fetch(discardIfReset = true)
    }

    private suspend fun fetch(discardIfReset: Boolean): ListChangelogResult? {
        val requestGeneration = generation
        val page = fetchPage(pageSize, cursor)
        if (discardIfReset && requestGeneration != generation) return null
        started = true
        cursor = page.nextCursor
        hasMore = page.hasMore && page.entries.isNotEmpty()
        return page
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 100

        /**
         * Appends [page] to [existing], skipping IDs that are already present.
         */
        fun append(
            existing: List<ChangelogEntry>,
            page: List<ChangelogEntry>
        ): List<ChangelogEntry> {
            if (page.isEmpty()) return existing
            val seen = existing.mapTo(HashSet(existing.size + page.size)) { it.id }
            val appended = page.filter { seen.add(it.id) }
            return if (appended.isEmpty()) existing else existing + appended
        }
    }
}
