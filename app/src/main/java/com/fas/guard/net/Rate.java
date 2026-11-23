package com.fas.guard.net;

/** Token-bucket shaper. Rates are in kbit/s; 0 means unlimited. */
public final class Rate {

    private final double bytesPerSec;
    private double tokens;
    private long lastNanos;

    public Rate(int kbps) {
        this.bytesPerSec = kbps <= 0 ? 0 : kbps * 1000.0 / 8.0;
        this.tokens = bytesPerSec;
        this.lastNanos = System.nanoTime();
    }

    public boolean limited() { return bytesPerSec > 0; }

    /** Consumes bytes and returns how many milliseconds the caller must wait. */
    public synchronized long take(int bytes) {
        if (bytesPerSec <= 0) return 0;
        long now = System.nanoTime();
        tokens += (now - lastNanos) / 1_000_000_000.0 * bytesPerSec;
        lastNanos = now;
        if (tokens > bytesPerSec) tokens = bytesPerSec;
        if (tokens >= bytes) {
            tokens -= bytes;
            return 0;
        }
        double deficit = bytes - tokens;
        tokens = 0;
        return (long) Math.ceil(deficit / bytesPerSec * 1000.0);
    }
}
