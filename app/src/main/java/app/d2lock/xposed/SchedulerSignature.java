/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.xposed;

/** Known scheduler layouts only; the primitive int position identifies the user ID. */
public final class SchedulerSignature {
    private SchedulerSignature() {}
    public static boolean supported(String[] types) {
        return userIndex(types) >= 0;
    }
    public static int userIndex(String[] types) {
        if (java.util.Arrays.equals(types, new String[]{"int", "long"})) return 0;
        if (types.length != 2 && types.length != 3) return -1;
        if (!"int".equals(types[types.length - 1])) return -1;
        for (int i = 0; i < types.length - 1; i++)
            if (!"long".equals(types[i])) return -1;
        return types.length - 1;
    }
}
