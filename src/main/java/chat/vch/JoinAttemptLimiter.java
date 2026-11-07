package chat.vch;

import java.util.*;
import java.util.function.LongSupplier;

/** One admission per direct source IP per ten seconds; unrelated chat traffic is unaffected. */
public final class JoinAttemptLimiter {
    public static final long INTERVAL_MILLIS = 10000;
    private final Map<String,Long> deadlines = new HashMap<>();
    private final LongSupplier millis;
    public JoinAttemptLimiter() { this(() -> System.nanoTime() / 1000000); }
    JoinAttemptLimiter(LongSupplier millis) { this.millis = millis; }
    public synchronized int acquire(String ip) {
        long now = millis.getAsLong();
        long remaining = deadlines.getOrDefault(ip, now) - now;
        if (remaining > 0) return (int)((remaining + 999) / 1000);
        deadlines.values().removeIf(deadline -> deadline <= now);
        if (deadlines.size() >= 16384) return 10;
        deadlines.put(ip, now + INTERVAL_MILLIS);
        return 0;
    }
    public synchronized void continued(String ip) { deadlines.put(ip, millis.getAsLong() + INTERVAL_MILLIS); }
}
