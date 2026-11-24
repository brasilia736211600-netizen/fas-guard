package com.fas.guard.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;

/** Userspace TCP relay for one flow. No Android dependencies: exercised by tools/TcpRelayTest. */
public final class TcpRelay {

    public interface Sink {
        void send(byte[] packet);
        void log(String message);
        long delay(boolean down, int bytes);
        void up(int bytes);
        void down(int bytes);
    }

    public interface Connector {
        Socket open() throws IOException;
    }

    private static final class Seg {
        final long seq;
        final byte[] data;
        final boolean fin;
        Seg(long seq, byte[] data, boolean fin) { this.seq = seq; this.data = data; this.fin = fin; }
    }

    private final byte[] localIp;
    private final int localPort;
    private final byte[] remoteIp;
    private final int remotePort;
    private final long synSeq;
    private final Sink sink;
    private final Connector connector;
    private final int chunk;

    private final ArrayBlockingQueue<Seg> queue = new ArrayBlockingQueue<>(256);
    private final Object seqLock = new Object();
    private final Object winLock = new Object();
    private volatile long clientNext;
    private volatile long clientAck;
    private volatile int clientWindow = 65535;
    private volatile long serverSeq;
    private volatile boolean handshakeSent;
    private volatile boolean clientFin;
    private volatile boolean finSent;
    private volatile boolean closed;
    private volatile Thread runner;
    private Socket socket;

    public TcpRelay(byte[] localIp, int localPort, byte[] remoteIp, int remotePort,
                    long clientIsn, long serverIsn, Sink sink, Connector connector, int chunk) {
        this.localIp = localIp;
        this.localPort = localPort;
        this.remoteIp = remoteIp;
        this.remotePort = remotePort;
        this.clientNext = addSeq(clientIsn, 1);
        this.synSeq = serverIsn & 0xFFFFFFFFL;
        this.serverSeq = synSeq;
        this.clientAck = addSeq(clientIsn, 1);
        this.sink = sink;
        this.connector = connector;
        this.chunk = chunk > 0 ? chunk : 1400;
    }

    private long addSeq(long seq, int n) {
        return (seq + n) & 0xFFFFFFFFL;
    }

    public void onPacket(Net.Pkt p, byte[] buf) {
        if (closed) return;
        if ((p.flags & Net.RST) != 0) {
            close();
            return;
        }
        if ((p.flags & Net.SYN) != 0 && (p.flags & Net.ACK) == 0) {
            synchronized (winLock) {
                clientWindow = p.window & 0xFFFF;
                winLock.notifyAll();
            }
            sendSynAck();
            return;
        }
        if ((p.flags & Net.ACK) != 0) {
            synchronized (winLock) {
                long ack = p.ack & 0xFFFFFFFFL;
                if (after(ack, clientAck)) clientAck = ack;
                clientWindow = p.window & 0xFFFF;
                winLock.notifyAll();
            }
        }
        if (p.payloadLen > 0) {
            byte[] data = new byte[p.payloadLen];
            System.arraycopy(buf, p.payloadOff, data, 0, data.length);
            queue.offer(new Seg(p.seq & 0xFFFFFFFFL, data, false));
        }
        if ((p.flags & Net.FIN) != 0) {
            queue.offer(new Seg(addSeq(p.seq, p.payloadLen), new byte[0], true));
        }
    }

    public void run() {
        if (closed) return;
        runner = Thread.currentThread();
        sendSynAck();
        try {
            socket = connector.open();
            if (closed) { try { socket.close(); } catch (Exception ignored) { } return; }
            socket.setTcpNoDelay(true);
            Thread pump = new Thread(this::pump, "fas-tcp-rx");
            pump.setDaemon(true);
            pump.start();
            while (!closed) {
                Seg s = queue.take();
                if (closed) break;
                if (s.fin) {
                    clientFin = true;
                    long diff = (s.seq - clientNext) & 0xFFFFFFFFL;
                    if (diff == 0) clientNext = addSeq(s.seq, 1);
                    writeAck();
                    try { if (!socket.isOutputShutdown()) socket.shutdownOutput(); } catch (Exception ignored) { }
                    continue;
                }
                long diff = (s.seq - clientNext) & 0xFFFFFFFFL;
                if (diff == 0 && s.data.length > 0) {
                    clientNext = addSeq(s.seq, s.data.length);
                    writeAck();
                    long d = sink.delay(false, s.data.length);
                    if (d > 0) Thread.sleep(d);
                    socket.getOutputStream().write(s.data);
                    socket.getOutputStream().flush();
                    sink.up(s.data.length);
                } else {
                    writeAck();
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            if (!closed) {
                reset();
                sink.log("TCP " + Net.ipString(remoteIp) + ":" + remotePort + " failed: " + e.getMessage());
            }
        } finally {
            close();
        }
    }

    private void sendSynAck() {
        synchronized (seqLock) {
            if (closed || handshakeSent) return;
            sink.send(packetAt(synSeq, Net.SYN | Net.ACK, null, 0, 0));
            serverSeq = addSeq(synSeq, 1);
            handshakeSent = true;
        }
    }

    private void writeAck() {
        synchronized (seqLock) {
            if (!closed) sink.send(packetAt(serverSeq, Net.ACK, null, 0, 0));
        }
    }

    private void reset() {
        synchronized (seqLock) {
            if (!closed) sink.send(packetAt(serverSeq, Net.RST | Net.ACK, null, 0, 0));
        }
    }

    private void pump() {
        byte[] b = new byte[chunk];
        try {
            InputStream is = socket.getInputStream();
            while (!closed) {
                int n = is.read(b);
                if (n < 0) break;
                if (n == 0) continue;
                int off = 0;
                while (off < n && !closed) {
                    int send;
                    synchronized (winLock) {
                        while (!closed) {
                            long inflight = (serverSeq - clientAck) & 0xFFFFFFFFL;
                            long room = Math.min(clientWindow, 0xFFFF) - inflight;
                            if (room > 0) break;
                            winLock.wait(1000);
                        }
                        if (closed) break;
                        long inflight = (serverSeq - clientAck) & 0xFFFFFFFFL;
                        long room = Math.min(clientWindow, 0xFFFF) - inflight;
                        send = (int) Math.min(n - off, Math.max(1, room));
                    }
                    long d = sink.delay(true, send);
                    if (d > 0) Thread.sleep(d);
                    synchronized (seqLock) {
                        if (closed) break;
                        sink.send(packetAt(serverSeq, Net.ACK | Net.PSH, b, off, send));
                        serverSeq = addSeq(serverSeq, send);
                    }
                    off += send;
                    sink.down(send);
                }
            }
            if (!closed) sendFin();
        } catch (Exception e) {
            if (!closed) reset();
        } finally {
            close();
        }
    }

    private static boolean after(long a, long b) {
        long d = (a - b) & 0xFFFFFFFFL;
        return d != 0 && d < 0x80000000L;
    }

    private void sendFin() {
        synchronized (seqLock) {
            if (closed || finSent) return;
            sink.send(packetAt(serverSeq, Net.FIN | Net.ACK, null, 0, 0));
            serverSeq = addSeq(serverSeq, 1);
            finSent = true;
        }
    }

    private byte[] packetAt(long seq, int flags, byte[] data, int off, int n) {
        return Net.buildTcp(remoteIp, remotePort, localIp, localPort, seq, clientNext,
                flags, data, off, n, 0xFFFF);
    }

    public void close() {
        if (closed) return;
        closed = true;
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        Thread r = runner;
        if (r != null && r != Thread.currentThread()) r.interrupt();
    }

    public boolean isClosed() { return closed; }
}
