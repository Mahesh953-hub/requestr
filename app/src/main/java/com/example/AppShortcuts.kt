package com.example

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Task 5(c) — replaces the previous global keyboard-shortcut wiring, which called
 * `sendBroadcast(Intent("com.example.requestr.SEND_REQUEST"))` /
 * `.../NEW_REQUEST` with zero registered `BroadcastReceiver` anywhere in the app (confirmed via
 * `grep -rn "registerReceiver|BroadcastReceiver" app/src/main` returning nothing) — those two
 * shortcuts were silent no-ops. [ApiTestrScreen] now collects [events] directly, in-process,
 * instead of a system broadcast nothing was listening for.
 */
sealed interface ShortcutEvent {
    data object SendRequest : ShortcutEvent
    data object NewRequest : ShortcutEvent
}

object AppShortcuts {
    private val _events = MutableSharedFlow<ShortcutEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ShortcutEvent> = _events.asSharedFlow()

    fun emit(event: ShortcutEvent) {
        _events.tryEmit(event)
    }
}
