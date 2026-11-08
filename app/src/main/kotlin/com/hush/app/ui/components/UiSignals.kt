package com.hush.app.ui.components

import com.hush.app.ui.history.DigestLink
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Activity-retained mailbox between [com.hush.app.ui.MainActivity], the nav shell and the screen
 * ViewModels. It exists so the screens can react to the digest intent and ask for navigation
 * without the NavHost call sites having to grow parameters.
 */
@ActivityRetainedScoped
class UiSignals @Inject constructor() {
    /** Pending digest deep link; set from the activity's intent, cleared when History is left or "Show all" is tapped. */
    val digestLink = MutableStateFlow<DigestLink?>(null)

    private val _navigate = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Routes a screen asked the nav shell to open. */
    val navigate: SharedFlow<String> = _navigate

    fun requestNavigation(route: String) {
        _navigate.tryEmit(route)
    }
}
