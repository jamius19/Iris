/*
* Author: Jamius Siam
* Since: 15/11/2026
*/

package dev.jamius.iris.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.jamius.iris.R
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.ui.components.Card
import dev.jamius.iris.ui.components.ControlHeight
import dev.jamius.iris.ui.components.ControlShape
import dev.jamius.iris.ui.components.DISABLED_ALPHA
import dev.jamius.iris.ui.components.DraftTextField
import dev.jamius.iris.ui.components.IconAction
import dev.jamius.iris.ui.components.PageScaffold
import dev.jamius.iris.ui.components.Saver
import dev.jamius.iris.ui.components.Separator
import dev.jamius.iris.ui.components.SwitchRow
import dev.jamius.iris.ui.components.Text
import dev.jamius.iris.ui.components.rememberSaveState
import dev.jamius.iris.ui.theme.IrisTheme

/**
 * Custom Words (under Post Processing): the master switch, then (dimmed while it is off, still editable) the
 * Correct Similar-Sounding Words switch, the Add Word field (plus or IME Done adds it; over the caps it shows an error
 * and isn't added) and the words, each with a delete. Adds and deletes save at once.
 */
@Composable
fun CustomWordsPage(settings: Settings, repository: SettingsRepository, saver: Saver, onBack: () -> Unit) {
    val colors = IrisTheme.colors
    val dim by animateFloatAsState(if (settings.customWordsEnabled) 1f else DISABLED_ALPHA, tween(150))
    val input = rememberTextFieldState()
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val saveState = rememberSaveState(saver)

    fun add() {
        val word = input.text.toString()
        error = customWordError(settings.customWords, word)
        if (error != null || word.isBlank()) return
        input.clearText()
        saveState.save { repository.addCustomWord(word) }
    }

    PageScaffold(title = "Custom Words (Beta)", onBack = onBack) {
        Card(verticalPadding = 15.dp) {
            SwitchRow(
                title = "Custom Words",
                subtitle = "Help transcription and cleanup spell your names and terms. " +
                    "Sent to cloud providers you use.",
                checked = settings.customWordsEnabled,
                onSave = { repository.setCustomWordsEnabled(it) },
                saver = saver,
                icon = R.drawable.lucide_spell_check
            )
        }
        Card(Modifier.alpha(dim), verticalPadding = 15.dp) {
            SwitchRow(
                title = "Correct Similar-Sounding Words",
                subtitle = "Fix words in the final text that sound like one of yours. May occasionally change the " +
                    "wrong word.",
                checked = settings.correctSimilarWords,
                onSave = { repository.setCorrectSimilarWords(it) },
                saver = saver
            )
            Separator()
            DraftTextField(
                label = "Add Word",
                state = input,
                placeholder = "A word or phrase",
                error = error ?: saveState.error,
                onEdited = { error = null },
                helper = CUSTOM_WORDS_HELPER,
                onDone = ::add,
                action = {
                    IconAction(
                        R.drawable.lucide_plus,
                        contentDescription = "Add word",
                        tint = colors.text,
                        onClick = ::add,
                        modifier = Modifier.border(1.dp, colors.fieldBorder, ControlShape),
                        width = ControlHeight,
                        clearFocus = false
                    )
                }
            )
            if (settings.customWords.isNotEmpty()) {
                Separator()
                Column {
                    for (word in settings.customWords) {
                        key(word) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(word, IrisTheme.type.field, Modifier.weight(1f))
                                IconAction(
                                    R.drawable.lucide_trash,
                                    contentDescription = "Delete $word",
                                    tint = colors.failure,
                                    onClick = { saveState.save { repository.removeCustomWord(word) } },
                                    width = ControlHeight
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
