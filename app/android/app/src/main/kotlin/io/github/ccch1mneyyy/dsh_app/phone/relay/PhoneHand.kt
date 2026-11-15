package io.github.ccch1mneyyy.dsh_app.phone.relay

import io.github.ccch1mneyyy.dsh_app.runtime.EngineFamily

/**
 * Which "hand" drives the main screen: the accessibility service (`a11y`) or the Mac relay
 * (`mac`). Saved as `auto | a11y | mac`; `auto` picks `mac` on the Pura X container, whose SELinux
 * domain the engine line already recognises ([EngineFamily.confinedDomain]), and `a11y` anywhere else.
 */
object PhoneHand {
    const val AUTO = "auto"
    const val A11Y = "a11y"
    const val MAC = "mac"

    /** Anything unknown reads as `auto`. */
    fun normalize(wire: String?): String = if (wire == A11Y || wire == MAC) wire else AUTO

    /** The hand in effect (`a11y` or `mac`). */
    fun resolve(choice: String?, seLinuxDomain: String?): String = when (normalize(choice)) {
        A11Y -> A11Y
        MAC -> MAC
        else -> if (EngineFamily.confinedDomain(seLinuxDomain)) MAC else A11Y
    }
}
