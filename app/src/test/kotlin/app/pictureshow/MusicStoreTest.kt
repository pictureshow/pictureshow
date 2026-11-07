/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.control.QueueEntry
import app.pictureshow.core.control.QueueState
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A saved queue the app can't read is no queue at all, never a crash or another viewer's rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class MusicStoreTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val account = Account(Server("s", "Test", "https://media.example.test/", "12"), User("v", "Viewer"), "token")
    private val store get() = MusicStore(context)

    private val file: File
        get() {
            val digest = MessageDigest.getInstance("SHA-256").digest(account.key.toByteArray())
            val name = digest.joinToString("") { "%02x".format(it) }.take(32)
            return File(File(context.filesDir, "music"), "$name.json")
        }

    private val saved = MusicSnapshot(
        QueueState("s", "v", entries = listOf(QueueEntry("e1", "a"), QueueEntry("e2", "b")), current = "e1"),
        context = "album",
        contextName = "Album",
        sequence = 2,
        tracks = listOf(MusicTrack("a", "Title a"), MusicTrack("b", "Title b"))
    )

    private fun savedText(): String {
        assertTrue(runBlocking { store.save(account, saved) })
        return file.readText()
    }

    @After fun clean() {
        File(context.filesDir, "music").deleteRecursively()
    }

    @Test fun aSavedQueueReadsBack() {
        savedText()
        assertEquals(saved, runBlocking { store.load(account) })
    }

    @Test fun aCorruptFileIsNoQueue() {
        savedText()
        file.writeText("{this is not a queue")
        assertNull(runBlocking { store.load(account) })
    }

    @Test fun aTruncatedFileIsNoQueue() {
        val text = savedText()
        file.writeText(text.take(text.length / 2))
        assertNull(runBlocking { store.load(account) })
    }

    @Test fun anEmptyFileIsNoQueue() {
        savedText()
        file.writeText("")
        assertNull(runBlocking { store.load(account) })
    }

    /** A queue written by a later version, with fields this one doesn't know, still restores what it does know. */
    @Test fun unknownKeysAreIgnored() {
        val text = savedText()
        val newer = "{\"version\":7,\"future\":{\"x\":1}," + text.removePrefix("{")
            .replaceFirst("\"server\":", "\"later\":[1,2],\"server\":")
        file.writeText(newer)
        assertEquals(saved, runBlocking { store.load(account) })
    }

    /** Removing a viewer removes their files; another viewer's stay. */
    @Test fun retainKeepsOnlyTheViewersStillSaved() {
        savedText()
        val other = account.copy(user = User("w", "Other"))
        runBlocking { store.save(other, saved.copy(queue = saved.queue.copy(viewer = "w"))) }
        runBlocking { store.savePosition(account, MusicPosition("e1", 10)) }
        runBlocking { store.retain(listOf(other)) }
        assertNull(runBlocking { store.load(account) })
        assertEquals("w", runBlocking { store.load(other) }!!.queue.viewer)
        assertTrue(File(context.filesDir, "music").listFiles()!!.none { it.name.startsWith(file.name.substringBefore('.')) })
    }
}
