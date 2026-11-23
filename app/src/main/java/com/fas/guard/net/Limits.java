package com.fas.guard.net;

import com.fas.guard.core.Prefs;

import java.util.HashMap;
import java.util.Map;

/** Per-app and global shaping, evaluated together. */
public final class Limits {

    private final Rate globalDown;
    private final Rate globalUp;
    private final Map<String, Rate> down = new HashMap<>();
    private final Map<String, Rate> up = new HashMap<>();
    private final Map<String, int[]> perApp;

    public Limits(Prefs p) {
        globalDown = new Rate(p.globalDownKbps());
        globalUp = new Rate(p.globalUpKbps());
        perApp = p.perAppLimits();
        for (Map.Entry<String, int[]> e : perApp.entrySet()) {
            if (e.getValue()[0] > 0) down.put(e.getKey(), new Rate(e.getValue()[0]));
            if (e.getValue()[1] > 0) up.put(e.getKey(), new Rate(e.getValue()[1]));
        }
    }

    public boolean anyLimit() {
        return globalDown.limited() || globalUp.limited() || !down.isEmpty() || !up.isEmpty();
    }

    /** Returns milliseconds to sleep for this chunk. */
    public long delay(String pkg, boolean isDown, int bytes) {
        if (pkg == null) pkg = "";
        long d = 0;
        if (isDown) {
            Rate r = down.get(pkg);
            if (r != null) d = Math.max(d, r.take(bytes));
            d = Math.max(d, globalDown.take(bytes));
        } else {
            Rate r = up.get(pkg);
            if (r != null) d = Math.max(d, r.take(bytes));
            d = Math.max(d, globalUp.take(bytes));
        }
        return Math.min(d, 2000);
    }
}
