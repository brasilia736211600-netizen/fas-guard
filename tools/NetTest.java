import com.fas.guard.net.Net;

public class NetTest {
    static int u16(byte[] b, int o) { return ((b[o] & 255) << 8) | (b[o + 1] & 255); }

    static int csum(byte[] b, int off, int len, int init) {
        long s = init;
        for (int i = off; i < off + len; i += 2) {
            int hi = b[i] & 255;
            int lo = (i + 1 < off + len) ? b[i + 1] & 255 : 0;
            s += (hi << 8) | lo;
        }
        while ((s >> 16) != 0) s = (s & 0xffff) + (s >> 16);
        return (int) (~s & 0xffff);
    }

    static void check(boolean c, String m) { if (!c) throw new RuntimeException(m); }

    public static void main(String[] a) {
        byte[] src = new byte[]{10, 1, 2, 3};
        byte[] dst = new byte[]{(byte) 93, (byte) 184, (byte) 216, 34};
        byte[] payload = "hello".getBytes();
        byte[] t = Net.buildTcp(src, 12345, dst, 443, 0x11223344L, 0x55667788L,
                Net.ACK | Net.PSH, payload, 0, payload.length, 65535);
        check(t.length == 20 + 20 + payload.length, "tcp len");
        check(u16(t, 2) == t.length, "ip total len");
        check((t[9] & 255) == Net.PROTO_TCP, "proto");
        check(u16(t, 20) == 12345, "sport");
        check(u16(t, 22) == 443, "dport");
        check(csum(t, 0, 20, 0) == 0, "ip checksum invalid");
        int pseudo = ((src[0] & 255) << 8 | (src[1] & 255)) + ((src[2] & 255) << 8 | (src[3] & 255))
                + ((dst[0] & 255) << 8 | (dst[1] & 255)) + ((dst[2] & 255) << 8 | (dst[3] & 255))
                + Net.PROTO_TCP + (20 + payload.length);
        check(csum(t, 20, 20 + payload.length, pseudo) == 0, "tcp checksum invalid");
        byte[] u = Net.buildUdp(src, 5353, dst, 53, payload, 0, payload.length);
        int upseudo = ((src[0] & 255) << 8 | (src[1] & 255)) + ((src[2] & 255) << 8 | (src[3] & 255))
                + ((dst[0] & 255) << 8 | (dst[1] & 255)) + ((dst[2] & 255) << 8 | (dst[3] & 255))
                + Net.PROTO_UDP + (8 + payload.length);
        check(csum(u, 20, 8 + payload.length, upseudo) == 0, "udp checksum invalid");
        check(u16(u, 24) == 8 + payload.length, "udp len");
        System.out.println("NET PACKET CHECKSUMS OK");
    }
}
