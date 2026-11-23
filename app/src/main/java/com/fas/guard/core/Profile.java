package com.fas.guard.core;

import org.json.JSONObject;

/** A VPN / proxy profile. Config text is kept in the app's private files dir. */
public final class Profile {

    public static final String TYPE_OPENVPN = "openvpn";
    public static final String TYPE_WIREGUARD = "wireguard";
    public static final String TYPE_SOCKS5 = "socks5";
    public static final String TYPE_HTTP = "http";
    public static final String TYPE_CUSTOM = "custom";

    public String id;
    public String name;
    public String type;
    public String host = "";
    public int port;
    public String user = "";
    public String pass = "";
    public String file;          // file name inside files/profiles or null
    public boolean active;

    public Profile() { }

    public static Profile fromJson(JSONObject o) {
        Profile p = new Profile();
        p.id = o.optString("id");
        p.name = o.optString("name");
        p.type = o.optString("type", TYPE_CUSTOM);
        p.host = o.optString("host");
        p.port = o.optInt("port");
        p.user = o.optString("user");
        p.pass = o.optString("pass");
        p.file = o.isNull("file") ? null : o.optString("file", null);
        p.active = o.optBoolean("active");
        return p;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("type", type);
            o.put("host", host);
            o.put("port", port);
            o.put("user", user);
            o.put("pass", pass);
            o.put("file", file == null ? JSONObject.NULL : file);
            o.put("active", active);
        } catch (Exception ignored) { }
        return o;
    }

    public boolean delegates() {
        return TYPE_OPENVPN.equals(type) || TYPE_WIREGUARD.equals(type);
    }

    public String label() {
        if (host != null && !host.isEmpty()) return name + "\n" + type + " · " + host + (port > 0 ? ":" + port : "");
        return name + "\n" + type;
    }
}
