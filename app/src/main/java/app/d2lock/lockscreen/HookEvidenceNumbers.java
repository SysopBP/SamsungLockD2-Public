/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.lockscreen;
/** Intent counters include AtomicInteger registrations and AtomicLong callbacks. */
public final class HookEvidenceNumbers {
    private HookEvidenceNumbers() {}
    public static long value(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }
}
