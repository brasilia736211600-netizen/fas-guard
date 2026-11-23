package com.fas.guard.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Profiles live as a JSON array in SharedPreferences, configs as private files. */
public final class ProfileStore {

    private final Prefs prefs;
    private final Context ctx;

    public ProfileStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = Prefs.get(ctx);
        if (!profilesDir().exists()) profilesDir().mkdirs();
    }

    public File profilesDir() {
        return new File(ctx.getFilesDir(), "profiles");
    }

    public File fileFor(Profile p) {
        return p.file == null ? null : new File(profilesDir(), p.file);
    }

    public List<Profile> list() {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.sp.getString("profiles", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Profile.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    public void save(List<Profile> list) {
        JSONArray a = new JSONArray();
        for (Profile p : list) a.put(p.toJson());
        prefs.sp.edit().putString("profiles", a.toString()).apply();
    }

    public Profile add(Profile p) {
        if (p.id == null) p.id = UUID.randomUUID().toString().substring(0, 8);
        List<Profile> l = list();
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id.equals(p.id)) {
                l.set(i, p);
                save(l);
                return p;
            }
        }
        l.add(p);
        save(l);
        return p;
    }

    public void delete(String id) {
        List<Profile> l = list();
        for (Profile p : new ArrayList<>(l)) {
            if (p.id.equals(id)) {
                File f = fileFor(p);
                if (f != null) f.delete();
                l.remove(p);
            }
        }
        save(l);
    }

    public void setActive(String id) {
        List<Profile> l = list();
        for (Profile p : l) p.active = p.id.equals(id);
        save(l);
    }

    public Profile active() {
        for (Profile p : list()) if (p.active) return p;
        return null;
    }

    public Profile importConfig(String displayName, String text) {
        Profile p = new Profile();
        p.id = UUID.randomUUID().toString().substring(0, 8);
        p.name = displayName == null || displayName.isEmpty() ? "Imported profile" : displayName;
        String lower = p.name.toLowerCase();
        if (lower.endsWith(".ovpn") || lower.endsWith(".conf") || text.contains("remote ")) {
            p.type = Profile.TYPE_OPENVPN;
            parseOpenVpn(p, text);
            p.file = p.id + ".ovpn";
        } else if (lower.endsWith(".wg") || text.contains("[Interface]")) {
            p.type = Profile.TYPE_WIREGUARD;
            p.file = p.id + ".conf";
        } else {
            p.type = Profile.TYPE_CUSTOM;
            p.file = p.id + ".txt";
        }
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(profilesDir(), p.file));
            fos.write(text.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) { }
        return add(p);
    }

    private static void parseOpenVpn(Profile p, String text) {
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            String[] t = line.split("\\s+");
            if (t.length < 2) continue;
            switch (t[0]) {
                case "remote":
                    if (p.host.isEmpty()) {
                        p.host = t[1];
                        if (t.length > 2) {
                            try { p.port = Integer.parseInt(t[2]); } catch (Exception ignored) { }
                        }
                    }
                    break;
                case "port":
                    try { p.port = Integer.parseInt(t[1]); } catch (Exception ignored) { }
                    break;
                default:
                    break;
            }
        }
    }
}
