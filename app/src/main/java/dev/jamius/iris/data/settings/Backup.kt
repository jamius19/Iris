/*
* Author: Jamius Siam
* Since: 15/11/2026
*/

package dev.jamius.iris.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A backup or restore failure with a user-readable message. [cause] only for errors not derived from the file. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What a backup holds: the raw settings prefs and the decrypted API keys. */
internal data class BackupContents(val prefs: Preferences, val secrets: Map<Secret, String>)

internal const val BACKUP_KDF_ITERATIONS = 600_000

internal const val NOT_A_BACKUP = "This isn't an Iris backup file."
internal const val NEWER_VERSION = "This backup is from a newer version of Iris. Update the app, then try again."
internal const val WRONG_PASSWORD_OR_DAMAGED = "Wrong password, or the file is damaged."
internal const val DAMAGED = "This backup is damaged and can't be restored."
internal const val KEY_UNREADABLE = "Couldn't read a saved API key. Enter it again, then try again."

/** Payload JSON bytes of [contents]. */
internal fun encodeBackup(contents: BackupContents): ByteArray {
    val settings = contents.prefs.asMap().entries.associate { (key, value) ->
        key.name to when (value) {
            is String -> TypedValue(TYPE_STRING, value)
            is Int -> TypedValue(TYPE_INT, value.toString())
            is Boolean -> TypedValue(TYPE_BOOLEAN, value.toString())
            is Float -> TypedValue(TYPE_FLOAT, value.toString())
            else -> error("Unsupported pref type: ${value::class.java.simpleName}")
        }
    }
    val secrets = contents.secrets.mapKeys { it.key.prefKey.name }
    return json.encodeToString(Payload(PAYLOAD_VERSION, settings, secrets)).encodeToByteArray()
}

/** Parses and validates a payload; throws [BackupException] with no cause (JSON errors quote the input). */
internal fun decodeBackup(payload: ByteArray): BackupContents {
    val text = payload.decodeToString()
    // The version first: a newer payload may not decode as this one.
    val version = try {
        json.decodeFromString<PayloadVersion>(text).version
    } catch (e: IllegalArgumentException) {
        throw BackupException(DAMAGED)
    }
    if (version > PAYLOAD_VERSION) throw BackupException(NEWER_VERSION)
    val decoded = try {
        json.decodeFromString<Payload>(text)
    } catch (e: IllegalArgumentException) {
        throw BackupException(DAMAGED)
    }

    // Unknown keys are kept: nothing reads them, and dropping them would need a registry of every key.
    val prefs = mutablePreferencesOf()
    for ((name, typed) in decoded.settings) {
        if (name.isBlank()) throw BackupException(DAMAGED)
        val value = typed.value
        when (typed.type) {
            TYPE_STRING -> prefs[stringPreferencesKey(name)] = value
            TYPE_INT -> prefs[intPreferencesKey(name)] = value.toIntOrNull() ?: throw BackupException(DAMAGED)
            TYPE_BOOLEAN -> prefs[booleanPreferencesKey(name)] =
                value.toBooleanStrictOrNull() ?: throw BackupException(DAMAGED)
            TYPE_FLOAT -> prefs[floatPreferencesKey(name)] = value.toFloatOrNull() ?: throw BackupException(DAMAGED)
            else -> throw BackupException(DAMAGED)
        }
    }
    // Whatever would crash reading the settings (a bad enum, malformed JSON, a wrongly typed known key) is rejected
    // here, before anything is written.
    try {
        prefs.toSettings(emptySet())
    } catch (e: Exception) {
        throw BackupException(DAMAGED)
    }

    // A custom provider's key without its provider would be orphaned.
    val providerIds = prefs.customCleanupProviders().mapTo(HashSet()) { it.id }
    val customPrefix = Secret.customCleanupKey("").prefKey.name
    val secrets = buildMap {
        for ((name, value) in decoded.secrets) {
            if (name.isBlank()) throw BackupException(DAMAGED)
            if (name.startsWith(customPrefix) && name.removePrefix(customPrefix) !in providerIds) continue
            put(Secret(stringPreferencesKey(name)), value)
        }
    }
    return BackupContents(prefs, secrets)
}

/**
 * The file around [plain]: magic, format version, iterations, salt and nonce (the header, authenticated as AAD), then
 * AES-256-GCM with a PBKDF2-SHA256 key from [password]. [iterations] is a parameter only so tests run fast.
 */
internal fun encryptBackup(plain: ByteArray, password: CharArray, iterations: Int = BACKUP_KDF_ITERATIONS): ByteArray {
    val random = SecureRandom()
    val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
    val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
    val header = ByteBuffer.allocate(HEADER_SIZE)
        .put(MAGIC)
        .put(FORMAT_VERSION.toByte())
        .putInt(iterations)
        .put(salt)
        .put(nonce)
        .array()
    val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iterations, nonce)
    cipher.updateAAD(header)
    return header + cipher.doFinal(plain)
}

/** [file]'s payload bytes; throws [BackupException] (no cause) for a non-backup, newer, damaged or wrong password. */
internal fun decryptBackup(file: ByteArray, password: CharArray): ByteArray {
    if (file.size < HEADER_SIZE + TAG_SIZE || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
        throw BackupException(NOT_A_BACKUP)
    }
    val header = ByteBuffer.wrap(file, MAGIC.size, HEADER_SIZE - MAGIC.size)
    val version = header.get().toInt() and 0xFF
    if (version > FORMAT_VERSION) throw BackupException(NEWER_VERSION)
    if (version == 0) throw BackupException(NOT_A_BACKUP)
    // Bounded, so a crafted value can't hang the key derivation.
    val iterations = header.getInt()
    if (iterations !in 1..MAX_ITERATIONS) throw BackupException(WRONG_PASSWORD_OR_DAMAGED)
    val salt = ByteArray(SALT_SIZE).also { header.get(it) }
    val nonce = ByteArray(NONCE_SIZE).also { header.get(it) }
    return try {
        val cipher = cipher(Cipher.DECRYPT_MODE, password, salt, iterations, nonce)
        cipher.updateAAD(file, 0, HEADER_SIZE)
        cipher.doFinal(file, HEADER_SIZE, file.size - HEADER_SIZE)
    } catch (e: GeneralSecurityException) {
        throw BackupException(WRONG_PASSWORD_OR_DAMAGED)
    }
}

private fun cipher(mode: Int, password: CharArray, salt: ByteArray, iterations: Int, nonce: ByteArray): Cipher {
    val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
    val encoded = try {
        SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
    val key = SecretKeySpec(encoded, "AES") // Copies the bytes.
    encoded.fill(0)
    return Cipher.getInstance(TRANSFORMATION).apply { init(mode, key, GCMParameterSpec(TAG_SIZE * 8, nonce)) }
}

@Serializable
private class Payload(val version: Int, val settings: Map<String, TypedValue>, val secrets: Map<String, String>)

@Serializable
private class PayloadVersion(val version: Int)

/** A pref value as a string, parsed by [type] (one of the `TYPE_` names). */
@Serializable
private class TypedValue(val type: String, val value: String)

private val json = Json { ignoreUnknownKeys = true }

private val MAGIC = "IRISBKUP".toByteArray(Charsets.US_ASCII)
private const val FORMAT_VERSION = 1
private const val PAYLOAD_VERSION = 1
private const val SALT_SIZE = 16
private const val NONCE_SIZE = 12
private const val TAG_SIZE = 16
private const val HEADER_SIZE = 41 // magic 8, version 1, iterations 4, salt 16, nonce 12
private const val MAX_ITERATIONS = 10_000_000
private const val KEY_BITS = 256
private const val KDF = "PBKDF2WithHmacSHA256"
private const val TRANSFORMATION = "AES/GCM/NoPadding"

private const val TYPE_STRING = "string"
private const val TYPE_INT = "int"
private const val TYPE_BOOLEAN = "boolean"
private const val TYPE_FLOAT = "float"
