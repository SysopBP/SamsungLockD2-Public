/* SPDX-License-Identifier: AGPL-3.0-only */
package io.github.soclear.oneuix.hook
import io.github.soclear.oneuix.common.PowerMenuAction
object PowerMenuPreferences {
 @Volatile var actions: List<PowerMenuAction>? = null
}
