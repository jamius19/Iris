/*
* Author: Jamius Siam
* Since: 18/11/2026
*/

package dev.jamius.iris.dictation

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jamius.iris.data.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** A delivered dictation: [raw] the provider's transcript, [finalText] what was delivered, null when equal to [raw]. */
@Serializable
data class HistoryEntry(val id: Int, val timestampMs: Long, val raw: String, val finalText: String? = null)

/**
 * Dictation history for the History page, newest first. Plain text in app-private storage: never logged, not in
 * Backup or Auto Backup. Writes run on [scope] (callers never wait), one DataStore `edit` each.
 *
 * @param settings the stored settings, read when a write runs ([Settings.historyEnabled], [Settings.historyLimit]).
 */
class History(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val settings: suspend () -> Settings,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Newest first. */
    val entries: Flow<List<HistoryEntry>> = dataStore.data.map { decodeEntries(it[ENTRIES]) }

    /** A delivered dictation: [raw] the provider's transcript, [finalText] the delivered text. Returns at once. */
    fun record(raw: String, finalText: String) {
        val now = clock()
        scope.launch {
            update { entries ->
                // Read inside the edit: a limit lowered after this read queues its trim after this write.
                val s = settings()
                if (!s.historyEnabled) return@update entries
                val id = (entries.firstOrNull()?.id ?: 0) + 1
                (listOf(HistoryEntry(id, now, raw, finalText.takeIf { it != raw })) + entries).take(s.historyLimit)
            }
        }
    }

    /** Keeps the newest [limit] entries. */
    fun trim(limit: Int) {
        scope.launch { update { it.take(limit) } }
    }

    fun delete(id: Int) {
        scope.launch { update { list -> list.filterNot { it.id == id } } }
    }

    fun clear() {
        scope.launch {
            try {
                dataStore.edit { it.clear() }
            } catch (e: IOException) {
                Log.w(TAG, "Clear failed", e)
            }
        }
    }

    /** One `edit`: read, change and write together; no write when nothing changed. */
    private suspend fun update(change: suspend (List<HistoryEntry>) -> List<HistoryEntry>) {
        try {
            dataStore.edit {
                val current = decodeEntries(it[ENTRIES])
                val next = change(current)
                if (next != current) it[ENTRIES] = json.encodeToString(next)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Write failed", e)
        }
    }

    /** None for null or malformed JSON. */
    private fun decodeEntries(encoded: String?): List<HistoryEntry> {
        if (encoded == null) return emptyList()
        return try {
            json.decodeFromString(encoded)
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    private companion object {
        const val TAG = "History"
        val ENTRIES = stringPreferencesKey("entries")
        val json = Json { ignoreUnknownKeys = true }
    }
}
