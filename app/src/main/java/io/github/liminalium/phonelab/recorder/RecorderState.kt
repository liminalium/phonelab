package io.github.liminalium.phonelab.recorder

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** What the recorder is doing, shared between the service and the screen. */
object RecorderState {
    sealed interface State {
        data object Idle : State
        data class Recording(val startedAt: Long, val mic: Boolean, val bubbles: Boolean) : State
        data class Saved(val uri: Uri, val name: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _events = MutableStateFlow<List<String>>(emptyList())

    /** Recent things worth knowing, newest first, such as a camera bubble that had to close. */
    val events: StateFlow<List<String>> = _events

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun set(state: State) {
        _state.value = state
    }

    fun event(text: String) {
        _events.update { (listOf("${LocalTime.now().format(clock)}  $text") + it).take(20) }
    }
}
