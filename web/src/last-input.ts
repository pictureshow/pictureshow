/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
// The viewer's last input (mouse, touch or keyboard/remote), tracked for the whole app
// from its first interaction, so a screen that opens later (the player's conversion
// notice) already knows how the viewer got there: Enter or a remote's Select on a
// detail page means keyboard, never "Click this note".
import { useSyncExternalStore } from "react";
import type { NoticeInput } from "@pictureshow/core/playback-information";

let current: NoticeInput | undefined;
let installed = false;
const listeners = new Set<() => void>();

function set(value: NoticeInput) {
    if (value === current) return;
    current = value;
    listeners.forEach((l) => l());
}

/** Before any interaction, a guess from the primary pointer: coarse means touch. */
function guess(): NoticeInput {
    return typeof matchMedia === "function" &&
        matchMedia("(pointer: coarse)").matches
        ? "touch"
        : "mouse";
}

/** Called once at app start; capture listeners see every input before any handler. */
export function installLastInput(target: Window = window) {
    if (installed) return;
    installed = true;
    target.addEventListener(
        "pointerdown",
        (e) => set(e.pointerType === "mouse" ? "mouse" : "touch"),
        true,
    );
    target.addEventListener(
        "keydown",
        (e) => {
            // A shortcut chord is not navigation; any other key is the keyboard or a remote.
            if (!e.metaKey && !e.ctrlKey && !e.altKey) set("keyboard");
        },
        true,
    );
}

export function lastInput(): NoticeInput {
    return current ?? guess();
}

export function useLastInput(): NoticeInput {
    return useSyncExternalStore(
        (l) => {
            listeners.add(l);
            return () => listeners.delete(l);
        },
        lastInput,
        lastInput,
    );
}
