package com.fas.guard.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Single source of truth for every user setting (no external dependencies). */
public final class Prefs {

    private static final String FILE = "fasguard";

    public static final String DEFAULT_DNS = "1.1.1.1";

    private static Prefs instance;
    final SharedPreferences sp;

    private Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static synchronized Prefs get(Context ctx) {
        if (instance == null) instance = new Prefs(ctx);
        return instance;
    }

    /* ---------------- tunnel ---------------- */

    public boolean isEnabled() { return sp.getBoolean("enabled", false); }
    public void setEnabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    public boolean autoStart() { return sp.getBoolean("auto_start", false); }
    public void setAutoStart(boolean v) { sp.edit().putBoolean("auto_start", v).apply(); }

    public boolean firewall() { return sp.getBoolean("firewall", true); }
    public void setFirewall(boolean v) { sp.edit().putBoolean("firewall", v).apply(); }

    public boolean blockIpv6() { return sp.getBoolean("block_v6", true); }
    public void setBlockIpv6(boolean v) { sp.edit().putBoolean("block_v6", v).apply(); }

    public boolean blockQuic() { return sp.getBoolean("block_quic", false); }
    public void setBlockQuic(boolean v) { sp.edit().putBoolean("block_quic", v).apply(); }

    public String dns() { return sp.getString("dns", DEFAULT_DNS); }
    public void setDns(String v) { sp.edit().putString("dns", v == null || v.isEmpty() ? DEFAULT_DNS : v).apply(); }

    public int mtu() { return sp.getInt("mtu", 1500); }
    public void setMtu(int v) { sp.edit().putInt("mtu", v).apply(); }

    /* ---------------- firewall rules ---------------- */

    public Set<String> blockedPackages() {
        return new HashSet<>(sp.getStringSet("blocked", Collections.emptySet()));
    }

    public void setBlockedPackages(Set<String> v) {
        sp.edit().putStringSet("blocked", new HashSet<>(v)).apply();
    }

    public boolean isBlocked(String pkg) {
        return blockedPackages().contains(pkg);
    }

    /* ---------------- limits (kbit/s, 0 = unlimited) ---------------- */

    public int globalDownKbps() { return sp.getInt("lim_down", 0); }
    public int globalUpKbps() { return sp.getInt("lim_up", 0); }
    public void setGlobalLimits(int down, int up) {
        sp.edit().putInt("lim_down", clampLimit(down)).putInt("lim_up", clampLimit(up)).apply();
    }

    public int downKbps(String pkg) { return perAppLimits().getOrDefault(pkg, new int[]{0, 0})[0]; }
    public int upKbps(String pkg) { return perAppLimits().getOrDefault(pkg, new int[]{0, 0})[1]; }

    public void setPerAppLimit(String pkg, int down, int up) {
        Map<String, int[]> map = perAppLimits();
        if (down == 0 && up == 0) map.remove(pkg);
        else map.put(pkg, new int[]{clampLimit(down), clampLimit(up)});
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, int[]> e : map.entrySet()) {
                o.put(e.getKey(), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
            }
        } catch (Exception ignored) { }
        sp.edit().putString("limits", o.toString()).apply();
    }

    public Map<String, int[]> perAppLimits() {
        Map<String, int[]> out = new HashMap<>();
        try {
            JSONObject o = new JSONObject(sp.getString("limits", "{}"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray a = o.getJSONArray(k);
                out.put(k, new int[]{a.optInt(0, 0), a.optInt(1, 0)});
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static int clampLimit(int v) {
        if (v < 0) return 0;
        return Math.min(v, 1_000_000);
    }

    /* ---------------- proxy ---------------- */

    public boolean proxyEnabled() { return sp.getBoolean("proxy_on", false); }
    public String proxyType() { return sp.getString("proxy_type", "socks5"); }
    public String proxyHost() { return sp.getString("proxy_host", ""); }
    public int proxyPort() { return sp.getInt("proxy_port", 1080); }
    public String proxyUser() { return sp.getString("proxy_user", ""); }
    public String proxyPass() { return sp.getString("proxy_pass", ""); }

    public void setProxy(boolean on, String type, String host, int port, String user, String pass) {
        sp.edit()
                .putBoolean("proxy_on", on)
                .putString("proxy_type", type)
                .putString("proxy_host", host == null ? "" : host.trim())
                .putInt("proxy_port", Math.max(1, Math.min(65535, port)))
                .putString("proxy_user", user == null ? "" : user)
                .putString("proxy_pass", pass == null ? "" : pass)
                .apply();
    }

    /* ---------------- diagnostics ---------------- */

    public boolean logging() { return sp.getBoolean("logging", true); }
    public void setLogging(boolean v) { sp.edit().putBoolean("logging", v).apply(); }

    public boolean notifyBlocked() { return sp.getBoolean("notify_blocked", true); }
    public void setNotifyBlocked(boolean v) { sp.edit().putBoolean("notify_blocked", v).apply(); }

    public long totalDown() { return sp.getLong("total_down", 0); }
    public long totalUp() { return sp.getLong("total_up", 0); }

    public void addTotals(long down, long up) {
        sp.edit().putLong("total_down", totalDown() + down)
                .putLong("total_up", totalUp() + up).apply();
    }

    public void resetTotals() {
        sp.edit().putLong("total_down", 0).putLong("total_up", 0).apply();
    }
}
