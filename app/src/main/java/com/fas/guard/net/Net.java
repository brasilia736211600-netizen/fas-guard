package com.fas.guard.net;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

/** IPv4/IPv6 + TCP/UDP packet parsing and construction (checksums included). */
public final class Net {

    public static final int PROTO_TCP = 6;
    public static final int PROTO_UDP = 17;

    public static final int FIN = 0x01, SYN = 0x02, RST = 0x04, PSH = 0x08, ACK = 0x10;

    private static final AtomicInteger IP_ID = new AtomicInteger(1);

    public static final class Pkt {
        public int version;
        public int protocol;
        public byte[] src;
        public byte[] dst;
        public int srcPort;
        public int dstPort;
        public long seq;
        public long ack;
        public int flags;
        public int window;
        public int payloadOff;
        public int payloadLen;
        public int totalLen;
        public boolean tcp;
    }

    private Net() { }

    public static int u8(byte[] b, int i) { return b[i] & 0xFF; }

    public static int u16(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }

    public static long u32(byte[] b, int i) {
        return ((long) (b[i] & 0xFF) << 24) | ((long) (b[i + 1] & 0xFF) << 16)
                | ((long) (b[i + 2] & 0xFF) << 8) | (long) (b[i + 3] & 0xFF);
    }

    public static void put16(byte[] b, int i, int v) {
        b[i] = (byte) (v >> 8);
        b[i + 1] = (byte) v;
    }

    public static void put32(byte[] b, int i, long v) {
        b[i] = (byte) (v >> 24);
        b[i + 1] = (byte) (v >> 16);
        b[i + 2] = (byte) (v >> 8);
        b[i + 3] = (byte) v;
    }

    public static InetAddress addr(byte[] a) {
        try { return InetAddress.getByAddress(a); } catch (Exception e) { return null; }
    }

    public static String ipString(byte[] a) {
        InetAddress i = addr(a);
        return i == null ? "?" : i.getHostAddress();
    }

    /** Parses a full IP packet. Returns null when not understood (fragments, extension headers, ...). */
    public static Pkt parse(byte[] b, int len) {
        if (len < 20) return null;
        int version = (b[0] & 0xF0) >> 4;
        Pkt p = new Pkt();
        p.version = version;
        if (version == 4) {
            int ihl = (b[0] & 0x0F) * 4;
            if (ihl < 20 || len < ihl + 8) return null;
            int frag = u16(b, 6);
            if ((frag & 0x1FFF) != 0 || (frag & 0x2000) != 0) return null; // fragments unsupported
            p.protocol = u8(b, 9);
            p.src = new byte[4];
            p.dst = new byte[4];
            System.arraycopy(b, 12, p.src, 0, 4);
            System.arraycopy(b, 16, p.dst, 0, 4);
            p.totalLen = u16(b, 2);
            int end = Math.min(len, p.totalLen == 0 ? len : p.totalLen);
            parseL4(p, b, ihl, end);
            return p;
        }
        if (version == 6) {
            if (len < 48) return null;
            int next = u8(b, 6);
            if (next != PROTO_TCP && next != PROTO_UDP) return null; // no extension header support
            p.protocol = next;
            p.src = new byte[16];
            p.dst = new byte[16];
            System.arraycopy(b, 8, p.src, 0, 16);
            System.arraycopy(b, 24, p.dst, 0, 16);
            p.totalLen = 40 + u16(b, 4);
            int end = Math.min(len, p.totalLen == 0 ? len : p.totalLen);
            parseL4(p, b, 40, end);
            return p;
        }
        return null;
    }

    private static void parseL4(Pkt p, byte[] b, int off, int end) {
        if (p.protocol == PROTO_TCP) {
            if (end - off < 20) return;
            p.tcp = true;
            p.srcPort = u16(b, off);
            p.dstPort = u16(b, off + 2);
            p.seq = u32(b, off + 4);
            p.ack = u32(b, off + 8);
            int doff = ((b[off + 12] & 0xF0) >> 4) * 4;
            p.flags = u8(b, off + 13);
            p.window = u16(b, off + 14);
            p.payloadOff = off + doff;
            p.payloadLen = Math.max(0, end - p.payloadOff);
        } else if (p.protocol == PROTO_UDP) {
            if (end - off < 8) return;
            p.srcPort = u16(b, off);
            p.dstPort = u16(b, off + 2);
            p.payloadOff = off + 8;
            int ulen = u16(b, off + 4);
            p.payloadLen = Math.max(0, Math.min(end - p.payloadOff, ulen - 8));
        }
    }

    public static byte[] buildTcp(byte[] src, int sp, byte[] dst, int dp, long seq, long ack,
                                  int flags, byte[] payload, int off, int plen, int window) {
        int ipLen = src.length == 4 ? 20 : 40;
        int tcpLen = 20 + plen;
        byte[] out = new byte[ipLen + tcpLen];
        boolean v4 = src.length == 4;
        if (v4) {
            out[0] = 0x45;
            out[1] = 0;
            put16(out, 2, ipLen + tcpLen);
            put16(out, 4, IP_ID.incrementAndGet() & 0xFFFF);
            put16(out, 6, 0x4000);
            out[8] = 64;
            out[9] = (byte) PROTO_TCP;
            System.arraycopy(src, 0, out, 12, 4);
            System.arraycopy(dst, 0, out, 16, 4);
        } else {
            out[0] = 0x60;
            put16(out, 4, tcpLen);
            out[6] = (byte) PROTO_TCP;
            out[7] = 64;
            System.arraycopy(src, 0, out, 8, 16);
            System.arraycopy(dst, 0, out, 24, 16);
        }
        int t = ipLen;
        put16(out, t, sp);
        put16(out, t + 2, dp);
        put32(out, t + 4, seq);
        put32(out, t + 8, ack);
        out[t + 12] = (byte) (5 << 4);
        out[t + 13] = (byte) flags;
        put16(out, t + 14, window <= 0 ? 0xFFFF : Math.min(window, 0xFFFF));
        if (plen > 0) System.arraycopy(payload, off, out, t + 20, plen);
        put16(out, t + 16, tcpChecksum(out, ipLen, tcpLen, v4));
        if (v4) put16(out, 10, checksum(out, 0, 20, 0));
        return out;
    }

    public static byte[] buildUdp(byte[] src, int sp, byte[] dst, int dp,
                                  byte[] payload, int off, int plen) {
        int ipLen = src.length == 4 ? 20 : 40;
        int udpLen = 8 + plen;
        byte[] out = new byte[ipLen + udpLen];
        boolean v4 = src.length == 4;
        if (v4) {
            out[0] = 0x45;
            put16(out, 2, ipLen + udpLen);
            put16(out, 4, IP_ID.incrementAndGet() & 0xFFFF);
            put16(out, 6, 0x4000);
            out[8] = 64;
            out[9] = (byte) PROTO_UDP;
            System.arraycopy(src, 0, out, 12, 4);
            System.arraycopy(dst, 0, out, 16, 4);
        } else {
            out[0] = 0x60;
            put16(out, 4, udpLen);
            out[6] = (byte) PROTO_UDP;
            out[7] = 64;
            System.arraycopy(src, 0, out, 8, 16);
            System.arraycopy(dst, 0, out, 24, 16);
        }
        int t = ipLen;
        put16(out, t, sp);
        put16(out, t + 2, dp);
        put16(out, t + 4, udpLen);
        if (plen > 0) System.arraycopy(payload, off, out, t + 8, plen);
        int c = udpChecksum(out, ipLen, udpLen, v4);
        if (v4 && c == 0) c = 0xFFFF;
        put16(out, t + 6, c);
        if (v4) put16(out, 10, checksum(out, 0, 20, 0));
        return out;
    }

    private static int tcpChecksum(byte[] p, int off, int len, boolean v4) {
        int sum = pseudoSum(p, off, len, v4, PROTO_TCP);
        return checksum(p, off, len, sum);
    }

    private static int udpChecksum(byte[] p, int off, int len, boolean v4) {
        int sum = pseudoSum(p, off, len, v4, PROTO_UDP);
        return checksum(p, off, len, sum);
    }

    private static int pseudoSum(byte[] p, int off, int len, boolean v4, int proto) {
        int sum = 0;
        if (v4) {
            for (int i = 0; i < 4; i += 2) sum += u16(p, 12 + i);
            for (int i = 0; i < 4; i += 2) sum += u16(p, 16 + i);
        } else {
            for (int i = 0; i < 16; i += 2) sum += u16(p, 8 + i);
            for (int i = 0; i < 16; i += 2) sum += u16(p, 24 + i);
        }
        sum += proto;
        sum += len;
        return sum;
    }

    public static int checksum(byte[] b, int off, int len, int initial) {
        long sum = initial;
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            sum += u16(b, i);
            i += 2;
        }
        if (i < end) sum += (b[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }
}
