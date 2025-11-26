package com.example.nova.ble;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class MeshGattGuard {

    private static final AtomicBoolean isBusy = new AtomicBoolean(false);
    private static final Set<Long> seen = new HashSet<>();
    private static long lastGattTs = 0;

    public static boolean alreadySeen(long id) {
        return seen.contains(id);
    }

    public static void markSeen(long id) {
        seen.add(id);
    }

    public static boolean begin() {
        long now = System.currentTimeMillis();
        if (now - lastGattTs < 300) return false;   // debounce 300ms
        lastGattTs = now;

        return isBusy.compareAndSet(false, true);
    }

    public static void end() {
        isBusy.set(false);
    }
}
