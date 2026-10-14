/* OneUIX adaptation for D2, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only
 * Upstream: SoClear/OneUIX @ 591d09c9cc69faf3c8faf258e39869b2df7981f4. */
package io.github.soclear.oneuix.common

typealias NetworkSpeedLayout = NetworkSpeedOption.Layout

object NetworkSpeedOption {
    enum class Layout {
        SYSTEM,
        SPLIT_HORIZONTAL,
        SPLIT_VERTICAL,
        ACTIVE_DIRECTION,
    }

    val MARKERS_UP = listOf("↑", "▲", "△", "⇧", "⇡", "⌃", "↕", "^", "⬆")
    val MARKERS_DOWN = listOf("↓", "▼", "▽", "⇩", "⇣", "⌄", "↕", "v", "⬇")

    data class UnitOption(
        val bits: Boolean,
        val base: Int,
        val byteSuffix: String,
        val kiloPrefix: String,
        val megaPrefix: String,
    )

    val UNITS = listOf(
        UnitOption(bits = false, base = 1024, byteSuffix = "B", kiloPrefix = "K", megaPrefix = "M"),
        UnitOption(bits = false, base = 1000, byteSuffix = "B", kiloPrefix = "K", megaPrefix = "M"),
        UnitOption(bits = true, base = 1000, byteSuffix = "b", kiloPrefix = "Kb", megaPrefix = "Mb"),
        UnitOption(bits = true, base = 1024, byteSuffix = "b", kiloPrefix = "Kib", megaPrefix = "Mib"),
    )
}
