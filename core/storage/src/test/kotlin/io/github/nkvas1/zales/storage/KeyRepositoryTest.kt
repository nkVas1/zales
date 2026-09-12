// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.storage

import io.github.nkvas1.zales.model.AccessKey
import io.github.nkvas1.zales.parsing.ParseFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.experimental.xor

class KeyRepositoryTest {

    @TempDir
    lateinit var dir: File

    /** Reversible, detectably-not-plaintext stand-in for the Keystore cipher. */
    private class XorCipher : BlobCipher {
        override fun encrypt(plaintext: ByteArray) = byteArrayOf(MAGIC) + plaintext.map { it xor MASK }.toByteArray()
        override fun decrypt(blob: ByteArray): ByteArray {
            if (blob.firstOrNull() != MAGIC) throw StorageUnavailableException("tampered")
            return blob.drop(1).map { it xor MASK }.toByteArray()
        }
        companion object {
            const val MAGIC: Byte = 0x5A
            const val MASK: Byte = 0x3C
        }
    }

    private var time = 1_000L
    private var nextId = 0

    private fun repo(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) = KeyRepository(
        file = File(dir, "keys.bin"),
        cipher = XorCipher(),
        clock = { time++ },
        newId = { "id-${nextId++}" },
        io = StandardTestDispatcher(scheduler),
    )

    private val trojan = "trojan://secret-password@a.example.com:443#Home"
    private val vless = "vless://secret-uuid@b.example.com:443?security=tls&sni=b.example.com#Work"

    @Test
    fun `first key becomes active and secrets never reach the summary`() = runTest {
        val repo = repo(testScheduler)
        val result = repo.add(trojan) as AddResult.Added
        assertEquals(listOf("id-0"), result.ids)

        val summary = repo.summaries().single()
        assertTrue(summary.isActive)
        assertEquals("Home", summary.descriptor.label)
        assertFalse("secret-password" in summary.toString())

        assertEquals("secret-password", (repo.activeKey() as AccessKey.Trojan).password)
    }

    @Test
    fun `the file on disk is not plaintext`() = runTest {
        repo(testScheduler).add(trojan)
        val raw = File(dir, "keys.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("secret-password" in raw)
    }

    @Test
    fun `adding the same key twice stores it once`() = runTest {
        val repo = repo(testScheduler)
        repo.add(trojan)
        val again = repo.add(trojan) as AddResult.Added
        assertEquals(0, again.ids.size)
        assertEquals(1, again.duplicates)
        assertEquals(1, repo.summaries().size)
    }

    @Test
    fun `a key just pasted is the key in use`() = runTest {
        // This used to keep the older key. It was defensible and it was wrong:
        // the person it matters to is someone whose key expired and who was
        // sent a new one. They paste it and expect it to work — not to go
        // looking for a list and press a button in the right row.
        val repo = repo(testScheduler)
        repo.add(trojan)
        repo.add(vless)
        assertEquals("Work", repo.summaries().first().descriptor.label)
    }

    @Test
    fun `an older key can be chosen again`() = runTest {
        val repo = repo(testScheduler)
        repo.add(trojan)
        repo.add(vless)

        assertTrue(repo.setActive("id-0"))
        assertEquals("Home", repo.summaries().first().descriptor.label)
        assertEquals("secret-password", (repo.activeKey() as AccessKey.Trojan).password)
    }

    @Test
    fun `removing the active key promotes the newest remaining one`() = runTest {
        val repo = repo(testScheduler)
        repo.add(trojan)
        repo.add(vless)
        repo.remove("id-0")
        val remaining = repo.summaries().single()
        assertEquals("Work", remaining.descriptor.label)
        assertTrue(remaining.isActive)
    }

    @Test
    fun `remote text asks to be fetched and rejected text explains why`() = runTest {
        val repo = repo(testScheduler)
        val fetch = repo.add("ssconf://keys.example.com/k#Outline") as AddResult.NeedsFetch
        assertEquals("https://keys.example.com/k", fetch.url)

        val rejected = repo.add("hello") as AddResult.Rejected
        assertEquals(ParseFailure.UNRECOGNIZED, rejected.reason)
        assertNull(repo.activeKey())
    }

    @Test
    fun `refreshing a remote source replaces its keys`() = runTest {
        val repo = repo(testScheduler)
        val url = "https://keys.example.com/k"
        repo.addRemote(url, """{"server":"1.2.3.4","server_port":443,"password":"old","method":"aes-256-gcm"}""")
        repo.addRemote(url, """{"server":"1.2.3.4","server_port":443,"password":"new","method":"aes-256-gcm"}""")

        assertEquals(1, repo.summaries().size)
        assertEquals("new", (repo.activeKey() as AccessKey.Shadowsocks).password)
        assertEquals(listOf(url), repo.remoteSources())
    }

    @Test
    fun `a tampered store surfaces as unavailable storage`() = runTest {
        val repo = repo(testScheduler)
        repo.add(trojan)
        File(dir, "keys.bin").writeBytes(byteArrayOf(1, 2, 3))
        val error = runCatching { repo.summaries() }.exceptionOrNull()
        assertTrue(error is StorageUnavailableException, "got $error")
    }

    @Test
    fun `many readers at once do not trip over the file lock`() {
        // Deliberately not runTest: the crash this guards against needs real
        // threads. A file lock keeps two processes apart but is not a mutex
        // within one — ask for a region the same JVM already holds and it
        // throws rather than waits. The home screen and the key screen ask at
        // the same moment every time they wake up together, and that took the
        // whole app down.
        val repo = KeyRepository(
            file = File(dir, "keys.bin"),
            cipher = XorCipher(),
            clock = { time++ },
            newId = { "id-${nextId++}" },
            io = Dispatchers.IO,
        )

        runBlocking {
            repo.add(vless)
            val readers = List(READERS) {
                async(Dispatchers.IO) {
                    repeat(ROUNDS) {
                        repo.summaries()
                        repo.activeKey()
                    }
                    true
                }
            }
            assertTrue(readers.awaitAll().all { it }, "every reader should have finished")
        }
    }
}

private const val READERS = 8
private const val ROUNDS = 12
