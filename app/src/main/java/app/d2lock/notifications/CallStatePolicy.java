/* Copyright (c) 2026 D2 Project and contributors. SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.notifications;

/** Independent of presentation controls and of a possibly delayed Telecom snapshot. */
public final class CallStatePolicy {
    private CallStatePolicy() {}
    public static boolean notificationActive(boolean trustedPackage, boolean callCategory,
            boolean ongoing, boolean trustedFullScreen, boolean callStyle) {
        return trustedPackage && callCategory && (ongoing || trustedFullScreen || callStyle);
    }
    public static boolean active(boolean telecomActive, boolean notificationActive) {
        return telecomActive || notificationActive;
    }
}
