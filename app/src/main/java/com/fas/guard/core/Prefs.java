package com.fas.guard.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Single source of truth for every user setting (no external dependencies). */
public final class Prefs {

    private static final String FILE = "fasguard";
    private static final String RULES = "app_rules_v1";

    public static final String DEFAULT_DNS = "1.1.1.1";

    private static Prefs instance;
    final SharedPreferences sp;
    private volatile Map<String, AppRule> rules;

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

    /* ---------------- per-app rules ---------------- */

    private void ensureRules() {
        if (rules != null) return;
        synchronized (this) {
            if (rules != null) return;
            Map<String, AppRule> m = new ConcurrentHashMap<>();
            String raw = sp.getString(RULES, null);
            if (raw != null) {
                try {
                    JSONObject o = new JSONObject(raw);
                    Iterator<String> it = o.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        AppRule r = AppRule.fromJson(o.getJSONObject(k), k);
                        r.pkg = k;
                        m.put(k, r);
                    }
                } catch (Exception ignored) { }
            } else {
                migrateLegacy(m);
                persist(m);
            }
            rules = m;
        }
    }

    private void migrateLegacy(Map<String, AppRule> m) {
        for (String pkg : sp.getStringSet("blocked", Collections.emptySet())) {
            AppRule r = new AppRule(pkg);
            r.firewall = true;
            r.blocked = true;
            m.put(pkg, r);
        }
        try {
            JSONObject o = new JSONObject(sp.getString("limits", "{}"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String pkg = it.next();
                JSONArray a = o.getJSONArray(pkg);
                AppRule r = m.get(pkg);
                if (r == null) r = new AppRule(pkg);
                r.limit = true;
                r.unit = AppRule.U_KBIT;
                r.downKbps = Math.max(0, a.optInt(0, 0));
                r.upKbps = Math.max(0, a.optInt(1, 0));
                m.put(pkg, r);
            }
        } catch (Exception ignored) { }
    }

    private void persist(Map<String, AppRule> m) {
        JSONObject o = new JSONObject();
        try {
            for (AppRule r : m.values()) o.put(r.pkg, r.toJson());
        } catch (Exception ignored) { }
        sp.edit().putString(RULES, o.toString()).apply();
    }

    public synchronized Map<String, AppRule> appRules() {
        ensureRules();
        return rules;
    }

    public AppRule appRule(String pkg) {
        ensureRules();
        AppRule r = rules.get(pkg);
        return r == null ? new AppRule(pkg) : r.copy();
    }

    public synchronized void setAppRule(AppRule r) {
        ensureRules();
        rules.put(r.pkg, r.copy());
        persist(rules);
    }

    public synchronized void removeAppRule(String pkg) {
        ensureRules();
        rules.remove(pkg);
        persist(rules);
    }

    /* legacy-compatible accessors */

    public Set<String> blockedPackages() {
        Set<String> out = new HashSet<>();
        for (AppRule r : appRules().values()) if (r.isBlocked()) out.add(r.pkg);
        return out;
    }

    public void setBlockedPackages(Set<String> v) {
        ensureRules();
        synchronized (this) {
            for (AppRule r : rules.values()) r.blocked = v.contains(r.pkg);
            for (String pkg : v) {
                if (!rules.containsKey(pkg)) {
                    AppRule r = new AppRule(pkg);
                    r.blocked = true;
                    rules.put(pkg, r);
                }
            }
            persist(rules);
        }
    }

    public boolean isBlocked(String pkg) {
        ensureRules();
        AppRule r = rules.get(pkg);
        return r != null && r.isBlocked();
    }

    /* ---------------- limits (internal unit: kbit/s, 0 = unlimited) ---------------- */

    public int globalDownKbps() { return sp.getInt("lim_down", 0); }
    public int globalUpKbps() { return sp.getInt("lim_up", 0); }

    public void setGlobalLimits(int down, int up) {
        sp.edit().putInt("lim_down", clampLimit(down)).putInt("lim_up", clampLimit(up)).apply();
    }

    public String globalUnit() {
        String u = sp.getString("lim_unit", AppRule.U_KBIT);
        for (String x : AppRule.UNITS) if (x.equals(u)) return u;
        return AppRule.U_KBIT;
    }

    public void setGlobalUnit(String unit) { sp.edit().putString("lim_unit", unit).apply(); }

    public int downKbps(String pkg) {
        AppRule r = appRule(pkg);
        return r.limit ? r.downKbps : 0;
    }

    public int upKbps(String pkg) {
        AppRule r = appRule(pkg);
        return r.limit ? r.upKbps : 0;
    }

    public void setPerAppLimit(String pkg, int down, int up) {
        AppRule r = appRule(pkg);
        r.limit = down > 0 || up > 0;
        r.downKbps = clampLimit(down);
        r.upKbps = clampLimit(up);
        setAppRule(r);
    }

    public Map<String, int[]> perAppLimits() {
        Map<String, int[]> out = new HashMap<>();
        for (AppRule r : appRules().values()) {
            if (r.hasLimit()) out.put(r.pkg, new int[]{r.downKbps, r.upKbps});
        }
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
