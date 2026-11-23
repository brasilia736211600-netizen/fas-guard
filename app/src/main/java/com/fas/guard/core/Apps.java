package com.fas.guard.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Installed application catalog with a persistent in-memory cache. */
public final class Apps {

    public static final class Info implements Comparable<Info> {
        public final String pkg;
        public final int uid;
        public final String label;
        public final boolean system;
        public final Drawable icon;

        Info(String pkg, int uid, String label, boolean system, Drawable icon) {
            this.pkg = pkg;
            this.uid = uid;
            this.label = label;
            this.system = system;
            this.icon = icon;
        }

        @Override public int compareTo(Info o) {
            return label.compareToIgnoreCase(o.label);
        }
    }

    private static volatile List<Info> cache;

    public static List<Info> all(Context ctx) {
        List<Info> c = cache;
        if (c != null) return c;
        return reload(ctx);
    }

    public static synchronized List<Info> reload(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<Info> out = new ArrayList<>();
        String self = ctx.getPackageName();
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (self.equals(ai.packageName)) continue;
            String label;
            try { label = ai.loadLabel(pm).toString(); } catch (Exception e) { label = ai.packageName; }
            if (label == null || label.trim().isEmpty()) label = ai.packageName;
            Drawable icon = null;
            try { icon = ai.loadIcon(pm); } catch (Exception ignored) { }
            out.add(new Info(ai.packageName, ai.uid, label, (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0, icon));
        }
        Collections.sort(out);
        cache = out;
        return out;
    }

    public static Info byPackage(Context ctx, String pkg) {
        for (Info i : all(ctx)) if (i.pkg.equals(pkg)) return i;
        return null;
    }

    public static String labelOf(Context ctx, String pkg) {
        Info i = byPackage(ctx, pkg);
        return i == null ? pkg : i.label;
    }
}
