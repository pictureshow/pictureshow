/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

/** Saved pages can briefly have identically tagged controls alive at once. The
 * replacement owns the tag; a retired page cannot erase or overwrite its state. */
internal class DesktopControlOwners {
    private val owners = mutableMapOf<String, Any>()
    @Synchronized fun register(tag: String, owner: Any, action: () -> Unit) {
        owners[tag] = owner
        action()
    }
    @Synchronized fun update(tag: String, owner: Any, action: () -> Unit) {
        if (owners[tag] === owner) action()
    }
    @Synchronized fun release(tag: String, owner: Any, action: () -> Unit) {
        if (owners[tag] === owner) { owners.remove(tag); action() }
    }
}
