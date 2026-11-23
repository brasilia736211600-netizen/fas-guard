package com.fas.guard.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.fas.guard.core.Apps;
import com.fas.guard.core.Prefs;
import com.fas.guard.core.Profile;
import com.fas.guard.core.ProfileStore;
import com.fas.guard.net.Proxy;
import com.fas.guard.vpn.FasVpnService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_VPN = 10;
    private static final int REQ_OPEN = 21;

    private static final int BG = 0xFF0B1220;
    private static final int CARD = 0xFF131C2E;
    private static final int ACCENT = 0xFF2EE6A8;
    private static final int TEXT = 0xFFE6EDF7;
    private static final int MUTED = 0xFF8A9BB5;
    private static final int DANGER = 0xFFFF6B6B;
    private static final int STROKE = 0xFF22304A;

    private Prefs prefs;
    private ProfileStore store;

    private FrameLayout content;
    private final View[] views = new View[5];
    private int tab = 0;
    private final TextView[] tabViews = new TextView[5];

    private TextView headerStatus;
    private Button power;
    private TextView hStatus, hStats, hTotals, hProxy, hDns;
    private TextView logView;
    private String lastLog = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (tab == 0) refreshHome();
            if (tab == 4) refreshLog();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        store = new ProfileStore(this);
        setContentView(buildRoot());
        select(0);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        updatePower();
        if (tab == 0) refreshHome();
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    /* ================= shell ================= */

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(16), dp(18), dp(10));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = tv("FAS Guard", 24, TEXT, true);
        headerStatus = tv("Firewall · Shaper · Proxy", 12, MUTED, false);
        titles.addView(title);
        titles.addView(headerStatus);

        power = new Button(this);
        power.setAllCaps(false);
        power.setText("OFF");
        power.setTextSize(15);
        power.setTextColor(0xFF07130E);
        power.setTypeface(Typeface.DEFAULT_BOLD);
        power.setPadding(dp(22), dp(10), dp(22), dp(10));
        power.setBackground(bg(ACCENT, 24));
        power.setOnClickListener(v -> {
            if (FasVpnService.isRunning()) {
                FasVpnService.stop(this);
                handler.postDelayed(this::updatePower, 500);
            } else {
                startVpn();
            }
        });

        head.addView(titles);
        head.addView(power);
        root.addView(head);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(10), 0, dp(10), dp(4));
        String[] names = {"Home", "Apps", "VPN", "Proxy", "Logs"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = tv(names[i], 13, MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            t.setOnClickListener(v -> select(idx));
            tabViews[i] = t;
            tabs.addView(t);
        }
        root.addView(tabs);

        View divider = new View(this);
        divider.setBackgroundColor(STROKE);
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        root.addView(divider);

        content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(content);
        return root;
    }

    private void select(int i) {
        tab = i;
        for (int k = 0; k < tabViews.length; k++) {
            tabViews[k].setTextColor(k == i ? ACCENT : MUTED);
            tabViews[k].setTypeface(k == i ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (i == 1) views[i] = null;
        if (i == 2) views[i] = null;
        if (views[i] == null) views[i] = buildTab(i);
        content.removeAllViews();
        content.addView(views[i]);
        if (i == 0) refreshHome();
        if (i == 4) refreshLog();
    }

    private View buildTab(int i) {
        switch (i) {
            case 0: return buildHome();
            case 1: return buildApps();
            case 2: return buildProfiles();
            case 3: return buildProxy();
            default: return buildLog();
        }
    }

    /* ================= home ================= */

    private View buildHome() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = col();
        root.setPadding(dp(14), dp(14), dp(14), dp(28));

        LinearLayout hero = card();
        hStatus = tv("STOPPED", 13, DANGER, true);
        hStats = tv("↑ 0 B   ↓ 0 B", 22, TEXT, true);
        hTotals = tv("0 blocked · 0 flows", 12, MUTED, false);
        hProxy = tv("Proxy: Direct", 12, MUTED, false);
        hDns = tv("DNS: " + prefs.dns(), 12, MUTED, false);
        hero.addView(hStatus);
        hero.addView(spacer(6));
        hero.addView(hStats);
        hero.addView(hTotals);
        hero.addView(spacer(8));
        hero.addView(hProxy);
        hero.addView(hDns);
        root.addView(hero);
        root.addView(spacer(12));

        LinearLayout switches = card();
        switches.addView(tv("Protection", 15, TEXT, true));
        switches.addView(spacer(6));
        CheckBox fw = check("Per-app firewall (block selected apps)", prefs.firewall());
        fw.setOnCheckedChangeListener((v, c) -> prefs.setFirewall(c));
        switches.addView(fw);
        CheckBox v6 = check("Block IPv6 leaks", prefs.blockIpv6());
        v6.setOnCheckedChangeListener((v, c) -> prefs.setBlockIpv6(c));
        switches.addView(v6);
        CheckBox quic = check("Block QUIC (force TCP fallback)", prefs.blockQuic());
        quic.setOnCheckedChangeListener((v, c) -> {
            prefs.setBlockQuic(c);
            toast("Reconnect to apply");
        });
        switches.addView(quic);
        CheckBox boot = check("Start on boot", prefs.autoStart());
        boot.setOnCheckedChangeListener((v, c) -> prefs.setAutoStart(c));
        switches.addView(boot);
        root.addView(switches);
        root.addView(spacer(12));

        LinearLayout lim = card();
        lim.addView(tv("Global speed limit (kbit/s · 0 = unlimited)", 15, TEXT, true));
        lim.addView(spacer(8));
        EditText down = num(String.valueOf(prefs.globalDownKbps()), "Download");
        EditText up = num(String.valueOf(prefs.globalUpKbps()), "Upload");
        lim.addView(labeled("Download", down));
        lim.addView(labeled("Upload", up));
        Button applyLim = btn("Apply limits");
        applyLim.setOnClickListener(v -> {
            prefs.setGlobalLimits(parse(down), parse(up));
            toast("Reconnect to apply the new limits");
        });
        lim.addView(applyLim);
        root.addView(lim);
        root.addView(spacer(12));

        LinearLayout dns = card();
        dns.addView(tv("DNS resolver", 15, TEXT, true));
        EditText dnsField = input(prefs.dns(), "1.1.1.1");
        dnsField.setInputType(InputType.TYPE_CLASS_TEXT);
        dns.addView(labeled("Server", dnsField));
        Button applyDns = btn("Apply DNS");
        applyDns.setOnClickListener(v -> {
            prefs.setDns(dnsField.getText().toString().trim());
            toast("Reconnect to apply DNS");
            hDns.setText("DNS: " + prefs.dns());
        });
        dns.addView(applyDns);
        root.addView(dns);
        root.addView(spacer(12));

        LinearLayout tools = card();
        tools.addView(tv("Maintenance", 15, TEXT, true));
        Button restart = btn("Restart tunnel");
        restart.setOnClickListener(v -> {
            FasVpnService.stop(this);
            handler.postDelayed(() -> {
                FasVpnService.start(this);
                handler.postDelayed(this::updatePower, 800);
            }, 600);
        });
        tools.addView(restart);
        Button reset = btn("Reset lifetime counters");
        reset.setOnClickListener(v -> {
            prefs.resetTotals();
            refreshHome();
            toast("Counters reset");
        });
        tools.addView(reset);
        root.addView(tools);

        sv.addView(root);
        return sv;
    }

    private void refreshHome() {
        boolean run = FasVpnService.isRunning();
        if (power != null) {
            power.setText(run ? "ON" : "OFF");
            power.setBackground(bg(run ? ACCENT : 0xFF33415C, 24));
            power.setTextColor(run ? 0xFF07130E : TEXT);
        }
        if (hStatus == null) return;
        hStatus.setText(run ? "PROTECTED" : "STOPPED");
        hStatus.setTextColor(run ? ACCENT : DANGER);
        hStats.setText(String.format(Locale.US, "↑ %s   ↓ %s",
                FasVpnService.human(FasVpnService.upBytes()),
                FasVpnService.human(FasVpnService.downBytes())));
        hTotals.setText(String.format(Locale.US, "%d blocked · %d flows · lifetime ↓ %s",
                FasVpnService.blockedCount(), FasVpnService.flowCount(),
                FasVpnService.human(prefs.totalDown())));
        hProxy.setText("Proxy: " + Proxy.describe(prefs));
        hDns.setText("DNS: " + prefs.dns());
        if (headerStatus != null) headerStatus.setText(run ? FasVpnService.statusText() : "Firewall · Shaper · Proxy");
    }

    private void updatePower() {
        boolean run = FasVpnService.isRunning();
        if (power == null) return;
        power.setText(run ? "ON" : "OFF");
        power.setBackground(bg(run ? ACCENT : 0xFF33415C, 24));
        power.setTextColor(run ? 0xFF07130E : TEXT);
    }

    /* ================= apps ================= */

    private View buildApps() {
        LinearLayout root = col();
        root.setPadding(dp(14), dp(12), dp(14), 0);

        EditText search = input("", "Search applications…");
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(search);
        root.addView(spacer(8));

        TextView note = tv("Uncheck an app to block all of its traffic. Limits apply per UID after reconnecting.", 11, MUTED, false);
        root.addView(note);
        root.addView(spacer(8));

        ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final List<Apps.Info> all = Apps.all(this);
        final List<Apps.Info> shown = new ArrayList<>(all);
        final AppAdapter adapter = new AppAdapter(shown);
        list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim().toLowerCase(Locale.US);
                shown.clear();
                for (Apps.Info i : all) {
                    if (q.isEmpty() || i.label.toLowerCase(Locale.US).contains(q)
                            || i.pkg.toLowerCase(Locale.US).contains(q)) shown.add(i);
                }
                adapter.notifyDataSetChanged();
            }
        });
        root.addView(list);
        return root;
    }

    private final class AppAdapter extends BaseAdapter {
        private final List<Apps.Info> items;
        AppAdapter(List<Apps.Info> items) { this.items = items; }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View reuse, ViewGroup parent) {
            Apps.Info info = items.get(pos);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(bg(CARD, 14));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(6);
            row.setLayoutParams(rlp);

            ImageView icon = new ImageView(MainActivity.this);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(38), dp(38));
            ilp.rightMargin = dp(10);
            icon.setLayoutParams(ilp);
            if (info.icon != null) icon.setImageDrawable(info.icon);
            row.addView(icon);

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            texts.addView(tv(info.label, 14, TEXT, false));
            TextView sub = tv(info.pkg + "  ·  " + (info.system ? "system" : "user") + "  ·  uid " + info.uid, 10, MUTED, false);
            texts.addView(sub);
            int d = prefs.downKbps(info.pkg), u = prefs.upKbps(info.pkg);
            if (d > 0 || u > 0) texts.addView(tv("limit ↓" + d + " ↑" + u + " kbit/s", 10, ACCENT, false));
            row.addView(texts);

            Button limit = new Button(MainActivity.this);
            limit.setAllCaps(false);
            limit.setText("Limit");
            limit.setTextSize(11);
            limit.setTextColor(ACCENT);
            limit.setBackground(bg(0xFF1B2740, 12));
            limit.setPadding(dp(10), dp(4), dp(10), dp(4));
            limit.setOnClickListener(v -> limitDialog(info));
            row.addView(limit);

            CheckBox allow = check("Allow", !prefs.isBlocked(info.pkg));
            allow.setTextSize(11);
            allow.setOnCheckedChangeListener((v, c) -> {
                java.util.Set<String> set = prefs.blockedPackages();
                if (c) set.remove(info.pkg); else set.add(info.pkg);
                prefs.setBlockedPackages(set);
                toast(c ? info.label + " allowed" : info.label + " blocked");
            });
            row.addView(allow);
            return row;
        }
    }

    private void limitDialog(Apps.Info info) {
        LinearLayout box = col();
        box.setPadding(dp(16), dp(8), dp(16), 0);
        EditText down = num(String.valueOf(prefs.downKbps(info.pkg)), "Download kbit/s");
        EditText up = num(String.valueOf(prefs.upKbps(info.pkg)), "Upload kbit/s");
        box.addView(labeled("Download (kbit/s)", down));
        box.addView(labeled("Upload (kbit/s)", up));
        box.addView(tv("0 means unlimited. Reconnect for the shaper to pick up changes.", 11, MUTED, false));
        new AlertDialog.Builder(this)
                .setTitle(info.label)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.setPerAppLimit(info.pkg, parse(down), parse(up));
                    views[1] = null;
                    toast("Reconnect to apply the limit");
                })
                .setNeutralButton("Clear", (d, w) -> {
                    prefs.setPerAppLimit(info.pkg, 0, 0);
                    views[1] = null;
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /* ================= profiles ================= */

    private View buildProfiles() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = col();
        root.setPadding(dp(14), dp(14), dp(14), dp(28));

        root.addView(tv("VPN profiles", 18, TEXT, true));
        root.addView(tv("Import .ovpn, .conf or .wg files. OpenVPN and WireGuard are driven by their official apps through secure content:// hand-off; proxy profiles are enforced inside the FAS tunnel.",
                11, MUTED, false));
        root.addView(spacer(10));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button imp = btn("Import file");
        imp.setOnClickListener(v -> importFile());
        Button manual = btn("Add proxy profile");
        manual.setOnClickListener(v -> manualProfile());
        actions.addView(imp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(spacerW(8));
        actions.addView(manual, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(actions);
        root.addView(spacer(12));

        List<Profile> list = store.list();
        if (list.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(tv("No profiles yet", 14, TEXT, true));
            empty.addView(tv("Tap “Import file” to add an OpenVPN/WireGuard configuration, or create a SOCKS5/HTTP profile manually.", 12, MUTED, false));
            root.addView(empty);
        }
        for (Profile p : list) {
            LinearLayout c = card();
            c.addView(tv(p.name, 15, TEXT, true));
            String desc = p.type.toUpperCase(Locale.US);
            if (p.host != null && !p.host.isEmpty()) desc += " · " + p.host + (p.port > 0 ? ":" + p.port : "");
            if (p.active) desc += "  ·  active";
            c.addView(tv(desc, 11, p.active ? ACCENT : MUTED, false));
            c.addView(spacer(8));
            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            Button use = btn(p.delegates() ? "Open with app" : "Activate");
            use.setOnClickListener(v -> useProfile(p));
            Button del = btn("Delete");
            del.setTextColor(DANGER);
            del.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Delete profile?")
                    .setMessage(p.name)
                    .setPositiveButton("Delete", (d, w) -> {
                        store.delete(p.id);
                        views[2] = null;
                        select(2);
                    })
                    .setNegativeButton("Cancel", null).show());
            btns.addView(use, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            btns.addView(spacerW(8));
            btns.addView(del, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            c.addView(btns);
            root.addView(c);
            root.addView(spacer(10));
        }
        sv.addView(root);
        return sv;
    }

    private void importFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(i, REQ_OPEN); }
        catch (Exception e) { toast("No file picker available"); }
    }

    private void saveImported(Uri uri) {
        try {
            String name = "profile";
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && idx >= 0) name = c.getString(idx);
                c.close();
            }
            InputStream is = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            String text = bo.toString("UTF-8");
            if (text.length() > 2_000_000) { toast("Configuration is too large"); return; }
            Profile p = store.importConfig(name, text);
            views[2] = null;
            select(2);
            toast("Imported " + p.name + " (" + p.type + ")");
        } catch (Exception e) {
            toast("Import failed: " + e.getMessage());
        }
    }

    private void manualProfile() {
        LinearLayout box = col();
        box.setPadding(dp(16), dp(8), dp(16), 0);
        EditText name = input("", "Profile name");
        Spinner type = new Spinner(this);
        ArrayAdapter<String> ta = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"socks5", "http"});
        type.setAdapter(ta);
        EditText host = input("", "Proxy host");
        EditText port = num("1080", "Port");
        EditText user = input("", "Username (optional)");
        EditText pass = input("", "Password (optional)");
        box.addView(labeled("Name", name));
        box.addView(labeled("Type", type));
        box.addView(labeled("Host", host));
        box.addView(labeled("Port", port));
        box.addView(labeled("Username", user));
        box.addView(labeled("Password", pass));
        new AlertDialog.Builder(this)
                .setTitle("New proxy profile")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    Profile p = new Profile();
                    p.name = name.getText().toString().trim().isEmpty() ? "Proxy profile" : name.getText().toString().trim();
                    p.type = type.getSelectedItem().toString();
                    p.host = host.getText().toString().trim();
                    p.port = parse(port);
                    p.user = user.getText().toString();
                    p.pass = pass.getText().toString();
                    store.add(p);
                    views[2] = null;
                    select(2);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void useProfile(Profile p) {
        if (!p.delegates()) {
            prefs.setProxy(true, p.type, p.host, p.port, p.user, p.pass);
            store.setActive(p.id);
            toast("Proxy activated — reconnect the tunnel");
            views[3] = null;
            select(3);
            return;
        }
        if (p.file == null) { toast("This profile has no configuration file"); return; }
        Uri uri = Uri.parse("content://" + com.fas.guard.data.ProfileProvider.AUTHORITY + "/" + Uri.encode(p.file));
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, p.file.endsWith(".ovpn") ? "application/x-openvpn-profile" : "application/octet-stream");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newRawUri("profile", uri));
        try {
            Intent chooser = Intent.createChooser(i, "Open profile with");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Exception e) {
            toast("Install OpenVPN for Android or WireGuard to use this profile");
        }
    }

    /* ================= proxy ================= */

    private View buildProxy() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = col();
        root.setPadding(dp(14), dp(14), dp(14), dp(28));

        root.addView(tv("Upstream proxy", 18, TEXT, true));
        root.addView(tv("All TCP traffic and TCP-DNS from the tunnel is chained through this proxy. Supports SOCKS5 (with user/password) and HTTP CONNECT.",
                11, MUTED, false));
        root.addView(spacer(10));

        LinearLayout card = card();
        CheckBox on = check("Enable proxy chaining", prefs.proxyEnabled());
        card.addView(on);

        Spinner type = new Spinner(this);
        ArrayAdapter<String> ta = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"socks5", "http"});
        type.setAdapter(ta);
        type.setSelection("http".equalsIgnoreCase(prefs.proxyType()) ? 1 : 0);
        card.addView(labeled("Protocol", type));

        EditText host = input(prefs.proxyHost(), "127.0.0.1");
        EditText port = num(String.valueOf(prefs.proxyPort()), "1080");
        EditText user = input(prefs.proxyUser(), "Username (optional)");
        EditText pass = input(prefs.proxyPass(), "Password (optional)");
        card.addView(labeled("Host", host));
        card.addView(labeled("Port", port));
        card.addView(labeled("Username", user));
        card.addView(labeled("Password", pass));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button test = btn("Test");
        test.setOnClickListener(v -> {
            Prefs p = Prefs.get(this);
            p.setProxy(on.isChecked(), type.getSelectedItem().toString(), host.getText().toString(),
                    parse(port), user.getText().toString(), pass.getText().toString());
            toast(Proxy.test(p));
        });
        Button save = btn("Save & apply");
        save.setOnClickListener(v -> {
            prefs.setProxy(on.isChecked(), type.getSelectedItem().toString(),
                    host.getText().toString(), parse(port), user.getText().toString(), pass.getText().toString());
            toast("Saved — reconnect the tunnel to apply");
            views[0] = null;
        });
        btns.addView(test, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(spacerW(8));
        btns.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(btns);
        root.addView(card);
        root.addView(spacer(12));

        LinearLayout info = card();
        info.addView(tv("Supported configurations", 14, TEXT, true));
        info.addView(tv("• SOCKS5 (RFC 1928/1929) with optional username/password\n"
                + "• HTTP CONNECT with optional Basic auth\n"
                + "• OpenVPN .ovpn profiles (via installed OpenVPN client)\n"
                + "• WireGuard .conf profiles (via official WireGuard app)\n"
                + "• Per-app firewall with UID attribution (Android 10+)\n"
                + "• Token-bucket upload/download shaping", 12, MUTED, false));
        root.addView(info);
        sv.addView(root);
        return sv;
    }

    /* ================= logs ================= */

    private View buildLog() {
        LinearLayout root = col();
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = tv("Live log", 18, TEXT, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);
        Button clear = btn("Clear");
        clear.setOnClickListener(v -> {
            FasVpnService.clearLog();
            refreshLog();
        });
        bar.addView(clear);
        root.addView(bar);
        root.addView(spacer(8));
        ScrollView sv = new ScrollView(this);
        logView = tv("", 11, 0xFFB8C7DD, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        sv.addView(logView);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        refreshLog();
        return root;
    }

    private void refreshLog() {
        if (logView == null) return;
        List<String> lines = FasVpnService.logLines();
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        String v = sb.toString();
        if (v.equals(lastLog)) return;
        lastLog = v;
        logView.setText(v);
    }

    /* ================= vpn control ================= */

    private void startVpn() {
        try {
            Intent prep = VpnService.prepare(this);
            if (prep != null) startActivityForResult(prep, REQ_VPN);
            else {
                FasVpnService.start(this);
                handler.postDelayed(this::updatePower, 700);
            }
        } catch (Exception e) {
            toast("Cannot start VPN: " + e.getMessage());
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN) {
            if (res == RESULT_OK) {
                try {
                    FasVpnService.start(this);
                    handler.postDelayed(this::updatePower, 700);
                } catch (Exception e) {
                    toast("Start failed: " + e.getMessage());
                }
            } else {
                toast("VPN permission denied");
            }
        } else if (req == REQ_OPEN && res == RESULT_OK && data != null && data.getData() != null) {
            saveImported(data.getData());
        }
    }

    /* ================= widgets ================= */

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout l = col();
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        l.setBackground(bgStroke(CARD, STROKE, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        l.setLayoutParams(lp);
        return l;
    }

    private GradientDrawable bg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private GradientDrawable bgStroke(int color, int stroke, float radius) {
        GradientDrawable g = bg(color, radius);
        g.setStroke(dp(1), stroke);
        return g;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private View spacerW(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), 1));
        return v;
    }

    private Button btn(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(0xFF07130E);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(bg(ACCENT, 14));
        b.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText input(String value, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setBackground(bgStroke(0xFF0E1727, STROKE, 10));
        e.setPadding(dp(12), dp(8), dp(12), dp(8));
        return e;
    }

    private EditText num(String value, String hint) {
        EditText e = input(value, hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private CheckBox check(String label, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(TEXT);
        c.setTextSize(13);
        c.setChecked(checked);
        return c;
    }

    private LinearLayout labeled(String label, View field) {
        LinearLayout l = col();
        l.setPadding(0, dp(6), 0, 0);
        l.addView(tv(label, 11, MUTED, false));
        l.addView(field);
        return l;
    }

    private static int parse(EditText e) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception x) { return 0; }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
