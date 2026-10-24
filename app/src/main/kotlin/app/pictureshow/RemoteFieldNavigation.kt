/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Keep the field and IME unchanged through Down. TalkBack decides whether to filter each half
 * using IME visibility; hiding it on Down can swallow Up and leave Android's native repeat armed.
 * A release delivered after another control moved focus into the field belongs to that control.
 */
internal class RemoteFieldNavigation(private val keys: Set<Key> = setOf(Key.DirectionUp, Key.DirectionDown)) {
    private var armed: Key? = null

    fun clear() {
        armed = null
    }

    fun handle(event: KeyEvent, navigate: (Key) -> Unit): Boolean {
        if (event.key !in keys) return false
        when (event.type) {
            KeyEventType.KeyDown -> armed = event.key
            KeyEventType.KeyUp -> {
                val paired = armed == event.key
                clear()
                if (paired) navigate(event.key)
            }
        }
        return true
    }
}
