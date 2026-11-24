import com.fas.guard.net.Net;
import com.fas.guard.net.TcpRelay;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TcpRelayTest {
    static final byte[] LOCAL = {10, 0, 0, 2};
    static final byte[] REMOTE = {127, 0, 0, 1};
    static final int LPORT = 41000;
    static final long CLIENT_ISN = 1000L;
    static final long SERVER_ISN = 0x20000000L;

    static void check(boolean c, String m) { if (!c) throw new RuntimeException(m); }
    static int u16(byte[] b, int o) { return ((b[o] & 255) << 8) | (b[o + 1] & 255); }
    static long u32(byte[] b, int o) { return ((long)(b[o]&255)<<24)|((long)(b[o+1]&255)<<16)|((long)(b[o+2]&255)<<8)|(long)(b[o+3]&255); }

    static byte[] tcp(long seq, long ack, int flags, byte[] data) {
        return tcp(seq, ack, flags, data, 65535);
    }

    static byte[] tcp(long seq, long ack, int flags, byte[] data, int window) {
        return Net.buildTcp(LOCAL, LPORT, REMOTE, 1, seq, ack, flags, data, 0, data == null ? 0 : data.length, window);
    }

    public static void main(String[] args) throws Exception {
        final byte[] request = "GET / HTTP/1.0\r\nHost: test\r\n\r\n".getBytes();
        final byte[] response = "HTTP/1.0 200 OK\r\nContent-Length: 5\r\n\r\nHELLO".getBytes();

        ServerSocket ss = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        final int port = ss.getLocalPort();
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        Thread server = new Thread(() -> {
            try (Socket s = ss.accept()) {
                s.setSoTimeout(3000);
                InputStream in = s.getInputStream();
                byte[] b = new byte[512];
                int n;
                while ((n = in.read(b)) >= 0) received.write(b, 0, n);
                s.getOutputStream().write(response);
                s.getOutputStream().flush();
            } catch (Exception e) { throw new RuntimeException(e); }
        }, "test-server");
        server.start();

        LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<>();
        TcpRelay relay = new TcpRelay(LOCAL, LPORT, REMOTE, port, CLIENT_ISN, SERVER_ISN,
                new TcpRelay.Sink() {
                    public void send(byte[] p) { out.offer(p); }
                    public void log(String m) { System.out.println("relay: " + m); }
                    public long delay(boolean d, int bytes) { return 0; }
                    public void up(int bytes) { }
                    public void down(int bytes) { }
                },
                () -> new Socket("127.0.0.1", port), 1400);

        Thread runner = new Thread(relay::run, "relay");
        runner.start();

        byte[] synAck = out.poll(2, TimeUnit.SECONDS);
        check(synAck != null, "no SYN-ACK");
        Net.Pkt s = Net.parse(synAck, synAck.length);
        check(s != null, "bad SYN-ACK packet");
        check((s.flags & Net.SYN) != 0 && (s.flags & Net.ACK) != 0, "SYN-ACK flags");
        check(s.ack == CLIENT_ISN + 1, "SYN-ACK ack=" + s.ack);
        long firstServerSeq = (s.seq + 1) & 0xFFFFFFFFL;

        byte[] ackPkt = tcp(CLIENT_ISN + 1, firstServerSeq, Net.ACK, null);
        relay.onPacket(Net.parse(ackPkt, ackPkt.length), ackPkt);
        byte[] dataPkt = tcp(CLIENT_ISN + 1, firstServerSeq, Net.ACK | Net.PSH, request);
        relay.onPacket(Net.parse(dataPkt, dataPkt.length), dataPkt);
        long clientNext = CLIENT_ISN + 1 + request.length;
        byte[] finPkt = tcp(clientNext, firstServerSeq, Net.FIN | Net.ACK, null);
        relay.onPacket(Net.parse(finPkt, finPkt.length), finPkt);

        ByteArrayOutputStream got = new ByteArrayOutputStream();
        long expected = firstServerSeq;
        boolean fin = false;
        boolean finAcked = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            byte[] pkt = out.poll(200, TimeUnit.MILLISECONDS);
            if (pkt == null) { if (fin) break; continue; }
            Net.Pkt p = Net.parse(pkt, pkt.length);
            if (p == null) continue;
            if ((p.flags & Net.RST) != 0) throw new RuntimeException("got RST: " + new String(pkt));
            if ((p.flags & Net.ACK) != 0 && p.payloadLen == 0 && p.ack == ((clientNext + 1) & 0xFFFFFFFFL)) finAcked = true;
            if (p.payloadLen > 0) {
                check(p.seq == expected, "server data seq " + p.seq + " expected " + expected);
                byte[] payload = new byte[p.payloadLen];
                System.arraycopy(pkt, p.payloadOff, payload, 0, payload.length);
                got.write(payload, 0, payload.length);
                expected = (expected + payload.length) & 0xFFFFFFFFL;
                byte[] ack = tcp(clientNext, expected, Net.ACK, null);
                relay.onPacket(Net.parse(ack, ack.length), ack);
            }
            if ((p.flags & Net.FIN) != 0) { check(p.seq == expected, "FIN seq"); fin = true; }
        }
        runner.join(2000);
        server.join(2000);
        check(fin, "server FIN not received");
        check(finAcked, "client FIN was not ACKed");
        check(java.util.Arrays.equals(got.toByteArray(), response), "response mismatch: " + new String(got.toByteArray()));
        check(java.util.Arrays.equals(received.toByteArray(), request), "request mismatch: " + new String(received.toByteArray()));
        check(relay.isClosed(), "relay did not close");
        ss.close();
        System.out.println("TCP RELAY HANDSHAKE/DATA/HALF-CLOSE OK");
        windowTest();
    }

    static void windowTest() throws Exception {
        final byte[] body = "0123456789ABCDEFGHIJKLMNOPQRST".getBytes();
        ServerSocket ss = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        final int port = ss.getLocalPort();
        Thread server = new Thread(() -> {
            try (Socket s = ss.accept()) {
                s.getOutputStream().write(body);
                s.getOutputStream().flush();
                Thread.sleep(300);
            } catch (Exception ignored) { }
        }, "window-server");
        server.start();

        LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<>();
        TcpRelay relay = new TcpRelay(LOCAL, LPORT, REMOTE, port, CLIENT_ISN, SERVER_ISN,
                new TcpRelay.Sink() {
                    public void send(byte[] p) { out.offer(p); }
                    public void log(String m) { System.out.println("relay: " + m); }
                    public long delay(boolean d, int bytes) { return 0; }
                    public void up(int bytes) { }
                    public void down(int bytes) { }
                },
                () -> new Socket("127.0.0.1", port), 1400);
        Thread runner = new Thread(relay::run, "window-relay");
        runner.start();

        byte[] synAck = out.poll(2, TimeUnit.SECONDS);
        check(synAck != null, "window: no SYN-ACK");
        Net.Pkt s = Net.parse(synAck, synAck.length);
        long serverNext = (s.seq + 1) & 0xFFFFFFFFL;
        byte[] ack = tcp(CLIENT_ISN + 1, serverNext, Net.ACK, null, 10);
        relay.onPacket(Net.parse(ack, ack.length), ack);

        ByteArrayOutputStream got = new ByteArrayOutputStream();
        while (got.size() < body.length) {
            byte[] pkt = out.poll(2, TimeUnit.SECONDS);
            check(pkt != null, "window: stalled at " + got.size());
            Net.Pkt p = Net.parse(pkt, pkt.length);
            if (p == null || p.payloadLen == 0) continue;
            check(p.payloadLen <= 10, "window overflow: " + p.payloadLen);
            byte[] payload = new byte[p.payloadLen];
            System.arraycopy(pkt, p.payloadOff, payload, 0, payload.length);
            got.write(payload, 0, payload.length);
            serverNext = (serverNext + payload.length) & 0xFFFFFFFFL;
            byte[] next = tcp(CLIENT_ISN + 1, serverNext, Net.ACK, null, 10);
            relay.onPacket(Net.parse(next, next.length), next);
        }
        check(java.util.Arrays.equals(got.toByteArray(), body), "window body mismatch");
        byte[] extra = out.poll(250, TimeUnit.MILLISECONDS);
        if (extra != null) {
            Net.Pkt p = Net.parse(extra, extra.length);
            check(p != null && (p.flags & Net.FIN) != 0, "window: unexpected data after body");
        }
        relay.close();
        runner.join(1000);
        server.join(1000);
        ss.close();
        System.out.println("TCP RELAY WINDOW CONTROL OK");
    }
}
