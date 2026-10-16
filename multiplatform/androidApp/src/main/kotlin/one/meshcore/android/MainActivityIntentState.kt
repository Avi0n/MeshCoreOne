package one.meshcore.android

import androidx.lifecycle.ViewModel

internal class MainActivityIntentState : ViewModel() {
    private var initialIntentSubmitted = false

    fun submitInitialIntent(submit: () -> Unit) {
        if (initialIntentSubmitted) return
        initialIntentSubmitted = true
        submit()
    }
}
