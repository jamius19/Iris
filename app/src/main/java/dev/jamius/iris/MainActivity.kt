package dev.jamius.iris

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.jamius.iris.ui.components.LocalRecheckUpdate
import dev.jamius.iris.ui.components.LocalUpdateStatus
import dev.jamius.iris.ui.components.Saver
import dev.jamius.iris.ui.components.UpdateStatus
import dev.jamius.iris.ui.settings.SettingsApp
import dev.jamius.iris.ui.theme.IrisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The system's 90 % white 3-button nav scrim is replaced by a page-coloured one in SettingsApp.
        window.isNavigationBarContrastEnforced = false
        val container = (application as IrisApp).container
        val saver = Saver(container.appScope)
        setContent {
            IrisTheme {
                // Checked each time the app opens and when "Up to date" is tapped (a new tap restarts the check).
                var checks by remember { mutableIntStateOf(0) }
                val update by produceState(UpdateStatus.CHECKING, checks) {
                    value = UpdateStatus.CHECKING
                    value = container.updateStatus()
                }
                CompositionLocalProvider(
                    LocalUpdateStatus provides update,
                    LocalRecheckUpdate provides { checks++ }
                ) {
                    SettingsApp(
                        repository = container.settingsRepository,
                        saver = saver,
                        gatewayModels = container::gatewayModels,
                        openRouterModels = container::openRouterModels,
                        openAiModels = container::openAiTranscriptionModels,
                        customModels = container::customCleanupModels,
                        sonioxCatalog = container.sonioxCatalog,
                        onDeviceModels = container.onDeviceModels,
                        statistics = container.statistics,
                        history = container.history,
                        favicons = container.favicons,
                        sttTakesCustomWords = container::sttTakesCustomWords
                    )
                }
            }
        }
    }
}
