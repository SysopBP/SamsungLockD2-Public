/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.xposed;
public final class FrameworkObserverPolicy {
    private FrameworkObserverPolicy() {}
    public static boolean shouldRelock(boolean unlocked, boolean master, boolean diagnosticsOnly,
                                        boolean relockEnabled, String event) {
        return unlocked && master && !diagnosticsOnly && relockEnabled && "KEYGUARD".equals(event);
    }
}
