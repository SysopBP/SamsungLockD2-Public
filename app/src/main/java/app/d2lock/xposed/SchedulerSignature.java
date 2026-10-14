/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.xposed;

/** Known scheduler layouts only; the final primitive int is the user ID. */
public final class SchedulerSignature {
    private SchedulerSignature() {}
    public static boolean supported(String[] types) {
        if (types.length != 2 && types.length != 3) return false;
        if (!"int".equals(types[types.length - 1])) return false;
        for (int i = 0; i < types.length - 1; i++)
            if (!"long".equals(types[i])) return false;
        return true;
    }
}
