package com.phonetts.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.phonetts.app.ui.BottomTransportBar
import com.phonetts.app.ui.TtsViewModel
import com.phonetts.core.testing.testDescriptor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeekBarPersistenceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun seekBarRemainsVisibleBeforeGenerationWhilePausedAndAfterStop() {
        val app = ApplicationProvider.getApplicationContext<PhoneTtsApplication>()
        val store = ViewModelStore()
        val vm = TtsViewModel(app.graph)
        store.put("reader", vm)
        val descriptor = testDescriptor("seek-test", "seek-test")
        val state =
            mutableStateOf(
                vm.state.value.copy(
                    models = listOf(descriptor),
                    selected = descriptor,
                    resumeSentenceIndex = null,
                    text = "First sentence. Second sentence. Third sentence.",
                ),
            )
        try {
            compose.setContent { MaterialTheme { BottomTransportBar(state.value, vm) } }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertIsDisplayed()
            compose.onNodeWithText("Sentence 1 of 3").assertIsDisplayed()

            compose.runOnIdle {
                state.value = state.value.copy(playing = true, currentSentenceIndex = 1)
            }
            compose.onNodeWithText("Sentence 2 of 3").assertIsDisplayed()
            compose.runOnIdle { state.value = state.value.copy(paused = true) }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertIsDisplayed()
            compose.onNodeWithText("Sentence 2 of 3").assertIsDisplayed()

            compose.runOnIdle {
                state.value =
                    state.value.copy(
                        playing = false,
                        paused = false,
                        currentSentenceIndex = null,
                        resumeSentenceIndex = 1,
                    )
            }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertIsDisplayed()
            compose.onNodeWithText("Sentence 2 of 3").assertIsDisplayed()
        } finally {
            store.clear()
        }
    }
}
