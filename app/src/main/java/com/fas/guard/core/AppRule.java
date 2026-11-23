package com.fas.guard.core;

import org.json.JSONObject;

/** Per-application policy: firewall, shaper, proxy route and DNS override. */
public final class AppRule {

    public static final String ROUTE_INHERIT = "inherit";
    public static final String ROUTE_DIRECT = "direct";

    public static final String U_KBIT = "kbit/s";
    public static final String U_KB = "KB/s";
    public static final String U_MBIT = "Mbit/s";
    public static final String U_MB = "MB/s";
    public static final String[] UNITS = {U_KBIT, U_KB, U_MBIT, U_MB};

    public String pkg = "";
    public boolean firewall = true;
    public boolean blocked = false;
    public boolean limit = false;
    public int downKbps = 0;
    public int upKbps = 0;
    public String unit = U_KBIT;
    public boolean proxy = false;
    public String route = ROUTE_INHERIT;
    public boolean dns = false;
    public String dnsServer = "";

    public AppRule() { }

    public AppRule(String pkg) { this.pkg = pkg; }

    public AppRule copy() {
        AppRule r = new AppRule(pkg);
        r.firewall = firewall;
        r.blocked = blocked;
        r.limit = limit;
        r.downKbps = downKbps;
        r.upKbps = upKbps;
        r.unit = unit;
        r.proxy = proxy;
        r.route = route;
        r.dns = dns;
        r.dnsServer = dnsServer;
        return r;
    }

    public boolean isBlocked() { return firewall && blocked; }

    public boolean hasLimit() { return limit && (downKbps > 0 || upKbps > 0); }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (isBlocked()) return "BLOCKED";
        if (limit) sb.append("↓").append(fmt(downKbps)).append(" ↑").append(fmt(upKbps)).append(" · ");
        if (proxy) sb.append("proxy").append(" · ");
        if (dns) sb.append("dns ").append(dnsServer.isEmpty() ? "global" : dnsServer).append(" · ");
        if (sb.length() == 0) return "unrestricted";
        sb.setLength(sb.length() - 3);
        return sb.toString();
    }

    private String fmt(int kbps) {
        if (kbps <= 0) return "∞";
        return displayFromKbps(kbps, unit) + " " + unit;
    }

    public static int unitIndex(String unit) {
        for (int i = 0; i < UNITS.length; i++) if (UNITS[i].equals(unit)) return i;
        return 0;
    }

    public static double multiplier(String unit) {
        switch (unit) {
            case U_KB: return 8;        // 1 KB/s  = 8 kbit/s
            case U_MBIT: return 1000;   // 1 Mbit/s = 1000 kbit/s
            case U_MB: return 8000;     // 1 MB/s  = 8000 kbit/s
            default: return 1;
        }
    }

    public static int kbpsFromDisplay(int value, String unit) {
        if (value <= 0) return 0;
        return (int) Math.round(value * multiplier(unit));
    }

    public static int displayFromKbps(int kbps, String unit) {
        if (kbps <= 0) return 0;
        return (int) Math.round(kbps / multiplier(unit));
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("pkg", pkg);
            o.put("fw", firewall);
            o.put("blocked", blocked);
            o.put("limit", limit);
            o.put("down", downKbps);
            o.put("up", upKbps);
            o.put("unit", unit);
            o.put("proxy", proxy);
            o.put("route", route);
            o.put("dns", dns);
            o.put("dnsServer", dnsServer);
        } catch (Exception ignored) { }
        return o;
    }

    public static AppRule fromJson(JSONObject o, String fallbackPkg) {
        AppRule r = new AppRule(o.optString("pkg", fallbackPkg));
        r.firewall = o.optBoolean("fw", true);
        r.blocked = o.optBoolean("blocked", false);
        r.limit = o.optBoolean("limit", false);
        r.downKbps = Math.max(0, o.optInt("down", 0));
        r.upKbps = Math.max(0, o.optInt("up", 0));
        String u = o.optString("unit", U_KBIT);
        r.unit = contains(u) ? u : U_KBIT;
        r.proxy = o.optBoolean("proxy", false);
        r.route = o.optString("route", ROUTE_INHERIT);
        r.dns = o.optBoolean("dns", false);
        r.dnsServer = o.optString("dnsServer", "");
        return r;
    }

    private static boolean contains(String u) {
        for (String x : UNITS) if (x.equals(u)) return true;
        return false;
    }
}
