/* Copyright (c) 2026 D2 Project and contributors. SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.xposed;

/**
 * Maps the stored D2 choice onto Android's strong-auth re-verification interval.
 *
 * Stored values are hours: 0 = stock Android (72 h), 168 = 7 days, 720 = 30 days, -1 = never.
 * The class stays free of Android types so it can be unit tested on the JVM.
 *
 * allowed() keeps the legacy five-argument contract so PeriodicCredentialPolicyTest still
 * passes; the live control now flows through timeoutMs().
 */
public final class PeriodicCredentialPolicy {
    public static final long DEFAULT_TIMEOUT_MS = 72L * 60 * 60 * 1000;
    /** Stock Android behaviour; the scheduler argument is left untouched. */
    public static final int STOCK_HOURS = 0;
    public static final int WEEK_HOURS = 168;
    public static final int MONTH_HOURS = 720;
    /** No re-verification. Resolved to a ten year alarm offset. */
    public static final int NEVER_HOURS = -1;
    private static final long HOUR_MS = 60L * 60 * 1000;
    private static final long NEVER_MS = 10L * 365L * 24L * HOUR_MS;

    private PeriodicCredentialPolicy() {}

    /** Legacy shim: the old opt-out value never suppressed Samsung authentication. */
    public static boolean allowed(boolean enabled, int userId, boolean deviceOwner,
            boolean profileOwner, long requiredTimeout) {
        return false;
    }

    /** Coerces any stored value onto a supported choice. */
    public static int normalise(int hours) {
        if (hours == WEEK_HOURS || hours == MONTH_HOURS || hours == NEVER_HOURS) return hours;
        return STOCK_HOURS;
    }

    public static boolean isStock(int hours) {
        return normalise(hours) == STOCK_HOURS;
    }

    public static String label(int hours) {
        switch (normalise(hours)) {
            case WEEK_HOURS: return "7 days";
            case MONTH_HOURS: return "30 days";
            case NEVER_HOURS: return "Never";
            default: return "Stock \u00b7 72 hours";
        }
    }

    /**
     * Milliseconds handed to the framework scheduler, or 0 to keep stock behaviour.
     * "Never" becomes a ten year offset so Android keeps its own alarm bookkeeping.
     */
    public static long timeoutMs(int hours) {
        switch (normalise(hours)) {
            case WEEK_HOURS: return WEEK_HOURS * HOUR_MS;
            case MONTH_HOURS: return MONTH_HOURS * HOUR_MS;
            case NEVER_HOURS: return NEVER_MS;
            default: return 0L;
        }
    }
}
