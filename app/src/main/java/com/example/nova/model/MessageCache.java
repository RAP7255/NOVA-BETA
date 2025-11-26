package com.example.nova.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class MessageCache {
    private final int maxSize;
    private final Map<String, Long> map;

    public MessageCache(int maxSize) {
        this.maxSize = Math.max(64, maxSize);
        this.map = new LinkedHashMap<String, Long>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > MessageCache.this.maxSize;
            }
        };
    }

    public synchronized boolean contains(String id) {
        return map.containsKey(id);
    }

    public synchronized void put(String id) {
        map.put(id, System.currentTimeMillis());
    }

    public synchronized int size() {
        return map.size();
    }
}
