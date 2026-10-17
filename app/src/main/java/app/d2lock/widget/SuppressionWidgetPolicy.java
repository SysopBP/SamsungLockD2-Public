/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.widget;
/** Elapsed timestamps must belong to this boot, never a retained pre-reboot event. */
public final class SuppressionWidgetPolicy {
    private SuppressionWidgetPolicy() {}
    public static boolean current(int version, int boot, int liveBoot, long sampled, long now) {
        return version == 2 && boot >= 0 && boot == liveBoot && sampled >= 0 && sampled <= now;
    }
    public static boolean timer(int count, long last, long now) {
        return count > 0 && last >= 0 && last <= now;
    }
}
