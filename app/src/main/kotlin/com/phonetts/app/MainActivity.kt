package com.phonetts.app

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.phonetts.app.playback.PlaybackService
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phonetts.app.hf.HfBrowseScreen
import com.phonetts.app.hf.HfBrowseViewModel
import com.phonetts.app.manage.ModelManagementScreen
import com.phonetts.app.manage.ModelManagementViewModel
import com.phonetts.app.ui.TtsScreen
import com.phonetts.app.ui.TtsViewModel

private enum class Screen { MAIN, BROWSE, MANAGE }

class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as PhoneTtsApplication).graph }
    private val ttsViewModel: TtsViewModel by viewModels {
        viewModelFactory { initializer { TtsViewModel(graph) } }
    }
    private var binder: PlaybackService.LocalBinder? = null

    // Bind to the foreground playback service so its notification/lock-screen Play/Pause/Stop
    // reach the same TtsViewModel controls, and forward every playback-state change back to it so
    // the notification stays in sync. Bound only while the activity is started.
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val localBinder = service as? PlaybackService.LocalBinder ?: return
                binder = localBinder
                localBinder.attachController(ttsViewModel.playbackController)
                forwardState(localBinder)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent?.readSharedText()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav(graph, ttsViewModel, sharedText)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PlaybackService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        binder?.detachController()
        unbindService(connection)
        binder = null
    }

    private fun forwardState(localBinder: PlaybackService.LocalBinder) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ttsViewModel.state.collect { s ->
                    localBinder.onStateChanged(s.playing, s.paused, s.selected?.displayName)
                }
            }
        }
    }
}

// Text handed to us by "Share to PhoneTTS" (ACTION_SEND) or a "read aloud" text-selection action
// (ACTION_PROCESS_TEXT). Null for a normal launch.
private fun Intent.readSharedText(): String? =
    when (action) {
        Intent.ACTION_SEND -> getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT -> getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }?.takeIf { it.isNotBlank() }

@Composable
private fun AppNav(
    graph: AppGraph,
    ttsViewModel: TtsViewModel,
    sharedText: String?,
) {
    var screen by remember { mutableStateOf(Screen.MAIN) }

    // Load shared/handed-in text into the reader once, when the activity was opened via a share.
    LaunchedEffect(sharedText) {
        sharedText?.let(ttsViewModel::setText)
    }

    when (screen) {
        Screen.MAIN ->
            TtsScreen(
                viewModel = ttsViewModel,
                onBrowseModels = { screen = Screen.BROWSE },
                onManageModels = { screen = Screen.MANAGE },
            )
        Screen.BROWSE -> {
            val hfViewModel: HfBrowseViewModel =
                viewModel(factory = viewModelFactory { initializer { HfBrowseViewModel(graph.hfCatalog, graph.hfDownloader) } })
            BackScaffold(onBack = {
                ttsViewModel.refreshModels() // pick up anything downloaded while browsing
                screen = Screen.MAIN
            }) { HfBrowseScreen(hfViewModel) }
        }
        Screen.MANAGE -> {
            val manageViewModel: ModelManagementViewModel =
                viewModel(factory = viewModelFactory { initializer { ModelManagementViewModel(graph.modelManager) } })
            BackScaffold(onBack = {
                ttsViewModel.refreshModels() // a delete removes it from the model dropdown too
                screen = Screen.MAIN
            }) { ModelManagementScreen(manageViewModel) }
        }
    }
}

@Composable
private fun BackScaffold(
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.padding(horizontal = 16.dp)) { Text("← Back") }
        content()
    }
}
