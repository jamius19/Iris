/*
* Author: Jamius Siam
* Since: 18/11/2026
*/

package dev.jamius.iris.ui.settings

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.jamius.iris.R
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.dictation.HistoryEntry
import dev.jamius.iris.overlay.copyToClipboard
import dev.jamius.iris.ui.components.Card
import dev.jamius.iris.ui.components.ControlHeight
import dev.jamius.iris.ui.components.DISABLED_ALPHA
import dev.jamius.iris.ui.components.DangerButton
import dev.jamius.iris.ui.components.IconAction
import dev.jamius.iris.ui.components.PageScaffold
import dev.jamius.iris.ui.components.Saver
import dev.jamius.iris.ui.components.SelectField
import dev.jamius.iris.ui.components.Separator
import dev.jamius.iris.ui.components.SwitchRow
import dev.jamius.iris.ui.components.Text
import dev.jamius.iris.ui.theme.IrisTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * History: the Save History switch and the History Limit (dimmed and locked while History is off), Clear History, then
 * the saved dictations, newest first, each with its time, delete, and the transcript and post-processed text (if it
 * differs) with a copy button. While History is off a note replaces the list; the entries are kept.
 */
@Composable
fun HistoryPage(
    settings: Settings,
    repository: SettingsRepository,
    saver: Saver,
    entries: List<HistoryEntry>?,
    onDelete: (Int) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dim by animateFloatAsState(if (settings.historyEnabled) 1f else DISABLED_ALPHA, tween(150))
    // "<id>:raw" / "<id>:final": the copy button showing its check, and the one whose copy failed.
    var copied by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var copyJob by remember { mutableStateOf<Job?>(null) }

    fun copy(key: String, text: String) {
        try {
            copyToClipboard(context, text)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Copying a history entry failed", e)
            copied = null
            failed = key
            return
        }
        failed = null
        copied = key
        copyJob?.cancel()
        copyJob = scope.launch {
            delay(1_500)
            copied = null
        }
    }

    // Ids can be reused (newest + 1): a check or error must not carry over to another entry.
    fun resetCopy() {
        copyJob?.cancel()
        copied = null
        failed = null
    }

    PageScaffold(title = "History", onBack = onBack) {
        Card(verticalPadding = 15.dp) {
            SwitchRow(
                title = "Save History",
                subtitle = "Keeps your recent dictations on this device.",
                checked = settings.historyEnabled,
                onSave = { repository.setHistoryEnabled(it) },
                saver = saver
            )
            Separator()
            Box(Modifier.alpha(dim)) {
                SelectField(
                    label = "History Limit",
                    options = Settings.HISTORY_LIMITS,
                    selected = settings.historyLimit,
                    optionLabel = { "$it" },
                    onSave = { repository.setHistoryLimit(it) },
                    saver = saver,
                    helper = "Maximum number of history entries to keep.",
                    enabled = settings.historyEnabled
                )
            }
        }
        // Null until the first read: no empty state flashes in.
        if (entries == null) return@PageScaffold
        if (entries.isNotEmpty()) {
            DangerButton(
                "Clear History",
                onClick = {
                    resetCopy()
                    onClear()
                }
            )
        }
        when {
            !settings.historyEnabled -> Text(
                "History is off. Turn it on to save new dictations and see the saved ones.",
                type.helper,
                color = colors.muted
            )
            entries.isEmpty() -> Card {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("No history yet", type.title)
                    Text("Dictate to see what you said here.", type.subtitle, color = colors.muted)
                }
            }
            else -> {
                val zone = ZoneId.systemDefault()
                for (entry in entries) {
                    key(entry.id) {
                        HistoryCard(
                            entry,
                            zone,
                            copied,
                            failed,
                            onCopy = ::copy,
                            onDelete = {
                                resetCopy()
                                onDelete(entry.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** An entry: its time and delete, the transcript and the post-processed text (if any), each with a copy button. */
@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    zone: ZoneId,
    copied: String?,
    failed: String?,
    onCopy: (key: String, text: String) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    val time = historyTimeText(entry.timestampMs, zone)
    val rawKey = "${entry.id}:raw"
    val finalKey = "${entry.id}:final"
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(time, type.subtitle, Modifier.weight(1f), color = colors.muted)
            IconAction(
                R.drawable.lucide_trash,
                contentDescription = "Delete entry from $time",
                tint = colors.failure,
                onClick = onDelete,
                width = ControlHeight
            )
        }
        HistoryText(
            caption = "Transcript",
            text = entry.raw,
            copied = copied == rawKey,
            copyLabel = "Copy transcript",
            onCopy = { onCopy(rawKey, entry.raw) }
        )
        entry.finalText?.let {
            Separator()
            HistoryText(
                caption = "Post Processed",
                text = it,
                copied = copied == finalKey,
                copyLabel = "Copy post processed text",
                onCopy = { onCopy(finalKey, it) }
            )
        }
        if (failed == rawKey || failed == finalKey) {
            Text("Couldn't copy. Try again.", type.helper, color = colors.failure)
        }
    }
}

/** [caption] with a copy button (a check while [copied]), then [text]. */
@Composable
private fun HistoryText(caption: String, text: String, copied: Boolean, copyLabel: String, onCopy: () -> Unit) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(caption, type.helper, Modifier.weight(1f), color = colors.muted)
            IconAction(
                if (copied) R.drawable.lucide_check else R.drawable.lucide_copy,
                contentDescription = copyLabel,
                tint = colors.text,
                onClick = onCopy,
                modifier = Modifier.semantics { if (copied) stateDescription = "Copied" },
                width = ControlHeight
            )
        }
        Text(text, type.field)
    }
}

private const val TAG = "HistoryPage"
