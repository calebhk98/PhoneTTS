package com.phonetts.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.phonetts.app.ui.TtsScreen
import com.phonetts.app.ui.TtsViewModel

private enum class Screen { MAIN, BROWSE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as PhoneTtsApplication).graph
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav(graph)
                }
            }
        }
    }
}

@Composable
private fun AppNav(graph: AppGraph) {
    var screen by remember { mutableStateOf(Screen.MAIN) }
    val ttsViewModel: TtsViewModel = viewModel(factory = viewModelFactory { initializer { TtsViewModel(graph) } })

    when (screen) {
        Screen.MAIN -> TtsScreen(viewModel = ttsViewModel, onBrowseModels = { screen = Screen.BROWSE })
        Screen.BROWSE -> {
            val hfViewModel: HfBrowseViewModel =
                viewModel(factory = viewModelFactory { initializer { HfBrowseViewModel(graph.hfCatalog, graph.hfDownloader) } })
            Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = {
                        ttsViewModel.refreshModels() // pick up anything downloaded while browsing
                        screen = Screen.MAIN
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("← Back") }
                HfBrowseScreen(hfViewModel)
            }
        }
    }
}
