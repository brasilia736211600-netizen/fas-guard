package com.fas.guard.net;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small LRU DNS cache with TTL-aware responses (no external dependencies). */
public final class DnsCache {
    private static final int MAX = 256;
    private static final int MIN_TTL = 30;
    private static final int MAX_TTL = 3600;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final class Entry {
        final byte[] msg;
        final long at;
        final int ttl;
        final int[] offsets;
        Entry(byte[] msg, long at, int ttl, int[] offsets) {
            this.msg = msg; this.at = at; this.ttl = ttl; this.offsets = offsets;
        }
    }

    private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Entry> e) {
            return size() > MAX;
        }
    };

    public synchronized byte[] get(String key) {
        Entry e = map.get(key);
        if (e == null) return null;
        long age = (System.currentTimeMillis() - e.at) / 1000L;
        if (age >= e.ttl) { map.remove(key); return null; }
        byte[] copy = e.msg.clone();
        if (age > 0) {
            int dec = (int) Math.min(age, e.ttl - 1);
            for (int off : e.offsets) {
                int old = u32(copy, off);
                writeU32(copy, off, Math.max(1, old - dec));
            }
        }
        return copy;
    }

    public synchronized void put(String key, byte[] response) {
        if (key == null || response == null || response.length < 12) return;
        long now = System.currentTimeMillis();
        int ttl = minTtl(response);
        if (ttl <= 0) return;
        int[] offs = ttlOffsets(response);
        if (offs == null) return;
        map.put(key, new Entry(response.clone(), now, Math.max(MIN_TTL, Math.min(MAX_TTL, ttl)), offs));
    }

    public synchronized void clear() { map.clear(); }
    public synchronized int size() { return map.size(); }

    /** Cache key from the raw question section + resolver; null if not cacheable. */
    public static String key(String server, byte[] query) {
        if (query == null || query.length < 17) return null;
        if ((query[2] & 0x80) != 0) return null;
        int end = questionEnd(query);
        if (end <= 12) return null;
        StringBuilder sb = new StringBuilder((server == null ? "" : server).length() + 2 + (end - 12) * 2);
        if (server != null) sb.append(server);
        sb.append('|');
        int nameEnd = end - 4;
        for (int i = 12; i < end; i++) {
            int b = query[i] & 0xFF;
            if (i < nameEnd && b >= 'A' && b <= 'Z') b += 32;
            sb.append(HEX[b >>> 4]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    /** Copy the query's question (and ID) into a cached response so 0x20-case clients accept it. */
    public static boolean rewriteQuestion(byte[] response, byte[] query) {
        if (response == null || query == null || response.length < 12 || query.length < 12) return false;
        response[0] = query[0];
        response[1] = query[1];
        int qe = questionEnd(query);
        int re = questionEnd(response);
        if (qe <= 12 || qe != re) return false;
        System.arraycopy(query, 12, response, 12, qe - 12);
        return true;
    }

    private static int questionEnd(byte[] m) {
        int p = 12;
        while (p < m.length) {
            int len = m[p++] & 0xFF;
            if (len == 0) {
                if (p + 4 > m.length) return -1;
                return p + 4;
            }
            if ((len & 0xC0) != 0 || len > 63) return -1;
            p += len;
            if (p > m.length) return -1;
        }
        return -1;
    }

    private static int minTtl(byte[] m) {
        int[] offs = ttlOffsets(m);
        if (offs == null || offs.length == 0) return 0;
        int min = Integer.MAX_VALUE;
        for (int off : offs) {
            int t = u32(m, off);
            if (t >= 0 && t < min) min = t;
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static int[] ttlOffsets(byte[] m) {
        if (m.length < 12) return null;
        int flags = ((m[2] & 0xFF) << 8) | (m[3] & 0xFF);
        if ((flags & 0x8000) == 0) return null;
        if (((flags >>> 11) & 0xF) != 0) return null;
        int qd = u16(m, 4);
        int an = u16(m, 6);
        if (qd != 1 || an <= 0) return null;
        int p = skipName(m, 12);
        if (p < 0 || p + 4 > m.length) return null;
        p += 4;
        int[] offs = new int[an];
        int found = 0;
        for (int i = 0; i < an; i++) {
            p = skipName(m, p);
            if (p < 0 || p + 10 > m.length) break;
            int rdlen = u16(m, p + 8);
            int next = p + 10 + rdlen;
            if (next > m.length) break;
            offs[found++] = p + 4;
            p = next;
        }
        if (found == 0) return null;
        if (found == offs.length) return offs;
        int[] out = new int[found];
        System.arraycopy(offs, 0, out, 0, found);
        return out;
    }

    private static int skipName(byte[] m, int off) {
        int p = off;
        while (p < m.length) {
            int len = m[p] & 0xFF;
            if (len == 0) return p + 1;
            if ((len & 0xC0) == 0xC0) {
                return p + 2 <= m.length ? p + 2 : -1;
            }
            if (len > 63) return -1;
            p += 1 + len;
        }
        return -1;
    }

    private static int u16(byte[] m, int off) {
        if (off + 2 > m.length) return -1;
        return ((m[off] & 0xFF) << 8) | (m[off + 1] & 0xFF);
    }

    private static int u32(byte[] m, int off) {
        if (off + 4 > m.length) return -1;
        return ((m[off] & 0xFF) << 24) | ((m[off + 1] & 0xFF) << 16)
                | ((m[off + 2] & 0xFF) << 8) | (m[off + 3] & 0xFF);
    }

    private static void writeU32(byte[] m, int off, int v) {
        m[off] = (byte) (v >>> 24);
        m[off + 1] = (byte) (v >>> 16);
        m[off + 2] = (byte) (v >>> 8);
        m[off + 3] = (byte) v;
    }
}
