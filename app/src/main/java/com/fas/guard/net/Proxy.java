package com.fas.guard.net;

import android.net.VpnService;

import com.fas.guard.core.Prefs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Opens outbound TCP sockets, optionally chained through HTTP CONNECT or SOCKS5. */
public final class Proxy {

    private static final int TIMEOUT = 15000;

    private Proxy() { }

    public static Socket open(VpnService vpn, Prefs prefs, InetAddress dst, int port) throws IOException {
        boolean useProxy = prefs.proxyEnabled() && !prefs.proxyHost().isEmpty();
        String host = useProxy ? prefs.proxyHost() : dst.getHostAddress();
        int p = useProxy ? prefs.proxyPort() : port;
        Socket s = new Socket();
        if (!vpn.protect(s)) throw new IOException("protect(Socket) failed");
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, p), TIMEOUT);
        s.setSoTimeout(TIMEOUT);
        if (!useProxy) return s;
        if ("http".equalsIgnoreCase(prefs.proxyType())) {
            httpConnect(s, prefs, dst.getHostAddress(), port);
        } else {
            socks5(s, prefs, dst, port);
        }
        s.setSoTimeout(0);
        return s;
    }

    private static void socks5(Socket s, Prefs prefs, InetAddress dst, int port) throws IOException {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        boolean auth = !prefs.proxyUser().isEmpty();
        out.write(new byte[]{5, (byte) (auth ? 2 : 1), 0, (byte) (auth ? 2 : 0)});
        out.flush();
        byte[] r = readN(in, 2);
        if (r[1] == (byte) 0xFF) throw new IOException("SOCKS5: no acceptable auth method");
        if (r[1] == 2) {
            byte[] u = prefs.proxyUser().getBytes(StandardCharsets.UTF_8);
            byte[] p = prefs.proxyPass().getBytes(StandardCharsets.UTF_8);
            byte[] ab = new byte[3 + u.length + p.length];
            ab[0] = 1;
            ab[1] = (byte) u.length;
            System.arraycopy(u, 0, ab, 2, u.length);
            ab[2 + u.length] = (byte) p.length;
            System.arraycopy(p, 0, ab, 3 + u.length, p.length);
            out.write(ab);
            out.flush();
            byte[] ar = readN(in, 2);
            if (ar[1] != 0) throw new IOException("SOCKS5: authentication rejected");
        }
        byte[] addr = dst.getAddress();
        byte[] req = new byte[4 + addr.length + 2];
        req[0] = 5;
        req[1] = 1;
        req[2] = 0;
        req[3] = (byte) (addr.length == 4 ? 1 : 4);
        System.arraycopy(addr, 0, req, 4, addr.length);
        req[4 + addr.length] = (byte) (port >> 8);
        req[5 + addr.length] = (byte) port;
        out.write(req);
        out.flush();
        byte[] head = readN(in, 4);
        if (head[1] != 0) throw new IOException("SOCKS5: connect failed code " + (head[1] & 0xFF));
        int skip;
        switch (head[3]) {
            case 1: skip = 4 + 2; break;
            case 4: skip = 16 + 2; break;
            case 3: skip = (readN(in, 1)[0] & 0xFF) + 2; break;
            default: throw new IOException("SOCKS5: bad reply");
        }
        readN(in, skip);
    }

    private static void httpConnect(Socket s, Prefs prefs, String host, int port) throws IOException {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        StringBuilder sb = new StringBuilder();
        sb.append("CONNECT ").append(host).append(':').append(port).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append(':').append(port).append("\r\n");
        sb.append("Proxy-Connection: keep-alive\r\n");
        if (!prefs.proxyUser().isEmpty()) {
            String token = prefs.proxyUser() + ":" + prefs.proxyPass();
            String b64 = android.util.Base64.encodeToString(token.getBytes(StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP);
            sb.append("Proxy-Authorization: Basic ").append(b64).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            line.append((char) c);
            int n = line.length();
            if (n >= 4 && line.charAt(n - 1) == '\n' && line.charAt(n - 2) == '\r'
                    && line.charAt(n - 3) == '\n' && line.charAt(n - 4) == '\r') break;
            if (n > 8192) throw new IOException("HTTP proxy: header too large");
        }
        String first = line.toString().split("\r\n", 2)[0];
        if (!first.contains(" 200")) throw new IOException("HTTP proxy: " + first);
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("unexpected EOF");
            off += r;
        }
        return b;
    }

    /** UI helper: verifies that the configured proxy accepts TCP connections. */
    public static String test(Prefs prefs) {
        if (!prefs.proxyEnabled()) return "Proxy is disabled";
        if (prefs.proxyHost().isEmpty()) return "No proxy host";
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(prefs.proxyHost(), prefs.proxyPort()), 6000);
            if ("socks5".equalsIgnoreCase(prefs.proxyType())) {
                OutputStream out = s.getOutputStream();
                out.write(new byte[]{5, 1, 0});
                out.flush();
                byte[] r = readN(s.getInputStream(), 2);
                if (r[0] != 5) return "Not a SOCKS5 server";
                return "SOCKS5 reachable (auth " + (r[1] == 0 ? "none" : "required") + ")";
            }
            return "TCP reachable";
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    public static String describe(Prefs p) {
        if (!p.proxyEnabled()) return "Direct";
        return String.format(Locale.US, "%s %s:%d", p.proxyType().toUpperCase(Locale.US),
                p.proxyHost(), p.proxyPort());
    }
}
