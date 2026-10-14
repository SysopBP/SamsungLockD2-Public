/* Copyright (c) 2026 D2 Project and contributors. SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.xposed;

/** Limits the experimental scheduler override to an unmanaged primary user. */
public final class PeriodicCredentialPolicy {
    public static final long DEFAULT_TIMEOUT_MS = 72L * 60 * 60 * 1000;
    private PeriodicCredentialPolicy() {}
    public static boolean allowed(boolean enabled, int userId, boolean deviceOwner,
            boolean profileOwner, long requiredTimeout) {
        return enabled && userId == 0 && !deviceOwner && !profileOwner
                && requiredTimeout == DEFAULT_TIMEOUT_MS;
    }
}
