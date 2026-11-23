package com.fas.guard.net;

import android.content.Context;

import com.fas.guard.core.AppRule;
import com.fas.guard.core.Prefs;
import com.fas.guard.core.Profile;
import com.fas.guard.core.ProfileStore;

import java.util.HashMap;
import java.util.Map;

/** Resolves the outbound proxy for a package, honouring per-app overrides. */
public final class Routing {

    private final Prefs prefs;
    private final Map<String, Proxy.Config> profiles = new HashMap<>();

    public Routing(Context ctx) {
        this.prefs = Prefs.get(ctx);
        for (Profile p : new ProfileStore(ctx).list()) {
            if (Profile.TYPE_SOCKS5.equals(p.type) || Profile.TYPE_HTTP.equals(p.type)) {
                profiles.put(p.id, new Proxy.Config(true, p.type, p.host, p.port, p.user, p.pass));
            }
        }
    }

    public Proxy.Config forApp(String pkg) {
        AppRule r = prefs.appRule(pkg == null ? "" : pkg);
        if (!r.proxy) return Proxy.Config.direct();
        if (AppRule.ROUTE_DIRECT.equals(r.route)) return Proxy.Config.direct();
        if (r.route != null && !r.route.isEmpty() && !AppRule.ROUTE_INHERIT.equals(r.route)) {
            Proxy.Config c = profiles.get(r.route);
            if (c != null) return c;
            // OpenVPN/WireGuard profiles are not natively routable yet (P6):
            // fall back to the global proxy instead of silently going direct.
            return Proxy.Config.fromPrefs(prefs);
        }
        return Proxy.Config.fromPrefs(prefs);
    }

    public Proxy.Config global() {
        return Proxy.Config.fromPrefs(prefs);
    }
}
