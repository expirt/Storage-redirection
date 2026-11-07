package dev.redirect.storage;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Settings for one app (EXTRA_PKG set) or the defaults (no extra). */
public class SettingsActivity extends Activity {

    static final String EXTRA_PKG = "pkg";

    private static final int BG = 0xFF101418, CARD = 0xFF1B2026, TXT = 0xFFECEFF1,
            SUB = 0xFF9AA5B1, ACC = 0xFF7CC4FF, WARN = 0xFFFFB74D;

    private static final int K_INTERNAL = 1, K_PREFS = 2, K_EXTERNAL = 3, K_STORAGE = 4, K_RAW = 5,
            K_OVERRIDE = 6, K_FAKE = 7;

    private String sd;
    private String pkg;                 // null = defaults
    private RedirectConfig cfg;
    private boolean overrideOn;         // only meaningful when pkg != null
    private boolean building = true;
    private Switch prefsSwitch;
    private Switch rawSwitch;
    private final EditText[] pathEdits = new EditText[3];
    private final TextView[] resolved = new TextView[3];
    private TextView warn;
    private BackupCard backupCard;
    private final List<View> controls = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sd = Environment.getExternalStorageDirectory().getAbsolutePath();
        pkg = getIntent().getStringExtra(EXTRA_PKG);
        overrideOn = RedirectConfig.hasOverride(sd, pkg);
        cfg = RedirectConfig.load(sd, pkg);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        building = false;
        applyEnabled();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!building && backupCard != null) backupCard.refresh();
    }

    private boolean editable() {
        return pkg == null || overrideOn;
    }

    /** A restore changed this app's redirect settings: reload them and redraw. */
    void reloadAfterRestore() {
        overrideOn = RedirectConfig.hasOverride(sd, pkg);
        cfg = RedirectConfig.load(sd, pkg);
        building = true;
        setContentView(buildUi());
        building = false;
        applyEnabled();
    }

    // -------------------------------------------------------------------- UI
    private View buildUi() {
        controls.clear();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(28), dp(20), dp(28));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));

        String title = "Default settings";
        String sub = "Used by every app that has no settings of its own.";
        if (pkg != null) {
            title = pkg;
            try {
                title = getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            sub = pkg;
        }
        col.addView(text(title, 24, TXT, true));
        TextView st = text(sub, 13, SUB, false);
        st.setPadding(0, dp(4), 0, dp(14));
        col.addView(st);

        if (pkg != null) {
            LinearLayout ov = card();
            toggle(ov, "Custom settings for this app",
                    overrideOn ? "This app ignores the defaults."
                            : "Off \u2014 this app follows the default settings.",
                    overrideOn, K_OVERRIDE, false);
            col.addView(ov);
        }

        col.addView(label("WHAT TO REDIRECT"));
        LinearLayout what = card();
        toggle(what, "Internal data", "/data/data/<pkg>  \u2192  files, cache, databases, app_*",
                cfg.internal, K_INTERNAL, true);
        prefsSwitch = toggle(what, "SharedPreferences", "Also move shared_prefs (needs Internal data)",
                cfg.prefs, K_PREFS, true);
        toggle(what, "App external data", "/Android/data/<pkg> and /Android/obb/<pkg>",
                cfg.external, K_EXTERNAL, true);
        toggle(what, "Main storage", "/storage/emulated/0  \u2192  private virtual sdcard per app",
                cfg.storage, K_STORAGE, true);
        rawSwitch = toggle(what, "Hardcoded paths", "Also rewrite raw \"/data/data/\u2026\" and \"/sdcard/\u2026\" strings",
                cfg.rawPaths, K_RAW, true);
        col.addView(what);

        col.addView(label("FAKE STANDARD PATHS"));
        LinearLayout fake = card();
        toggle(fake, "Show standard paths to the app",
                "The app keeps seeing /data/user/0/<pkg>, /storage/emulated/0 and /Android/data/<pkg>; "
                        + "file operations are redirected underneath. Java I/O only \u2014 native code "
                        + "(Unity, C++ engines) can't follow, so leave it off for those apps.",
                cfg.fakePaths, K_FAKE, true);
        col.addView(fake);

        col.addView(label("DESTINATION FOLDERS"));
        LinearLayout dest = card();
        dest.addView(text("Absolute path, or relative to " + sd + ".  {pkg} = the app's package name.",
                12, SUB, false));
        addPathField(dest, "Internal data folder", 0, cfg.internalPath, RedirectConfig.DEF_INTERNAL);
        addPathField(dest, "App external data folder", 1, cfg.externalPath, RedirectConfig.DEF_EXTERNAL);
        addPathField(dest, "Main storage folder", 2, cfg.storagePath, RedirectConfig.DEF_STORAGE);
        warn = text("", 12, WARN, false);
        warn.setPadding(0, dp(10), 0, 0);
        dest.addView(warn);
        col.addView(dest);
        updatePathInfo();

        backupCard = null;
        if (pkg != null) {      // backups are set up per app
            col.addView(label("BACKUPS"));
            backupCard = new BackupCard(this, sd, pkg, new Runnable() {
                @Override public void run() { reloadAfterRestore(); }
            });
            col.addView(backupCard.view());
        }

        TextView note = text("Changes apply the next time the app starts \u2014 force stop it first.", 12, SUB, false);
        note.setPadding(0, dp(14), 0, 0);
        col.addView(note);
        return sv;
    }

    private Switch toggle(LinearLayout parent, String title, String desc, boolean init,
                          final int key, boolean isSetting) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(title, 16, TXT, false));
        TextView d = text(desc, 12, SUB, false);
        d.setPadding(0, dp(2), dp(8), 0);
        tx.addView(d);
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));

        Switch s = new Switch(this);
        s.setChecked(init);
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton v, boolean on) {
                if (building) return;
                if (key == K_OVERRIDE) { setOverride(on); return; }
                if (!editable()) return;
                switch (key) {
                    case K_INTERNAL: cfg.internal = on; break;
                    case K_PREFS: cfg.prefs = on; break;
                    case K_EXTERNAL: cfg.external = on; break;
                    case K_STORAGE: cfg.storage = on; break;
                    case K_RAW: cfg.rawPaths = on; break;
                    case K_FAKE: cfg.fakePaths = on; break;
                }
                persist();
                updatePathInfo();
                applyEnabled();
            }
        });
        if (isSetting) controls.add(s);
        row.addView(s);
        parent.addView(row);
        return s;
    }

    private void setOverride(boolean on) {
        if (on) {
            // Start from whatever the app currently inherits.
            overrideOn = true;
            if (!cfg.save(sd, pkg)) {
                overrideOn = false;
                toast("Can't write settings \u2014 grant all-files access first");
            }
        } else {
            if (!RedirectConfig.deleteOverride(sd, pkg)) {
                toast("Can't remove the override \u2014 grant all-files access first");
            } else {
                overrideOn = false;
                cfg = RedirectConfig.load(sd, pkg);     // back to the defaults
            }
        }
        building = true;
        setContentView(buildUi());
        building = false;
        applyEnabled();
    }

    private void applyEnabled() {
        boolean ed = editable();
        for (View v : controls) {
            v.setEnabled(ed);
            v.setAlpha(ed ? 1f : 0.4f);
        }
        boolean[] on = {cfg.internal, cfg.external, cfg.storage};
        for (int i = 0; i < 3; i++) {
            if (pathEdits[i] == null) continue;
            boolean en = ed && on[i];
            pathEdits[i].setEnabled(en);
            pathEdits[i].setAlpha(en ? 1f : 0.4f);
        }
        if (prefsSwitch != null && ed) {
            prefsSwitch.setEnabled(cfg.internal);
            prefsSwitch.setAlpha(cfg.internal ? 1f : 0.4f);
        }
        if (rawSwitch != null && ed) {      // fake mode already covers hardcoded paths
            rawSwitch.setEnabled(!cfg.fakePaths);
            rawSwitch.setAlpha(cfg.fakePaths ? 0.4f : 1f);
        }
    }

    private void addPathField(LinearLayout parent, String title, final int which,
                              String value, final String def) {
        TextView t = text(title, 13, SUB, false);
        t.setPadding(0, dp(12), 0, 0);
        parent.addView(t);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTextColor(TXT);
        e.setHintTextColor(SUB);
        e.setHint(def);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable ed) {
                if (building || !editable()) return;
                String v = ed.toString().trim();
                if (v.length() == 0) v = def;
                if (which == 0) cfg.internalPath = v;
                else if (which == 1) cfg.externalPath = v;
                else cfg.storagePath = v;
                persist();
                updatePathInfo();
            }
        });
        pathEdits[which] = e;
        controls.add(e);
        parent.addView(e);
        TextView r = text("", 11, SUB, false);
        resolved[which] = r;
        parent.addView(r);
    }

    private void updatePathInfo() {
        if (resolved[0] == null || warn == null) return;
        String[] paths = {cfg.internalPath, cfg.externalPath, cfg.storagePath};
        String[] abs = new String[3];
        for (int i = 0; i < 3; i++) {
            abs[i] = RedirectConfig.resolve(sd, pkg, paths[i]).getAbsolutePath();
            resolved[i].setText((cfg.fakePaths ? "real folder \u2192 " : "\u2192 ") + abs[i]);
        }
        boolean clash = cfg.internal && cfg.external && abs[0].equals(abs[1]);
        warn.setText(clash ? "Internal and external use the same folder \u2014 their files and cache would be mixed."
                : "");
        warn.setVisibility(clash ? View.VISIBLE : View.GONE);
    }

    private void persist() {
        if (!editable()) return;
        if (!cfg.save(sd, pkg)) toast("Can't write settings \u2014 grant all-files access first");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- helpers
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(10), dp(16), dp(10));
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
