/*
* Author: Jamius Siam
* Since: 15/11/2026
*/

package dev.jamius.iris.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

class BackupTest {

    private val password = "correct horse".toCharArray()
    private val plain = "payload { \"a\": 1 }".encodeToByteArray()

    private fun encrypt(bytes: ByteArray = plain, pass: CharArray = password, iterations: Int = FAST) =
        encryptBackup(bytes, pass.copyOf(), iterations)

    /** Runs [block], expecting a [BackupException] with [message]; returns it. */
    private fun expectFailure(message: String, block: () -> Unit): BackupException {
        try {
            block()
        } catch (e: BackupException) {
            assertEquals(message, e.message)
            return e
        }
        fail("Expected BackupException: $message")
        error("unreachable")
    }

    // Crypto

    @Test
    fun encryptThenDecryptRoundTrips() {
        val file = encrypt()

        assertArrayEquals("IRISBKUP".toByteArray(Charsets.US_ASCII), file.copyOfRange(0, 8))
        assertEquals(1, file[8].toInt())
        assertEquals(FAST, ByteBuffer.wrap(file, 9, 4).getInt())
        assertEquals(41 + plain.size + 16, file.size)
        assertArrayEquals(plain, decryptBackup(file, password.copyOf()))
    }

    @Test
    fun defaultIterationsAre600000() {
        val file = encryptBackup(plain, password.copyOf())

        assertEquals(600_000, ByteBuffer.wrap(file, 9, 4).getInt())
        assertArrayEquals(plain, decryptBackup(file, password.copyOf()))
    }

    @Test
    fun nonAsciiPasswordRoundTrips() {
        val pass = "pässwörd 🔑".toCharArray()

        assertArrayEquals(plain, decryptBackup(encrypt(pass = pass), pass.copyOf()))
    }

    @Test
    fun twoBackupsOfTheSamePayloadDiffer() {
        val a = encrypt()
        val b = encrypt()

        assertFalse(a.copyOfRange(13, 29).contentEquals(b.copyOfRange(13, 29))) // salt
        assertFalse(a.copyOfRange(29, 41).contentEquals(b.copyOfRange(29, 41))) // nonce
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun wrongPasswordFails() {
        val file = encrypt()

        expectFailure(WRONG_PASSWORD_OR_DAMAGED) { decryptBackup(file, "wrong".toCharArray()) }
    }

    @Test
    fun editedHeaderFails() {
        val salt = encrypt().also { it[13] = (it[13].toInt() xor 1).toByte() }
        expectFailure(WRONG_PASSWORD_OR_DAMAGED) { decryptBackup(salt, password.copyOf()) }

        // FAST + 1: still in range, so only the AAD tag can catch it.
        val iterations = encrypt().also { it[12] = (it[12].toInt() xor 1).toByte() }
        assertEquals(FAST + 1, ByteBuffer.wrap(iterations, 9, 4).getInt())
        expectFailure(WRONG_PASSWORD_OR_DAMAGED) { decryptBackup(iterations, password.copyOf()) }
    }

    @Test
    fun editedCiphertextFails() {
        val file = encrypt().also { it[45] = (it[45].toInt() xor 1).toByte() }

        expectFailure(WRONG_PASSWORD_OR_DAMAGED) { decryptBackup(file, password.copyOf()) }
    }

    @Test
    fun droppedLastByteFails() {
        val file = encrypt().let { it.copyOf(it.size - 1) }

        expectFailure(WRONG_PASSWORD_OR_DAMAGED) { decryptBackup(file, password.copyOf()) }
    }

    @Test
    fun tooShortFileIsNotABackup() {
        expectFailure(NOT_A_BACKUP) { decryptBackup(ByteArray(0), password.copyOf()) }
        expectFailure(NOT_A_BACKUP) { decryptBackup(encrypt(bytes = ByteArray(0)).copyOf(56), password.copyOf()) }
    }

    @Test
    fun wrongMagicIsNotABackup() {
        val file = encrypt().also { it[0] = 'X'.code.toByte() }

        expectFailure(NOT_A_BACKUP) { decryptBackup(file, password.copyOf()) }
    }

    @Test
    fun newerFormatVersionIsRejected() {
        val file = encrypt().also { it[8] = 2 }

        expectFailure(NEWER_VERSION) { decryptBackup(file, password.copyOf()) }
    }

    @Test
    fun zeroFormatVersionIsNotABackup() {
        val file = encrypt().also { it[8] = 0 }

        expectFailure(NOT_A_BACKUP) { decryptBackup(file, password.copyOf()) }
    }

    @Test
    fun outOfRangeIterationsAreRejected() {
        for (iterations in listOf(0, -1, Int.MAX_VALUE)) {
            val file = encrypt().also { ByteBuffer.wrap(it).putInt(9, iterations) }

            expectFailure(WRONG_PASSWORD_OR_DAMAGED) { decryptBackup(file, password.copyOf()) }
        }
    }

    // Mapping and validation

    private val prompts = listOf(CleanupPrompt(id = "p", name = "Formal", systemPrompt = "Make it \"formal\"."))
    private val providers = listOf(CustomCleanupProvider(id = "c1", name = "Groq", baseUrl = "https://x.example/v1"))

    private fun roundTrip(contents: BackupContents) = decodeBackup(encodeBackup(contents))

    /** A payload with the given settings entries (raw JSON) and secrets. */
    private fun payload(settings: String = "", secrets: String = "", version: Int = 1) =
        """{"version":$version,"settings":{$settings},"secrets":{$secrets}}""".encodeToByteArray()

    @Test
    fun contentsRoundTripAllTypes() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("stt_provider_id") to "deepgram",
            stringPreferencesKey("cleanup_reasoning_effort") to "",
            intPreferencesKey("panel_lines") to 5,
            booleanPreferencesKey("vad_enabled") to false,
            floatPreferencesKey("bubble_y_fraction") to 0.42f,
            stringPreferencesKey("cleanup_prompts") to encodePrompts(prompts),
            stringPreferencesKey("custom_cleanup_providers") to encodeCustomProviders(providers),
        )
        val secrets = mapOf(
            Secret.OPENAI_API_KEY to "sk-1",
            Secret.SONIOX_API_KEY to "sx-\"quoted\" ✓",
            Secret.customCleanupKey("c1") to "gsk_2",
        )

        val restored = roundTrip(BackupContents(prefs, secrets))

        assertEquals(prefs.asMap(), restored.prefs.asMap())
        assertEquals(secrets, restored.secrets)
        val settings = restored.prefs.toSettings(restored.secrets.keys)
        assertEquals(0.42f, settings.bubbleYFraction)
        assertEquals(5, settings.panelLines)
        assertEquals(prompts, settings.prompts)
        assertEquals(providers, settings.customCleanupProviders)
        assertEquals(setOf("c1"), settings.customCleanupKeyIds)
    }

    @Test
    fun missingPrefsStayMissing() {
        val empty = roundTrip(BackupContents(emptyPreferences(), emptyMap()))
        assertTrue(empty.prefs.asMap().isEmpty())
        assertTrue(empty.secrets.isEmpty())

        val missing = roundTrip(BackupContents(mutablePreferencesOf(), emptyMap()))
        val blank = roundTrip(
            BackupContents(mutablePreferencesOf(stringPreferencesKey("cleanup_reasoning_effort") to ""), emptyMap())
        )
        assertNull(missing.prefs[stringPreferencesKey("cleanup_reasoning_effort")])
        assertEquals("", blank.prefs[stringPreferencesKey("cleanup_reasoning_effort")])
        assertEquals("low", missing.prefs.toSettings(emptySet()).cleanupReasoningEffort)
        assertNull(blank.prefs.toSettings(emptySet()).cleanupReasoningEffort)
    }

    @Test
    fun unknownPrefKeysAreKept() {
        val restored = decodeBackup(payload(""""future_setting":{"type":"int","value":"7"}"""))

        assertEquals(7, restored.prefs[intPreferencesKey("future_setting")])
    }

    @Test
    fun orphanedCustomKeyIsDropped() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("custom_cleanup_providers") to encodeCustomProviders(providers)
        )
        val secrets = mapOf(
            Secret.customCleanupKey("c1") to "kept",
            Secret.customCleanupKey("gone") to "dropped",
            Secret.GATEWAY_API_KEY to "gw",
        )

        val restored = roundTrip(BackupContents(prefs, secrets))

        assertEquals(mapOf(Secret.customCleanupKey("c1") to "kept", Secret.GATEWAY_API_KEY to "gw"), restored.secrets)
    }

    @Test
    fun newerPayloadVersionIsRejected() {
        expectFailure(NEWER_VERSION) { decodeBackup(payload(version = 2)) }
        // Even when the newer payload no longer decodes as this version's.
        expectFailure(NEWER_VERSION) { decodeBackup("""{"version":2,"settings":[]}""".encodeToByteArray()) }
    }

    @Test
    fun missingSectionIsRejected() {
        expectFailure(DAMAGED) { decodeBackup("""{"version":1,"settings":{}}""".encodeToByteArray()) }
    }

    @Test
    fun fullPipelineRoundTrips() {
        val contents = BackupContents(
            mutablePreferencesOf(stringPreferencesKey("stt_provider_id") to "deepgram"),
            mapOf(Secret.OPENAI_API_KEY to "sk-1")
        )

        val file = encryptBackup(encodeBackup(contents), password.copyOf(), FAST)
        val restored = decodeBackup(decryptBackup(file, password.copyOf()))

        assertEquals(contents.prefs.asMap(), restored.prefs.asMap())
        assertEquals(contents.secrets, restored.secrets)
    }

    @Test
    fun malformedJsonIsRejectedWithoutLeakingIt() {
        val full = payload(secrets = """"openai_api_key":"sk-secret-123"""")
        val truncated = full.copyOf(full.size - 3)

        val e = expectFailure(DAMAGED) { decodeBackup(truncated) }

        assertNull(e.cause)
        assertFalse(e.message!!.contains("sk-secret-123"))
    }

    @Test
    fun unknownTypeIsRejected() {
        expectFailure(DAMAGED) { decodeBackup(payload(""""panel_lines":{"type":"long","value":"3"}""")) }
    }

    @Test
    fun unparsableValueIsRejected() {
        expectFailure(DAMAGED) { decodeBackup(payload(""""panel_lines":{"type":"int","value":"abc"}""")) }
        expectFailure(DAMAGED) { decodeBackup(payload(""""vad_enabled":{"type":"boolean","value":"yes"}""")) }
    }

    @Test
    fun blankNamesAreRejected() {
        expectFailure(DAMAGED) { decodeBackup(payload(""" " ":{"type":"string","value":"x"}""")) }
        expectFailure(DAMAGED) { decodeBackup(payload(secrets = """"":"x"""")) }
    }

    @Test
    fun wronglyTypedKnownKeyIsRejected() {
        expectFailure(DAMAGED) { decodeBackup(payload(""""panel_lines":{"type":"string","value":"3"}""")) }
    }

    @Test
    fun invalidBubbleSideIsRejected() {
        expectFailure(DAMAGED) { decodeBackup(payload(""""bubble_side":{"type":"string","value":"TOP"}""")) }
    }

    @Test
    fun malformedPromptsAreRejected() {
        expectFailure(DAMAGED) { decodeBackup(payload(""""cleanup_prompts":{"type":"string","value":"[{"}""")) }
    }

    @Test
    fun malformedCustomProvidersAreRejected() {
        expectFailure(DAMAGED) {
            decodeBackup(payload(""""custom_cleanup_providers":{"type":"string","value":"{\"id\":1}"}"""))
        }
    }

    private companion object {
        const val FAST = 1_000
    }
}
