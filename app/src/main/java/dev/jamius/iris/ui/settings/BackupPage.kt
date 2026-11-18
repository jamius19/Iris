/*
* Author: Jamius Siam
* Since: 15/11/2026
*/

package dev.jamius.iris.ui.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jamius.iris.data.settings.BackupException
import dev.jamius.iris.data.settings.NOT_A_BACKUP
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.ui.components.Card
import dev.jamius.iris.ui.components.DraftPasswordField
import dev.jamius.iris.ui.components.FieldGroup
import dev.jamius.iris.ui.components.PageScaffold
import dev.jamius.iris.ui.components.Section
import dev.jamius.iris.ui.components.SmallButton
import dev.jamius.iris.ui.components.Text
import dev.jamius.iris.ui.theme.IrisTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class Action { Backup, Restore }

private class Outcome(val text: String, val error: Boolean)

private class PickedFile(val uri: Uri, val name: String)

/**
 * Backup and Restore, each with its own password: Create Backup writes an encrypted file; Restore picks a file, then
 * Restore Backup replaces all settings and API keys with its. Nothing here is saveable, so no password (or picked
 * file) reaches instance state.
 */
@OptIn(ExperimentalFoundationApi::class) // undoState: a used password must not come back with undo.
@Composable
fun BackupPage(repository: SettingsRepository, onRestored: () -> Unit, onBack: () -> Unit) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val backupPassword = remember { TextFieldState() }
    val restorePassword = remember { TextFieldState() }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    var running by remember { mutableStateOf<Action?>(null) }
    var outcome by remember { mutableStateOf<Pair<Action, Outcome>?>(null) }

    fun passwordChars(password: TextFieldState): CharArray? =
        password.text.takeIf { it.isNotEmpty() }?.let { t -> CharArray(t.length) { t[it] } }

    suspend fun writeBackup(uri: Uri, chars: CharArray) {
        var completed = false
        try {
            val bytes = repository.exportBackup(chars)
            withContext(Dispatchers.IO) {
                // Mode "w": the document was just created, so there is nothing to truncate.
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IOException("No output stream")
            }
            completed = true
        } finally {
            // No empty or partial file is left behind (best effort).
            if (!completed) deleteDocument(resolver, uri)
        }
    }

    suspend fun restoreBackup(uri: Uri, chars: CharArray) {
        val bytes = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { it.readAtMost(MAX_BACKUP_BYTES + 1) }
        }
        if (bytes == null || bytes.size > MAX_BACKUP_BYTES) throw BackupException(NOT_A_BACKUP)
        // Leaving the page mid-restore still completes it (and reloads the model lists).
        withContext(NonCancellable) {
            repository.restoreBackup(bytes, chars)
            onRestored()
        }
    }

    fun perform(action: Action, uri: Uri) {
        // A second picker (double tap) must not start a second, concurrent operation.
        if (running != null) {
            if (action == Action.Backup) scope.launch { deleteDocument(resolver, uri) }
            return
        }
        val password = if (action == Action.Backup) backupPassword else restorePassword
        // Backup: empty after an activity recreation while the picker was open (the password is never saved).
        // Restore: its button is disabled while the password is empty.
        val chars = passwordChars(password)
        if (chars == null) {
            if (action == Action.Backup) {
                passwordError = PASSWORD_REQUIRED
                scope.launch { deleteDocument(resolver, uri) }
            }
            return
        }
        running = action
        scope.launch {
            try {
                if (action == Action.Backup) writeBackup(uri, chars) else restoreBackup(uri, chars)
                outcome = action to Outcome(if (action == Action.Backup) BACKUP_SAVED else RESTORED, error = false)
                password.clearText()
                password.undoState.clearHistory()
                if (action == Action.Restore) picked = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Messages and causes never hold the password, keys or payload.
                Log.w(TAG, if (action == Action.Backup) "Backup failed" else "Restore failed", e)
                val generic = if (action == Action.Backup) BACKUP_FAILED else RESTORE_FAILED
                outcome = action to Outcome((e as? BackupException)?.message ?: generic, error = true)
            } finally {
                chars.fill('\u0000')
                running = null
            }
        }
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) perform(Action.Backup, uri) }
    // The extension has no MIME type, and Drive may report anything. Picking only selects; the file is read on restore.
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { picked = PickedFile(uri, displayName(resolver, uri)) }
    }

    fun createBackup() {
        outcome = null
        val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        createDocument.launch("iris-backup-$date.irisbackup")
    }

    fun chooseFile() {
        outcome = null
        openDocument.launch(arrayOf("*/*"))
    }

    PageScaffold(title = "Backup and Restore", onBack = onBack) {
        Section("Backup") {
            Text(BACKUP_TEXT, type.subtitle, color = colors.muted)
            DraftPasswordField(
                label = "Password",
                state = backupPassword,
                placeholder = "Enter a password",
                helper = PASSWORD_HELPER,
                error = passwordError,
                onEdited = { passwordError = null }
            )
            ActionButton(
                if (running == Action.Backup) "Creating…" else "Create Backup",
                enabled = running == null && backupPassword.text.isNotEmpty(),
                outcome = outcome?.takeIf { it.first == Action.Backup }?.second,
                onClick = { createBackup() }
            )
        }
        Section("Restore") {
            Text(RESTORE_TEXT, type.subtitle, color = colors.muted)
            val restoreOutcome = outcome?.takeIf { it.first == Action.Restore }?.second
            val file = picked
            if (file == null) {
                ActionButton(
                    "Choose Backup File",
                    enabled = running == null,
                    outcome = restoreOutcome,
                    onClick = { chooseFile() }
                )
            } else {
                FieldGroup("Selected File") { Text(file.name, type.subtitle, color = colors.muted) }
                SmallButton("Choose Another File", onClick = { chooseFile() }, enabled = running == null)
                DraftPasswordField(
                    label = "Password",
                    state = restorePassword,
                    placeholder = "Enter the backup's password",
                    helper = RESTORE_PASSWORD_HELPER,
                    error = null,
                    onEdited = {}
                )
                ActionButton(
                    if (running == Action.Restore) "Restoring…" else "Restore Backup",
                    enabled = running == null && restorePassword.text.isNotEmpty(),
                    outcome = restoreOutcome,
                    onClick = {
                        outcome = null
                        perform(Action.Restore, file.uri)
                    }
                )
            }
        }
    }
}

/** A [SmallButton] with its [outcome] (success or failure colour) below. */
@Composable
private fun ActionButton(text: String, enabled: Boolean, outcome: Outcome?, onClick: () -> Unit) {
    val colors = IrisTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton(text, onClick = onClick, enabled = enabled)
        outcome?.let { Text(it.text, IrisTheme.type.helper, color = if (it.error) colors.failure else colors.enabled) }
    }
}

/** Best effort: some providers can't delete, and the file may already be gone. */
private suspend fun deleteDocument(resolver: ContentResolver, uri: Uri) {
    withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
}

/** The picked file's name, or a generic one when the provider doesn't report it. */
private suspend fun displayName(resolver: ContentResolver, uri: Uri): String = withContext(Dispatchers.IO) {
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Backup file"
}

/** At most [limit] bytes of the stream (a picked file may be huge). */
private fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val TAG = "BackupPage"

/** Far above any real backup; larger files aren't read whole. */
private const val MAX_BACKUP_BYTES = 4 * 1024 * 1024

private const val PASSWORD_REQUIRED = "Enter a password."
private const val BACKUP_SAVED = "Backup saved."
private const val RESTORED = "Settings restored."
private const val BACKUP_FAILED = "Couldn't save the backup. Try again."
private const val RESTORE_FAILED = "Couldn't restore the backup. Try again."

private const val PASSWORD_HELPER = "Needed to restore the backup and can't be recovered. Anyone with the file and " +
    "the password can read your API keys."

private const val RESTORE_PASSWORD_HELPER = "The password the backup was created with."

private const val BACKUP_TEXT = "Saves all settings, prompts, custom providers and API keys to an encrypted file. " +
    "History, statistics and downloaded models aren't included."

private const val RESTORE_TEXT = "Replaces all settings, prompts, custom providers and API keys with the backup's. " +
    "Anything not in the backup is removed. History, statistics and downloaded models are kept, so download the " +
    "restored on-device model if it's missing."
