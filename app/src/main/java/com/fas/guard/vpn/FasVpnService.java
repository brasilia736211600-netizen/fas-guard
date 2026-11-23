package com.fas.guard.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.fas.guard.R;
import com.fas.guard.core.AppRule;
import com.fas.guard.core.Prefs;
import com.fas.guard.net.Limits;
import com.fas.guard.net.Net;
import com.fas.guard.net.Proxy;
import com.fas.guard.net.Routing;
import com.fas.guard.ui.MainActivity;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The tunnel engine: a userspace IPv4 TCP/UDP relay with per-UID filtering,
 * token-bucket shaping and HTTP/SOCKS5 upstream chaining.
 */
public class FasVpnService extends VpnService implements Runnable {

    public static final String ACTION_START = "com.fas.guard.START";
    public static final String ACTION_STOP = "com.fas.guard.STOP";

    private static final int CHANNEL = 101;
    private static final int NOTIFICATION = 102;
    private static final int CHUNK = 1400;

    private static volatile boolean running;
    private static volatile String status = "Stopped";
    private static volatile FasVpnService instance;

    private static final AtomicLong up = new AtomicLong();
    private static final AtomicLong down = new AtomicLong();
    private static final AtomicLong blocked = new AtomicLong();
    private static final AtomicLong connections = new AtomicLong();
    private static final List<String> log = new CopyOnWriteArrayList<>();
    private static final Map<String, Flow> flows = new ConcurrentHashMap<>();

    private ParcelFileDescriptor tun;
    private FileInputStream in;
    private FileOutputStream out;
    private Thread reader;
    private Thread reaper;
    private volatile boolean alive;
    private Limits limits;
    private Routing routing;
    private String dnsServer = Prefs.DEFAULT_DNS;
    private Prefs prefs;
    private final Object writeLock = new Object();
    private final ExecutorService dnsPool = Executors.newFixedThreadPool(2);
    private final Map<String, Long> blockedLog = new ConcurrentHashMap<>();
    private final Map<Integer, String[]> uidPackages = new ConcurrentHashMap<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    /* ------------------------------------------------------------------ */

    public static boolean isRunning() { return running; }
    public static long upBytes() { return up.get(); }
    public static long downBytes() { return down.get(); }
    public static long blockedCount() { return blocked.get(); }
    public static long flowCount() { return connections.get(); }
    public static String statusText() { return status; }
    public static List<String> logLines() { return new ArrayList<>(log); }
    public static void clearLog() { log.clear(); }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, FasVpnService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, FasVpnService.class).setAction(ACTION_STOP));
    }

    public static void refreshStats() { /* UI polls the static counters */ }

    private static void logLine(String s) {
        try {
            log.add(0, instance == null ? s : instance.clock.format(new Date()) + "  " + s);
            while (log.size() > 400) log.remove(log.size() - 1);
        } catch (Exception ignored) { }
    }

    /* ------------------------------------------------------------------ */

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = Prefs.get(this);
        createChannel();
        startForegroundCompat(buildNotification("Initialising tunnel…"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!alive) startTunnel();
        return START_STICKY;
    }

    private void startTunnel() {
        try {
            Builder b = new Builder();
            b.setSession("FAS Guard");
            b.addAddress("10.111.222.1", 24);
            b.addRoute("0.0.0.0", 0);
            if (prefs.blockIpv6()) {
                b.addAddress("fd00:1:2::1", 64);
                b.addRoute("::", 0);
            }
            String dns = prefs.dns();
            try {
                InetAddress.getByName(dns);
                dnsServer = dns;
            } catch (Exception e) {
                dnsServer = Prefs.DEFAULT_DNS;
            }
            b.addDnsServer(dnsServer);
            b.setMtu(prefs.mtu());
            b.setBlocking(true);
            try { b.addDisallowedApplication(getPackageName()); } catch (Exception ignored) { }
            tun = b.establish();
            if (tun == null) {
                status = "VPN permission denied";
                logLine("establish() returned null – VPN permission missing");
                stopSelf();
                return;
            }
            in = new FileInputStream(tun.getFileDescriptor());
            out = new FileOutputStream(tun.getFileDescriptor());
            limits = new Limits(prefs);
            routing = new Routing(this);
            alive = true;
            running = true;
            status = "Running";
            up.set(0); down.set(0); blocked.set(0); connections.set(0);
            PersistTotals.reset();
            flows.clear();
            uidPackages.clear();
            blockedLog.clear();
            logLine("Tunnel up · DNS " + dnsServer + " · " + Proxy.describe(prefs));
            reader = new Thread(this, "fas-reader");
            reader.start();
            reaper = new Thread(this::reap, "fas-reaper");
            reaper.start();
            updateNotification();
        } catch (Exception e) {
            status = "Error: " + e.getMessage();
            logLine("Tunnel error: " + e);
            shutdown();
            stopSelf();
        }
    }

    @Override public void run() {
        byte[] buf = new byte[65535];
        while (alive) {
            int len;
            try {
                len = in.read(buf);
            } catch (Exception e) {
                if (alive) logLine("read failed: " + e.getMessage());
                break;
            }
            if (len <= 0) continue;
            try {
                handlePacket(buf, len);
            } catch (Exception e) {
                logLine("packet error: " + e.getMessage());
            }
        }
    }

    private void handlePacket(byte[] buf, int len) {
        Net.Pkt p = Net.parse(buf, len);
        if (p == null) return;
        if (p.version == 6) {
            if (prefs.blockIpv6()) blocked.incrementAndGet();
            return;
        }
        int uid = ownerUid(p);
        String pkg = pkgForUid(uid);
        if (prefs.firewall() && isBlocked(uid)) {
            blocked.incrementAndGet();
            long now = System.currentTimeMillis();
            Long last = blockedLog.get(pkg);
            if (last == null || now - last > 1000) {
                blockedLog.put(pkg, now);
                logLine("blocked " + pkg + " → " + Net.ipString(p.dst) + ":" + p.dstPort);
            }
            return;
        }
        if (p.protocol == Net.PROTO_UDP && p.dstPort == 53) {
            resolve(p, pkg, buf);
            return;
        }
        if (p.protocol == Net.PROTO_UDP && p.dstPort == 443 && prefs.blockQuic()) {
            blocked.incrementAndGet();
            return;
        }
        String key = keyOf(p);
        if (p.protocol == Net.PROTO_TCP) {
            Flow f = flows.get(key);
            if (f == null) {
                if ((p.flags & Net.SYN) == 0) return;
                if (flows.size() >= 400) return;
                TcpFlow t = new TcpFlow(p, pkg, key);
                flows.put(key, t);
                t.start();
            } else if (f instanceof TcpFlow) {
                ((TcpFlow) f).onPacket(p, buf);
            } else if (p.flags != 0 && (p.flags & Net.RST) != 0) {
                f.close();
                flows.remove(key);
            }
        } else if (p.protocol == Net.PROTO_UDP) {
            Flow f = flows.get(key);
            if (f == null) {
                UdpFlow u = new UdpFlow(p, pkg, key);
                flows.put(key, u);
                u.start();
                u.send(p, buf);
            } else if (f instanceof UdpFlow) {
                ((UdpFlow) f).send(p, buf);
            }
        }
    }

    private int ownerUid(Net.Pkt p) {
        if (Build.VERSION.SDK_INT < 29) return -1;
        try {
            InetSocketAddress local = new InetSocketAddress(Net.addr(p.src), p.srcPort);
            InetSocketAddress remote = new InetSocketAddress(Net.addr(p.dst), p.dstPort);
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            return cm == null ? -1 : cm.getConnectionOwnerUid(p.protocol, local, remote);
        } catch (Exception e) {
            return -1;
        }
    }

    private String[] packagesForUid(int uid) {
        String[] cached = uidPackages.get(uid);
        if (cached != null) return cached;
        String[] pkgs = null;
        try { pkgs = getPackageManager().getPackagesForUid(uid); } catch (Exception ignored) { }
        if (pkgs == null || pkgs.length == 0) pkgs = new String[]{"uid" + uid};
        uidPackages.put(uid, pkgs);
        return pkgs;
    }

    private String pkgForUid(int uid) {
        if (uid < 0) return "uid?";
        return packagesForUid(uid)[0];
    }

    private boolean isBlocked(int uid) {
        if (uid < 0) return false;
        for (String pk : packagesForUid(uid)) if (prefs.isBlocked(pk)) return true;
        return false;
    }

    private static String keyOf(Net.Pkt p) {
        return p.protocol + "|" + Net.ipString(p.src) + "|" + p.srcPort + "|"
                + Net.ipString(p.dst) + "|" + p.dstPort;
    }

    /* ---------------- DNS ---------------- */

    private void resolve(Net.Pkt p, String pkg, byte[] buf) {
        final byte[] query = new byte[p.payloadLen];
        System.arraycopy(buf, p.payloadOff, query, 0, p.payloadLen);
        dnsPool.execute(() -> {
            try {
                byte[] answer;
                Proxy.Config cfg = configFor(pkg);
                String server = dnsFor(pkg);
                if (cfg.enabled) {
                    answer = dnsOverTcp(cfg, server, query, pkg);
                } else {
                    answer = dnsUdp(server, query, pkg);
                }
                if (answer == null || answer.length == 0) return;
                long d = limits == null ? 0 : limits.delay(pkg, true, answer.length);
                if (d > 0) Thread.sleep(d);
                byte[] pkt = Net.buildUdp(p.dst, 53, p.src, p.srcPort, answer, 0, answer.length);
                write(pkt);
                down.addAndGet(answer.length);
            } catch (Exception e) {
                logLine("DNS error: " + e.getMessage());
            }
        });
    }

    private Proxy.Config configFor(String pkg) {
        return routing == null ? Proxy.Config.fromPrefs(prefs) : routing.forApp(pkg);
    }

    private String dnsFor(String pkg) {
        AppRule r = prefs.appRule(pkg);
        if (r.dns && r.dnsServer != null && !r.dnsServer.trim().isEmpty()) return r.dnsServer.trim();
        return dnsServer;
    }

    private byte[] dnsUdp(String serverAddr, byte[] query, String pkg) throws Exception {
        DatagramSocket ds = new DatagramSocket();
        if (!protect(ds)) throw new Exception("protect(DatagramSocket) failed");
        ds.setSoTimeout(5000);
        InetAddress server = InetAddress.getByName(serverAddr);
        long d = limits == null ? 0 : limits.delay(pkg, false, query.length);
        if (d > 0) Thread.sleep(d);
        ds.send(new DatagramPacket(query, query.length, server, 53));
        up.addAndGet(query.length);
        byte[] b = new byte[4096];
        DatagramPacket r = new DatagramPacket(b, b.length);
        ds.receive(r);
        ds.close();
        byte[] a = new byte[r.getLength()];
        System.arraycopy(b, 0, a, 0, a.length);
        return a;
    }

    private byte[] dnsOverTcp(Proxy.Config cfg, String serverAddr, byte[] query, String pkg) throws Exception {
        InetAddress server = InetAddress.getByName(serverAddr);
        Socket s = Proxy.open(this, cfg, server, 53);
        OutputStream o = s.getOutputStream();
        InputStream i = s.getInputStream();
        byte[] len = new byte[]{(byte) (query.length >> 8), (byte) query.length};
        o.write(len);
        o.write(query);
        o.flush();
        up.addAndGet(query.length);
        int hi = i.read(), lo = i.read();
        if (hi < 0 || lo < 0) throw new Exception("DNS TCP EOF");
        int n = (hi << 8) | lo;
        byte[] a = new byte[n];
        int off = 0;
        while (off < n) {
            int r = i.read(a, off, n - off);
            if (r < 0) throw new Exception("DNS TCP EOF");
            off += r;
        }
        s.close();
        return a;
    }

    /* ---------------- writers ---------------- */

    void write(byte[] pkt) {
        synchronized (writeLock) {
            try {
                out.write(pkt);
                out.flush();
            } catch (Exception e) {
                logLine("tun write: " + e.getMessage());
            }
        }
    }

    /* ---------------- flow base ---------------- */

    abstract class Flow {
        final String key;
        final String pkg;
        final byte[] localIp;
        final int localPort;
        final byte[] remoteIp;
        final int remotePort;
        final long created = System.currentTimeMillis();
        volatile long used = System.currentTimeMillis();
        volatile boolean closed;
        Thread thread;

        Flow(Net.Pkt p, String pkg, String key) {
            this.key = key;
            this.pkg = pkg;
            this.localIp = p.src;
            this.localPort = p.srcPort;
            this.remoteIp = p.dst;
            this.remotePort = p.dstPort;
        }

        void start() {
            thread = new Thread(this::runFlow, "fas-flow");
            thread.start();
            connections.incrementAndGet();
        }

        abstract void runFlow();

        void close() {
            if (closed) return;
            closed = true;
            flows.remove(key);
            try { if (thread != null) thread.interrupt(); } catch (Exception ignored) { }
        }
    }

    /* ---------------- TCP ---------------- */

    class TcpFlow extends Flow {
        private final ArrayBlockingQueue<Seg> queue = new ArrayBlockingQueue<>(64);
        private volatile long clientNext;
        private volatile long serverSeq;
        private volatile boolean established;
        private volatile boolean clientFin;
        private Socket socket;

        TcpFlow(Net.Pkt p, String pkg, String key) {
            super(p, pkg, key);
            this.clientNext = p.seq + 1;
            this.serverSeq = 0x10000000L + (System.nanoTime() & 0xFFFF);
        }

        @Override void start() {
            super.start();
        }

        void onPacket(Net.Pkt p, byte[] buf) {
            used = System.currentTimeMillis();
            if ((p.flags & Net.RST) != 0) {
                close();
                return;
            }
            if (p.payloadLen > 0) {
                byte[] data = new byte[p.payloadLen];
                System.arraycopy(buf, p.payloadOff, data, 0, data.length);
                queue.offer(new Seg(p.seq, data, false));
            }
            if ((p.flags & Net.FIN) != 0) {
                queue.offer(new Seg(p.seq + p.payloadLen, new byte[0], true));
            }
        }

        @Override void runFlow() {
            try {
                socket = Proxy.open(FasVpnService.this, configFor(pkg), Net.addr(remoteIp), remotePort);
                socket.setTcpNoDelay(true);
                established = true;
                write(packet(Net.SYN | Net.ACK, null, 0, 0));
                Thread t = new Thread(this::pump, "fas-tcp-rx");
                t.start();
                while (!closed) {
                    Seg s = queue.take();
                    if (s.fin) {
                        clientFin = true;
                        sendFin();
                        break;
                    }
                    long end = s.seq + s.data.length;
                    int skip = 0;
                    if (end <= clientNext) continue;
                    if (s.seq < clientNext) skip = (int) (clientNext - s.seq);
                    int n = s.data.length - skip;
                    if (n > 0) {
                        clientNext = end;
                        write(packet(Net.ACK, null, 0, 0));
                        long d = limits == null ? 0 : limits.delay(pkg, false, n);
                        if (d > 0) Thread.sleep(d);
                        socket.getOutputStream().write(s.data, skip, n);
                        socket.getOutputStream().flush();
                        up.addAndGet(n);
                    } else {
                        write(packet(Net.ACK, null, 0, 0));
                    }
                }
            } catch (Exception e) {
                if (!closed) {
                    write(packet(Net.RST | Net.ACK, null, 0, 0));
                    logLine("TCP " + pkg + " → " + Net.ipString(remoteIp) + ":" + remotePort + " failed: " + e.getMessage());
                }
            } finally {
                close();
            }
        }

        private void pump() {
            byte[] b = new byte[CHUNK];
            try {
                InputStream is = socket.getInputStream();
                while (!closed) {
                    int n = is.read(b);
                    if (n < 0) break;
                    if (n == 0) continue;
                    long d = limits == null ? 0 : limits.delay(pkg, true, n);
                    if (d > 0) Thread.sleep(d);
                    write(packet(Net.ACK | Net.PSH, b, 0, n));
                    serverSeq += n;
                    down.addAndGet(n);
                    used = System.currentTimeMillis();
                }
                if (!clientFin) sendFin();
            } catch (Exception e) {
                if (!closed) write(packet(Net.RST | Net.ACK, null, 0, 0));
            } finally {
                close();
            }
        }

        private void sendFin() {
            try {
                if (socket != null && !socket.isOutputShutdown()) socket.shutdownOutput();
            } catch (Exception ignored) { }
            write(packet(Net.FIN | Net.ACK, null, 0, 0));
            serverSeq++;
        }

        private byte[] packet(int flags, byte[] data, int off, int n) {
            return Net.buildTcp(remoteIp, remotePort, localIp, localPort, serverSeq, clientNext,
                    flags, data, off, n, 0xFFFF);
        }

        @Override void close() {
            super.close();
            try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        }
    }

    static final class Seg {
        final long seq;
        final byte[] data;
        final boolean fin;
        Seg(long seq, byte[] data, boolean fin) { this.seq = seq; this.data = data; this.fin = fin; }
    }

    /* ---------------- UDP ---------------- */

    class UdpFlow extends Flow {
        private volatile DatagramSocket socket;
        private final ArrayBlockingQueue<byte[]> out = new ArrayBlockingQueue<>(128);

        UdpFlow(Net.Pkt p, String pkg, String key) { super(p, pkg, key); }

        @Override void runFlow() {
            try {
                DatagramSocket ds = new DatagramSocket();
                if (!protect(ds)) throw new Exception("protect(DatagramSocket) failed");
                ds.connect(new InetSocketAddress(Net.addr(remoteIp), remotePort));
                ds.setSoTimeout(60000);
                socket = ds;
                Thread tx = new Thread(this::pumpOut, "fas-udp-tx");
                tx.start();
                byte[] b = new byte[65535];
                DatagramPacket r = new DatagramPacket(b, b.length);
                while (!closed) {
                    try {
                        ds.receive(r);
                    } catch (java.net.SocketTimeoutException e) {
                        break;
                    }
                    int n = r.getLength();
                    if (n <= 0) continue;
                    used = System.currentTimeMillis();
                    long d = limits == null ? 0 : limits.delay(pkg, true, n);
                    if (d > 0) Thread.sleep(d);
                    write(Net.buildUdp(remoteIp, remotePort, localIp, localPort, b, 0, n));
                    down.addAndGet(n);
                }
            } catch (Exception e) {
                if (!closed) logLine("UDP " + pkg + " error: " + e.getMessage());
            } finally {
                close();
            }
        }

        private void pumpOut() {
            while (!closed) {
                try {
                    byte[] data = out.take();
                    DatagramSocket ds = socket;
                    if (ds == null) continue;
                    long d = limits == null ? 0 : limits.delay(pkg, false, data.length);
                    if (d > 0) Thread.sleep(d);
                    ds.send(new DatagramPacket(data, data.length,
                            InetAddress.getByAddress(remoteIp), remotePort));
                    up.addAndGet(data.length);
                    used = System.currentTimeMillis();
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    close();
                    break;
                }
            }
        }

        void send(Net.Pkt p, byte[] buf) {
            if (p.payloadLen <= 0) return;
            if (out.size() >= 120) return; // drop under pressure – UDP is loss tolerant
            byte[] data = new byte[p.payloadLen];
            System.arraycopy(buf, p.payloadOff, data, 0, data.length);
            out.offer(data);
        }

        @Override void close() {
            super.close();
            try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        }
    }

    /* ---------------- housekeeping ---------------- */

    private void reap() {
        while (alive) {
            try { Thread.sleep(20000); } catch (InterruptedException e) { break; }
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Flow> e : flows.entrySet()) {
                Flow f = e.getValue();
                if (now - f.used > 300000 && now - f.created > 60000) {
                    f.close();
                    flows.remove(e.getKey());
                }
            }
            updateNotification();
            PersistTotals.tick(prefs, up.get(), down.get());
        }
    }

    private void shutdown() {
        alive = false;
        running = false;
        status = "Stopped";
        for (Flow f : new ArrayList<>(flows.values())) f.close();
        flows.clear();
        try { if (reader != null) reader.interrupt(); } catch (Exception ignored) { }
        try { if (reaper != null) reaper.interrupt(); } catch (Exception ignored) { }
        try { dnsPool.shutdownNow(); } catch (Exception ignored) { }
        try { if (in != null) in.close(); } catch (Exception ignored) { }
        try { if (tun != null) tun.close(); } catch (Exception ignored) { }
        tun = null;
        PersistTotals.tick(prefs, up.get(), down.get());
        PersistTotals.reset();
    }

    @Override public void onRevoke() {
        logLine("VPN revoked by the system");
        shutdown();
        stopSelf();
    }

    @Override public void onDestroy() {
        shutdown();
        instance = null;
        super.onDestroy();
    }

    private static final class PersistTotals {
        static long lastUp, lastDown;
        static void tick(Prefs p, long u, long d) {
            if (u >= lastUp && d >= lastDown) {
                long du = u - lastUp, dd = d - lastDown;
                if (du > 0 || dd > 0) p.addTotals(dd, du);
            }
            lastUp = u; lastDown = d;
        }
        static void reset() { lastUp = 0; lastDown = 0; }
    }

    /* ---------------- notification ---------------- */

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel("tunnel",
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, FasVpnService.class).setAction(ACTION_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "tunnel")
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("FAS Guard")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "Stop", ps).build());
        return b.build();
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION, n);
        }
    }

    private void updateNotification() {
        if (!alive) return;
        String text = String.format(Locale.US, "%s ↑%s/s ↓%s/s · %d flows",
                Proxy.describe(prefs), human(up.get()), human(down.get()), flows.size());
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION, buildNotification(text));
        } catch (Exception ignored) { }
    }

    public static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }
}
