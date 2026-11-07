package dev.redirect.storage;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** App picker: choose the defaults or an individual app to configure. */
public class MainActivity extends Activity {

    private static final int BG = 0xFF101418, CARD = 0xFF1B2026, TXT = 0xFFECEFF1,
            SUB = 0xFF9AA5B1, ACC = 0xFF7CC4FF, OK = 0xFF7BD88F, WARN = 0xFFFFB74D;

    private static final class Entry {
        String label, pkg;
        Drawable icon;
    }

    private String sd;
    private TextView status;
    private TextView defaultsInfo;
    private Button grant;
    private TextView empty;
    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    private Set<String> overridden = new java.util.HashSet<>();
    private String query = "";
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sd = Environment.getExternalStorageDirectory().getAbsolutePath();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ListView lv = new ListView(this);
        lv.setBackgroundColor(BG);
        lv.setDivider(null);
        lv.setClipToPadding(false);
        lv.setPadding(dp(16), 0, dp(16), dp(24));
        lv.addHeaderView(buildHeader(), null, false);
        adapter = new Adapter();
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                int i = pos - lv_headerCount();
                if (i >= 0 && i < shown.size()) open(shown.get(i).pkg);
            }
            private int lv_headerCount() { return 1; }
        });
        setContentView(lv);
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------------------------------------------------------------- header
    private View buildHeader() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(28), 0, 0);

        col.addView(text("Storage Redirect", 26, TXT, true));
        TextView sub = text("Set what gets redirected \u2014 for all apps, or per app.", 14, SUB, false);
        sub.setPadding(0, dp(4), 0, dp(18));
        col.addView(sub);

        LinearLayout access = card();
        status = text("", 14, TXT, false);
        access.addView(status);
        grant = new Button(this);
        grant.setText("Grant all-files access");
        grant.setAllCaps(false);
        grant.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestAccess(); }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.topMargin = dp(10);
        access.addView(grant, glp);
        col.addView(access);

        col.addView(label("DEFAULTS"));
        LinearLayout def = card();
        def.addView(text("Default settings", 16, TXT, false));
        defaultsInfo = text("Used by every app without its own settings", 12, SUB, false);
        defaultsInfo.setPadding(0, dp(2), 0, dp(4));
        def.addView(defaultsInfo);
        def.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(null); }
        });
        col.addView(def);

        col.addView(label("APPS"));
        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setTextColor(TXT);
        search.setHintTextColor(SUB);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable e) {
                query = e.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });
        col.addView(search);
        empty = text("Loading apps\u2026", 13, SUB, false);
        empty.setPadding(dp(4), dp(10), 0, dp(10));
        col.addView(empty);
        return col;
    }

    // ------------------------------------------------------------------ data
    private void loadApps() {
        new Thread(new Runnable() {
            @Override public void run() {
                PackageManager pm = getPackageManager();
                final List<Entry> list = new ArrayList<>();
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    if (ai.packageName.equals(getPackageName())) continue;
                    boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                    if (sys || pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
                    Entry e = new Entry();
                    e.pkg = ai.packageName;
                    e.label = String.valueOf(pm.getApplicationLabel(ai));
                    try { e.icon = pm.getApplicationIcon(ai); } catch (Throwable ignored) { }
                    list.add(e);
                }
                Collections.sort(list, new Comparator<Entry>() {
                    @Override public int compare(Entry a, Entry b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        all.clear();
                        all.addAll(list);
                        applyFilter();
                    }
                });
            }
        }).start();
    }

    private void applyFilter() {
        shown.clear();
        for (Entry e : all)
            if (query.length() == 0 || e.label.toLowerCase(Locale.ROOT).contains(query)
                    || e.pkg.toLowerCase(Locale.ROOT).contains(query))
                shown.add(e);
        if (empty != null)
            empty.setText(all.isEmpty() ? "Loading apps\u2026" : shown.isEmpty() ? "No matching apps" : "");
        empty.setVisibility(empty.getText().length() == 0 ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private void refresh() {
        boolean ok = hasAccess();
        status.setText(ok ? "All-files access granted \u2014 settings are saved."
                : "All-files access missing \u2014 settings can't be saved yet.");
        status.setTextColor(ok ? OK : WARN);
        grant.setVisibility(ok ? View.GONE : View.VISIBLE);
        overridden = ok ? RedirectConfig.overriddenPackages(sd) : new java.util.HashSet<String>();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void open(String pkg) {
        Intent i = new Intent(this, SettingsActivity.class);
        if (pkg != null) i.putExtra(SettingsActivity.EXTRA_PKG, pkg);
        startActivity(i);
    }

    // --------------------------------------------------------------- adapter
    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            Entry e = shown.get(i);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(10), dp(8), dp(10));

            ImageView icon = new ImageView(MainActivity.this);
            if (e.icon != null) icon.setImageDrawable(e.icon);
            row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));

            LinearLayout tx = new LinearLayout(MainActivity.this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding(dp(14), 0, dp(8), 0);
            tx.addView(text(e.label, 16, TXT, false));
            tx.addView(text(e.pkg, 12, SUB, false));
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));

            if (overridden.contains(e.pkg)) row.addView(text("Custom", 12, ACC, true));
            return row;
        }
    }

    // ---------------------------------------------------------------- access
    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return (Boolean) Environment.class.getMethod("isExternalStorageManager").invoke(null);
            } catch (Throwable t) {
                return false;
            }
        }
        if (Build.VERSION.SDK_INT >= 23)
            return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                startActivity(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"));
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.READ_EXTERNAL_STORAGE"}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        refresh();
    }

    // --------------------------------------------------------------- helpers
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView label(String s) {
        TextView t = text(s, 12, ACC, true);
        t.setPadding(dp(4), dp(18), 0, dp(8));
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
