package app.d2lock.security;

/** Discards stale callbacks after backgrounding or a newer authentication attempt. */
public final class BiometricSessionGate {
    private long generation;
    private long pending;
    public synchronized long begin() { pending = ++generation; return pending; }
    public synchronized boolean consume(long token) {
        if (token == 0 || pending != token) return false;
        pending = 0;
        return true;
    }
    public synchronized void cancel() { pending = 0; generation++; }
}
