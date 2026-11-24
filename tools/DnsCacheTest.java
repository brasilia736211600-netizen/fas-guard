import com.fas.guard.net.DnsCache;
import java.util.Arrays;

public class DnsCacheTest {
    static void u16(byte[] b, int o, int v) { b[o] = (byte) (v >> 8); b[o + 1] = (byte) v; }
    static void u32(byte[] b, int o, int v) { b[o] = (byte) (v >> 24); b[o + 1] = (byte) (v >> 16); b[o + 2] = (byte) (v >> 8); b[o + 3] = (byte) v; }
    static int u32(byte[] b, int o) { return ((b[o] & 255) << 24) | ((b[o + 1] & 255) << 16) | ((b[o + 2] & 255) << 8) | (b[o + 3] & 255); }
    static int name(byte[] b, int o, String n) {
        for (String p : n.split("\\.")) {
            b[o++] = (byte) p.length();
            for (char c : p.toCharArray()) b[o++] = (byte) c;
        }
        b[o++] = 0;
        return o;
    }
    static byte[] query(String nm, int id) {
        byte[] q = new byte[64];
        u16(q, 0, id); u16(q, 2, 0x0100); u16(q, 4, 1);
        int p = name(q, 12, nm); u16(q, p, 1); u16(q, p + 2, 1);
        return Arrays.copyOf(q, p + 4);
    }
    static byte[] answer(String nm, int ttl1, int ttl2) {
        byte[] r = new byte[256];
        u16(r, 0, 7); u16(r, 2, 0x8180); u16(r, 4, 1); u16(r, 6, 2);
        int p = name(r, 12, nm); u16(r, p, 1); u16(r, p + 2, 1); p += 4;
        r[p++] = (byte) 0xC0; r[p++] = 0x0C; u16(r, p, 1); u16(r, p + 2, 1); u32(r, p + 4, ttl1); u16(r, p + 8, 4); p += 10;
        r[p++] = 1; r[p++] = 2; r[p++] = 3; r[p++] = 4;
        r[p++] = (byte) 0xC0; r[p++] = 0x0C; u16(r, p, 1); u16(r, p + 2, 1); u32(r, p + 4, ttl2); u16(r, p + 8, 4); p += 10;
        r[p++] = 5; r[p++] = 6; r[p++] = 7; r[p++] = 8;
        return Arrays.copyOf(r, p);
    }
    static void check(boolean c, String m) { if (!c) throw new RuntimeException(m); }

    public static void main(String[] a) throws Exception {
        byte[] q1 = query("Example.COM", 1), q2 = query("example.com", 2);
        String k1 = DnsCache.key("1.1.1.1", q1), k2 = DnsCache.key("1.1.1.1", q2);
        check(k1 != null && k1.equals(k2), "case-insensitive key");

        byte[] r = answer("Example.COM", 300, 120);
        int qe = 12 + name(new byte[64], 0, "example.com") + 4;
        int off1 = qe + 2 + 4, off2 = off1 + 16;
        DnsCache c = new DnsCache();
        c.put(k1, r);
        byte[] got = c.get(k2);
        check(got != null, "cache hit");
        check(u32(got, off1) == 300 && u32(got, off2) == 120, "ttl preserved");
        check(DnsCache.rewriteQuestion(got, q2), "question rewrite");
        check(got[0] == 0 && got[1] == 2, "id rewrite");
        check(got[13] == 'e', "case rewrite");
        Thread.sleep(1100);
        got = c.get(k2);
        check(got != null, "still cached");
        check(u32(got, off1) < 300 && u32(got, off2) < 120, "ttl aged");

        DnsCache c2 = new DnsCache();
        byte[] shortTtl = answer("short.io", 1, 1);
        c2.put(DnsCache.key("1.1.1.1", query("short.io", 3)), shortTtl);
        check(c2.get(DnsCache.key("1.1.1.1", query("short.io", 3))) != null, "short ttl cached");
        Thread.sleep(1100);
        check(c2.get(DnsCache.key("1.1.1.1", query("short.io", 3))) == null, "short ttl expired");

        byte[] neg = Arrays.copyOf(query("neg.io", 4), 12);
        u16(neg, 4, 1); u16(neg, 6, 0);
        DnsCache c3 = new DnsCache();
        c3.put(DnsCache.key("1.1.1.1", query("neg.io", 4)), neg);
        check(c3.size() == 0, "negative not cached");
        System.out.println("DNS CACHE TTL/CASE/EXPIRY OK");
    }
}
