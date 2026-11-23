package com.fas.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

import com.fas.guard.core.Prefs;
import com.fas.guard.vpn.FasVpnService;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        Prefs p = Prefs.get(ctx);
        if (!p.autoStart()) return;
        if (VpnService.prepare(ctx) != null) return; // permission required – user must open the app
        try { FasVpnService.start(ctx); } catch (Exception ignored) { }
    }
}
