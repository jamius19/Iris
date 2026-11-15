/*
* Author: Jamius Siam
* Since: 15/11/2026
*/

package dev.jamius.iris.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.jamius.iris.R
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.ui.components.AutosaveTextField
import dev.jamius.iris.ui.components.Card
import dev.jamius.iris.ui.components.DISABLED_ALPHA
import dev.jamius.iris.ui.components.PageScaffold
import dev.jamius.iris.ui.components.Saver
import dev.jamius.iris.ui.components.Separator
import dev.jamius.iris.ui.components.SwitchRow

/**
 * Custom Words (under Post Processing): the master switch, then (dimmed while it is off, still editable) the
 * Correct Similar-Sounding Words switch and the word list, which autosaves; over the caps it shows an error and isn't
 * saved.
 */
@Composable
fun CustomWordsPage(settings: Settings, repository: SettingsRepository, saver: Saver, onBack: () -> Unit) {
    val dim by animateFloatAsState(if (settings.customWordsEnabled) 1f else DISABLED_ALPHA, tween(150))

    PageScaffold(title = "Custom Words", onBack = onBack) {
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
            AutosaveTextField(
                label = "Words",
                stored = settings.customWords.joinToString("\n"),
                onSave = { repository.setCustomWords(it) },
                saver = saver,
                placeholder = "One word or phrase per line",
                helper = customWordsHelper(settings.sttOnDevice),
                validate = ::customWordsError,
                multiLine = true
            )
        }
    }
}
