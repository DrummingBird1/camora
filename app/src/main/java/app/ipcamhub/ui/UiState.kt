package app.ipcamhub.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** Small bits of screen state that the navigation shell needs to see. */
object UiState {
    /** True while a source is shown full screen: the bottom navigation is hidden then. */
    val fullscreen = MutableStateFlow(false)

    /** Tab to switch to, requested from outside the screens (a launcher shortcut). */
    val openTab = MutableStateFlow<Int?>(null)
}
