package com.metrolist.music.playback.queue

import com.metrolist.music.playback.queue.sync.QueueContent
import com.metrolist.music.playback.queue.sync.SyncJson
import com.metrolist.music.playback.queue.sync.SyncSong
import com.metrolist.music.playback.queue.sync.contentHash
import com.metrolist.music.playback.queue.sync.hash
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Row 201: a queue's original order (the order it was made in, songs added later on the end,
 * songs taken out gone) and "Restore original order".
 *
 * [Q] is a small stand-in for the player plus QueueBoard: the songs in real order (what shuffle
 * off plays, the player's media items), an optional shuffle order over them, and the original
 * order kept the way QueueBoard keeps it, reconciled after every edit.
 */
class QueueOriginalOrderTest {
    private class Q(songs: List<String>) {
        val real = songs.toMutableList()
        var shuffle: MutableList<Int>? = null // media indices in play order
        var original: List<String> = songs // set when the queue is made

        fun playOrder(): List<String> = shuffle?.map { real[it] } ?: real

        /** QueueBoard's saved form: play order plus, when shuffled, each song's real position. */
        fun saved(): Pair<List<String>, List<Int>?> =
            playOrder() to shuffle?.let { realPositionsOf(it) }

        fun edited() {
            val (play, order) = saved()
            original = reconcileOriginalOrder(original, realOrderOf(play, shuffle != null, order))
        }

        fun shuffleOn(order: List<Int>) {
            shuffle = order.toMutableList()
            edited()
        }

        fun shuffleOff() {
            shuffle = null
            edited()
        }

        /** A drag on the Queue page: moves the real order unshuffled, only the shuffle order shuffled. */
        fun drag(from: Int, to: Int) {
            val s = shuffle
            if (s == null) real.add(to, real.removeAt(from)) else s.add(to, s.removeAt(from))
            edited()
        }

        /** Play Next: right after the current song (media index [current]) in real and play order. */
        fun playNext(song: String, current: Int) {
            real.add(current + 1, song)
            shuffle = shuffle?.let { s ->
                val shifted = s.map { if (it > current) it + 1 else it }.toMutableList()
                shifted.add(shifted.indexOf(current) + 1, current + 1)
                shifted
            }
            edited()
        }

        /** Add to queue: the end of the real order and of the shuffle order. */
        fun add(song: String) {
            real.add(song)
            shuffle?.add(real.lastIndex)
            edited()
        }

        fun remove(song: String) {
            val i = real.indexOf(song)
            real.removeAt(i)
            shuffle = shuffle?.filter { it != i }?.map { if (it > i) it - 1 else it }?.toMutableList()
            edited()
        }

        /** What MusicService.restoreOriginalOrder() does to the player. */
        fun restore() {
            shuffle = null
            val target = reconcileOriginalOrder(original, real)
            val moves = reorderMoves(real, target)!!
            moves.forEach { (from, to) -> real.add(to, real.removeAt(from)) }
            edited()
        }
    }

    private val albums = listOf("a1", "a2", "a3", "b1", "b2", "b3")

    // ---- Before row 201 (the replication): shuffle off plays the real order, which drags and Play Next change.

    @Test
    fun `without an original order, shuffle off after an unshuffled drag is not the creation order`() {
        val q = Q(albums)
        q.shuffleOn(listOf(4, 0, 5, 2, 1, 3))
        q.drag(1, 4) // shuffled drag: shuffle order only
        q.shuffleOff()
        assertEquals(albums, q.real) // still fine here...
        q.drag(5, 0) // ...but an unshuffled drag moves the real order
        q.shuffleOn(listOf(0, 1, 2, 3, 4, 5).shuffled(java.util.Random(1)))
        q.shuffleOff()
        assertNotEquals(albums, q.real)
        assertEquals(listOf("b3", "a1", "a2", "a3", "b1", "b2"), q.real)
    }

    @Test
    fun `without an original order, Play Next while shuffled lands in the middle of the real order`() {
        val q = Q(albums)
        q.shuffleOn(listOf(1, 4, 0, 5, 2, 3)) // playing a2 (media index 1)
        q.playNext("x", current = 1)
        q.shuffleOff()
        assertEquals(listOf("a1", "a2", "x", "a3", "b1", "b2", "b3"), q.real)
    }

    // ---- The original order and restore.

    @Test
    fun `a new queue's original order is its songs as made`() {
        assertEquals(albums, Q(albums).original)
    }

    @Test
    fun `shuffling and drags leave the original order alone`() {
        val q = Q(albums)
        q.shuffleOn(listOf(4, 0, 5, 2, 1, 3))
        q.drag(0, 5)
        q.drag(3, 1)
        q.shuffleOff()
        q.drag(5, 0)
        q.drag(2, 4)
        assertEquals(albums, q.original)
    }

    @Test
    fun `songs added later go on the end of the original order wherever they were put`() {
        val q = Q(albums)
        q.shuffleOn(listOf(1, 4, 0, 5, 2, 3))
        q.playNext("x", current = 1) // into the middle of the real order
        q.add("y") // the end
        q.shuffleOff()
        q.playNext("z", current = 0) // unshuffled, second place
        assertEquals(albums + listOf("x", "y", "z"), q.original)
    }

    @Test
    fun `removed songs drop out of the original order and stay out`() {
        val q = Q(albums)
        q.remove("a2")
        q.shuffleOn(listOf(3, 0, 4, 1, 2))
        q.remove("b3")
        q.shuffleOff()
        q.restore()
        assertEquals(listOf("a1", "a3", "b1", "b2"), q.original)
        assertEquals(listOf("a1", "a3", "b1", "b2"), q.real)
    }

    @Test
    fun `restore after shuffle, add, drag and remove gives the creation order with additions at the end`() {
        val q = Q(albums)
        q.shuffleOn(listOf(2, 5, 0, 3, 1, 4))
        q.playNext("x", current = 2)
        q.drag(0, 3)
        q.remove("b1")
        q.shuffleOff()
        q.drag(4, 0)
        q.add("y")
        q.shuffleOn(listOf(0, 1, 2, 3, 4, 5, 6).shuffled(java.util.Random(7)))
        q.restore()
        assertNull(q.shuffle)
        assertEquals(listOf("a1", "a2", "a3", "b2", "b3", "x", "y"), q.real)
        assertEquals(q.real, q.original)
    }

    @Test
    fun `restore keeps the playing song, which is only moved`() {
        val q = Q(albums)
        q.shuffleOff()
        q.drag(0, 5) // a1 to the end
        val playing = q.real[2]
        q.restore()
        assertEquals(albums, q.real)
        assertEquals(1, q.real.count { it == playing })
    }

    @Test
    fun `a queue saved before row 201 takes its real order as the baseline`() {
        val real = listOf("c", "a", "b")
        assertEquals(real, reconcileOriginalOrder(null, real))
        // ...and from then on keeps it: a later drag does not move it.
        assertEquals(real, reconcileOriginalOrder(real, listOf("a", "b", "c")))
    }

    @Test
    fun `a song in the queue twice is matched occurrence by occurrence`() {
        assertEquals(listOf("a", "b", "a", "c"), reconcileOriginalOrder(listOf("a", "b", "a"), listOf("a", "c", "a", "b")))
        // One copy taken out: the later copy in the original order is the one that goes.
        assertEquals(listOf("a", "b"), reconcileOriginalOrder(listOf("a", "b", "a"), listOf("b", "a")))
        assertEquals(listOf(0, 1, 2), positionsInOrder(listOf("a", "a", "b"), listOf("a", "a", "b")))
        assertEquals(listOf(2, 0, 1), positionsInOrder(listOf("a", "a", "b"), listOf("b", "a", "a")))
    }

    @Test
    fun `reorder moves turn any order into the target, and nothing for an equal one`() {
        val target = listOf("a", "b", "c", "d", "e")
        val current = listOf("e", "c", "a", "d", "b")
        val list = current.toMutableList()
        reorderMoves(current, target)!!.forEach { (from, to) -> list.add(to, list.removeAt(from)) }
        assertEquals(target, list)
        assertEquals(emptyList<Pair<Int, Int>>(), reorderMoves(target, target))
        assertNull(reorderMoves(listOf("a", "b"), listOf("a", "c")))
        assertNull(nextReorderMove(target, target))
    }

    @Test
    fun `real order comes from the play order and the shuffle order`() {
        assertEquals(listOf("a", "b", "c"), realOrderOf(listOf("c", "a", "b"), true, listOf(2, 0, 1)))
        assertEquals(listOf("c", "a", "b"), realOrderOf(listOf("c", "a", "b"), false, listOf(2, 0, 1)))
        assertEquals(listOf("c", "a", "b"), realOrderOf(listOf("c", "a", "b"), true, listOf(0, 0, 1)))
    }

    // ---- Sync: hashes and older clients.

    private fun content(original: List<String>?) = QueueContent(
        title = "Mix",
        shuffled = true,
        songs = listOf("c", "a", "b").map { SyncSong(it) },
        shuffleOrder = listOf(2, 0, 1),
        originalOrder = original,
    )

    @Test
    fun `an original order equal to the real order leaves the hash as it was before row 201`() {
        val before = contentHash("Mix", null, true, listOf("c", "a", "b"), listOf(2, 0, 1))
        assertEquals(before, content(null).hash())
        assertEquals(before, content(listOf("a", "b", "c")).hash())
        assertNotEquals(before, content(listOf("b", "a", "c")).hash())
    }

    /** QueueContent as a client from before row 201 has it. */
    @Serializable
    private data class OldQueueContent(
        val title: String,
        val sourceKey: String? = null,
        val shuffled: Boolean = false,
        val songs: List<SyncSong>,
        val shuffleOrder: List<Int>? = null,
        val createdAt: Long = 0L,
    )

    @Test
    fun `older clients read the new content, and the new client reads theirs`() {
        val json = SyncJson.encodeToString(QueueContent.serializer(), content(listOf("b", "a", "c")))
        val old = SyncJson.decodeFromString(OldQueueContent.serializer(), json)
        assertEquals(listOf("c", "a", "b"), old.songs.map { it.id })
        val back = SyncJson.decodeFromString(QueueContent.serializer(), SyncJson.encodeToString(OldQueueContent.serializer(), old))
        assertNull(back.originalOrder)
        assertEquals(contentHash("Mix", null, true, listOf("c", "a", "b"), listOf(2, 0, 1)), back.hash())
    }
}
