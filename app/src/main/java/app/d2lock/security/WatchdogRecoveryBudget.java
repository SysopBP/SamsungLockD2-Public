package app.d2lock.security;

/** Monotonic-time retry budget; a successful request never proves recovery. */
public final class WatchdogRecoveryBudget {
    private long windowStart = -1L;
    private long lastAttempt = -1L;
    private int attempts;
    public synchronized boolean allow(long now) {
        if (now < 0) return false;
        if (windowStart < 0 || now < windowStart || now - windowStart >= 60000L) {
            windowStart = now;
            lastAttempt = -1L;
            attempts = 0;
        }
        if (attempts >= 3 || (lastAttempt >= 0 && now - lastAttempt < 5000L)) return false;
        attempts++;
        lastAttempt = now;
        return true;
    }
    public synchronized int attempts() { return attempts; }
}
