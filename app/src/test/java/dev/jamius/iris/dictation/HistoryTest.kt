/*
* Author: Jamius Siam
* Since: 18/11/2026
*/

package dev.jamius.iris.dictation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jamius.iris.data.settings.toSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    private var current = emptyPreferences().toSettings(emptySet()).copy(historyEnabled = true, historyLimit = 20)
    private var now = 1_000L
    private lateinit var store: MemoryDataStore

    /** In memory: a file-backed store can't replace its file on a Windows host (see SecretStoreTest). */
    private class MemoryDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private fun TestScope.history(initial: Preferences = emptyPreferences()): History {
        store = MemoryDataStore(initial)
        return History(store, backgroundScope, settings = { current }, clock = { now })
    }

    /** Records [count] dictations "1".."count", one clock tick apart. */
    private fun TestScope.recordMany(history: History, count: Int) {
        for (i in 1..count) {
            history.record("$i", "$i")
            now++
        }
        runCurrent()
    }

    private suspend fun History.ids() = entries.first().map { it.id }

    private suspend fun storedJson() = store.data.first()[ENTRIES]

    @Test
    fun missingOrCorruptEntriesReadAsEmptyAndAreReplaced() = runTest {
        assertTrue(history().entries.first().isEmpty())

        val history = history(preferencesOf(ENTRIES to "{not json"))
        assertTrue(history.entries.first().isEmpty())

        history.record("hello", "hello")
        runCurrent()

        assertEquals(listOf(HistoryEntry(1, 1_000, "hello")), history.entries.first())
    }

    @Test
    fun recordAddsTheNewestFirst() = runTest {
        val history = history()
        history.record("first", "first")
        now = 2_000
        history.record("second", "second")
        runCurrent()

        assertEquals(
            listOf(HistoryEntry(2, 2_000, "second"), HistoryEntry(1, 1_000, "first")),
            history.entries.first()
        )
    }

    @Test
    fun recordKeepsOnlyTheLimit() = runTest {
        current = current.copy(historyLimit = 5)
        val history = history()
        recordMany(history, 6)

        assertEquals(listOf(6, 5, 4, 3, 2), history.ids())
    }

    @Test
    fun loweringTheLimitTrimsTheOldest() = runTest {
        val history = history()
        recordMany(history, 3)

        history.trim(2)
        runCurrent()
        assertEquals(listOf(3, 2), history.ids())

        history.trim(10)
        runCurrent()
        assertEquals(listOf(3, 2), history.ids())
    }

    @Test
    fun disabledHistoryRecordsNothing() = runTest {
        current = current.copy(historyEnabled = false)
        val history = history()
        history.record("hello", "Hello.")
        runCurrent()

        assertTrue(history.entries.first().isEmpty())
        assertNull(storedJson())
    }

    @Test
    fun settingsAreReadWhenTheWriteRuns() = runTest {
        val history = history()
        history.record("hello", "hello")
        current = current.copy(historyEnabled = false)
        runCurrent()
        assertTrue(history.entries.first().isEmpty())

        current = current.copy(historyEnabled = true)
        recordMany(history, 6)
        history.record("seventh", "seventh")
        current = current.copy(historyLimit = 5)
        runCurrent()

        assertEquals(listOf(7, 6, 5, 4, 3), history.ids())
    }

    @Test
    fun deleteRemovesOnlyThatEntry() = runTest {
        val history = history()
        recordMany(history, 3)

        history.delete(2)
        runCurrent()
        assertEquals(listOf(3, 1), history.ids())

        history.delete(99)
        runCurrent()
        assertEquals(listOf(3, 1), history.ids())
    }

    @Test
    fun clearRemovesEveryEntry() = runTest {
        val history = history()
        recordMany(history, 3)

        history.clear()
        runCurrent()

        assertTrue(history.entries.first().isEmpty())
    }

    @Test
    fun finalTextIsKeptOnlyWhenItDiffers() = runTest {
        val history = history()
        history.record("uh hello", "Hello.")
        runCurrent()
        assertEquals("Hello.", history.entries.first()[0].finalText)

        history.record("hi", "hi")
        runCurrent()
        assertNull(history.entries.first()[0].finalText)
        // The newest entry's JSON (first in the array) has no finalText.
        assertFalse(storedJson()!!.substringBefore("},").contains("finalText"))

        history.record("hi", "hi ")
        runCurrent()
        assertEquals("hi ", history.entries.first()[0].finalText)
    }

    @Test
    fun idsContinueFromTheNewest() = runTest {
        val history = history()
        recordMany(history, 3)

        history.delete(3)
        history.record("again", "again")
        runCurrent()
        assertEquals(listOf(3, 2, 1), history.ids())

        history.clear()
        history.record("fresh", "fresh")
        runCurrent()
        assertEquals(listOf(1), history.ids())
    }

    private companion object {
        val ENTRIES = stringPreferencesKey("entries")
    }
}
