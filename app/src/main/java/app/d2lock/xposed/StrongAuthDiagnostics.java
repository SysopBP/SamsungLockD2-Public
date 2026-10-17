/* Copyright (c) 2026 D2 Project and contributors. SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.xposed;

/** Diagnostic labels only: flags never authorize authentication or change policy. */
public final class StrongAuthDiagnostics {
    private StrongAuthDiagnostics() {}
    public static String credential(int type) {
        switch (type) {
            case 1: return "Samsung pattern";
            case 3: return "Samsung PIN";
            case 4: return "Samsung password";
            case -1: return "Unavailable / not reported";
            default: return "Other / unsupported (" + type + ")";
        }
    }
    public static String reasons(int flags) {
        if (flags < 0) return "Unavailable";
        if (flags == 0) return "No strong-auth reason reported";
        String[] names = {"after boot", "device policy", "user request", "lockout",
                "primary-auth timeout", "lockdown", "unattended update", "non-strong biometric timeout"};
        StringBuilder result = new StringBuilder("0x" + Integer.toHexString(flags) + ": ");
        for (int bit = 0; bit < names.length; bit++) {
            if ((flags & (1 << bit)) != 0) result.append(names[bit]).append(", ");
        }
        if ((flags & ~255) != 0) result.append("unknown/OEM flags 0x")
                .append(Integer.toHexString(flags & ~255)).append(", ");
        return result.substring(0, result.length() - 2);
    }
    public static boolean current(int version, int pid, int livePid, int boot, int liveBoot,
                                  long elapsed, long now) {
        return (version == 2 || version == 3) && pid > 0 && pid == livePid && boot >= 0 && boot == liveBoot
                && elapsed >= 0 && elapsed <= now;
    }
}
