package dev.cupthread.feedback.ui

import dev.cupthread.feedback.FeatureRequestComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit coverage for [replyParentId], the reply-targeting policy behind the detail sheet's
 * composer (issue #216):
 *
 * - The API allows single-level threading only — a reply must reference the top-level root of
 *   its thread, and the server answers HTTP 400 `invalid_parent` when a reply is nested under
 *   another reply.
 * - The pre-fix expression `replyTarget?.id ?: replyTarget?.parentId` was an inverted Elvis:
 *   [FeatureRequestComment.id] is non-nullable, so the right-hand side was unreachable and
 *   every reply to a reply sent the child's own ID as `parentId`.
 */
class ReplyParentIdTest {

    private fun comment(id: String, parentId: String? = null) = FeatureRequestComment(
        id = id,
        body = "comment body",
        parentId = parentId,
        createdAt = "2026-02-01T12:00:00.000Z"
    )

    @Test
    fun replyToTopLevelCommentTargetsTheCommentItself() {
        val root = comment(id = "c-root")
        assertEquals("c-root", replyParentId(root))
    }

    @Test
    fun replyToAReplyTargetsTheThreadRootNotTheReply() {
        val reply = comment(id = "c-reply-1", parentId = "c-root")
        assertEquals("c-root", replyParentId(reply))
    }

    @Test
    fun nestedThreadStillResolvesToTheTopLevelRoot() {
        val nested = comment(id = "c-reply-2", parentId = "c-root")
        assertEquals("c-root", replyParentId(nested))
    }

    @Test
    fun noReplyTargetProducesNoParentId() {
        assertNull(replyParentId(null))
    }
}
