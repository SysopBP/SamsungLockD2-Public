/* OneUIX adaptation for D2, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only
 * Upstream: SoClear/OneUIX @ 591d09c9cc69faf3c8faf258e39869b2df7981f4. */
package io.github.soclear.oneuix.common

import android.os.SystemProperties

val ONE_UI_VERSION = SystemProperties.getInt("ro.build.version.oneui", 0)
