package dev.redirect.storage;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** "Backups" card of an app's settings: set up, run, list, rename and delete zip backups. */
final class BackupCard {

    private static final int CARD = 0xFF1B2026, TXT = 0xFFECEFF1, SUB = 0xFF9AA5B1,
            ACC = 0xFF7CC4FF, WARN = 0xFFFFB74D, OK = 0xFF7BD88F;
    private static final int[] LEVELS = {6, 9, 1, 0};
    private static final String[] LEVEL_NAMES = {"Normal", "Maximum", "Fast", "None (store only)"};

    // A run keeps going while the screen is rebuilt, so its state is static.
    private static BackupCard sCurrent;
    private static volatile boolean sRunning;
    private static volatile boolean sCancel;
    private static volatile String sStatus = "";
    private static volatile int sStatusColor = SUB;

    private final Activity act;
    private final String sd;
    private final String pkg;
    private final BackupConfig bc;
    private final Runnable onChanged;     // redraw the settings screen after a restore
    private final LinearLayout root;
    private final LinearLayout listBox;
    private final TextView status;
    private final Button action;
    private final Button levelBtn;
    private boolean building = true;

    BackupCard(Activity act, String sd, String pkg, Runnable onChanged) {
        this.onChanged = onChanged;
        this.act = act;
        this.sd = sd;
        this.pkg = pkg;
        this.bc = BackupConfig.load(sd, pkg);
        sCurrent = this;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        root.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        root.setLayoutParams(lp);

        root.addView(text("Zip backups of this app's redirected folders. There is no restore \u2014 "
                + "they are plain archives you can copy or open anywhere.", 12, SUB, false));

        root.addView(heading("Include"));
        toggle("Internal data", bc.internal, 0);
        toggle("App external data", bc.external, 1);
        toggle("Main storage", bc.storage, 2);

        root.addView(heading("Backup folder"));
        root.addView(field(bc.dir, BackupConfig.DEF_DIR, 0));
        root.addView(heading("File name  ({pkg} {app} {ver} {date} {time})"));
        root.addView(field(bc.name, BackupConfig.DEF_NAME, 1));

        levelBtn = new Button(act);
        levelBtn.setAllCaps(false);
        levelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int i = levelIndex();
                bc.level = LEVELS[(i + 1) % LEVELS.length];
                bc.save(sd, pkg);
                levelBtn.setText(levelLabel());
            }
        });
        levelBtn.setText(levelLabel());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = dp(10);
        root.addView(levelBtn, blp);

        action = new Button(act);
        action.setAllCaps(false);
        action.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (sRunning) { sCancel = true; sStatus = "Cancelling\u2026"; applyStatus(); }
                else askNameAndStart();
            }
        });
        root.addView(action, blp);

        status = text("", 12, SUB, false);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);

        root.addView(heading("Saved backups"));
        listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        building = false;
        refresh();
    }

    View view() { return root; }

    void refresh() {
        applyStatus();
        listBox.removeAllViews();
        final List<Backups.Item> items = Backups.list(bc.backupDir(sd, pkg));
        if (items.isEmpty()) {
            listBox.addView(text("No backups yet.", 12, SUB, false));
            return;
        }
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (final Backups.Item it : items) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            LinearLayout tx = new LinearLayout(act);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.addView(text(it.name, 14, TXT, false));
            tx.addView(text(Formatter.formatShortFileSize(act, it.size) + "  \u00B7  "
                    + df.format(new Date(it.time)), 11, SUB, false));
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));

            row.addView(action("Restore", OK, new View.OnClickListener() {
                @Override public void onClick(View v) { restoreDialog(it); }
            }));
            row.addView(action("Rename", ACC, new View.OnClickListener() {
                @Override public void onClick(View v) { renameDialog(it); }
            }));
            row.addView(action("Delete", WARN, new View.OnClickListener() {
                @Override public void onClick(View v) { deleteDialog(it); }
            }));
            listBox.addView(row);
        }
    }

    // ------------------------------------------------------------------- run
    private void askNameAndStart() {
        List<Backups.Source> src = sources();
        if (src.isEmpty()) {
            sStatus = "Nothing to back up \u2014 turn on a redirect, run the app once, and include its folder above.";
            sStatusColor = WARN;
            applyStatus();
            return;
        }
        String[] av = appInfo();
        final EditText e = new EditText(act);
        e.setSingleLine(true);
        e.setText(Backups.expand(bc.name, pkg, av[0], av[1], new Date()));
        e.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(act);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e);
        new AlertDialog.Builder(act)
                .setTitle("Backup name")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Back up", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String base = Backups.sanitize(e.getText().toString());
                        if (base.length() == 0) { toast("Invalid name"); return; }
                        start(base);
                    }
                }).show();
    }

    private void start(final String base) {
        if (sRunning) return;
        final List<Backups.Source> src = sources();
        if (src.isEmpty()) return;
        final File dir = bc.backupDir(sd, pkg);
        final int level = bc.level;
        final Map<String, String> info = new LinkedHashMap<>();
        String[] av = appInfo();
        info.put("package", pkg);
        info.put("app", av[0] == null ? pkg : av[0]);
        info.put("version", av[1] == null ? "" : av[1]);
        info.put("format", "2");
        info.put("created", new Date().toString());
        info.put("compression.level", String.valueOf(level));

        sRunning = true;
        sCancel = false;
        sStatus = "Backing up\u2026";
        sStatusColor = SUB;
        applyStatus();

        new Thread(new Runnable() {
            @Override public void run() {
                final long[] last = {0};
                Backups.Progress p = new Backups.Progress() {
                    @Override public void onProgress(int files, long bytes) {
                        long now = System.currentTimeMillis();
                        if (now - last[0] < 250) return;
                        last[0] = now;
                        post("Backing up\u2026 " + files + " files, "
                                + Formatter.formatShortFileSize(actOrNull(), bytes), SUB);
                    }
                    @Override public boolean cancelled() { return sCancel; }
                };
                String msg;
                int color;
                try {
                    File out = Backups.create(dir, base, src, level, info, p);
                    if (out == null) { msg = "Cancelled."; color = WARN; }
                    else {
                        msg = "Saved " + out.getName() + " ("
                                + Formatter.formatShortFileSize(actOrNull(), out.length()) + ")";
                        color = OK;
                    }
                } catch (Throwable t) {
                    msg = "Backup failed: " + t.getMessage()
                            + (t.getMessage() != null && t.getMessage().contains("denied")
                            ? " (grant all-files access)" : "");
                    color = WARN;
                }
                sRunning = false;
                sCancel = false;
                post(msg, color);
            }
        }).start();
    }

    private static android.content.Context actOrNull() {
        BackupCard c = sCurrent;
        return c == null ? null : c.act;
    }

    private static void post(String msg, int color) {
        post(msg, color, false);
    }

    private static void post(String msg, int color, final boolean configChanged) {
        sStatus = msg;
        sStatusColor = color;
        final BackupCard c = sCurrent;
        if (c == null) return;
        c.act.runOnUiThread(new Runnable() {
            @Override public void run() {
                c.applyStatus();
                if (!sRunning) c.refresh();
                if (configChanged && c.onChanged != null) c.onChanged.run();
            }
        });
    }

    private void applyStatus() {
        if (status == null) return;
        status.setText(sStatus);
        status.setTextColor(sStatusColor);
        status.setVisibility(sStatus.length() == 0 ? View.GONE : View.VISIBLE);
        action.setText(sRunning ? "Cancel backup" : "Back up now");
    }

    /** Only folders that are redirected, included, and exist. */
    private List<Backups.Source> sources() {
        RedirectConfig rc = RedirectConfig.load(sd, pkg);
        List<Backups.Source> out = new ArrayList<>();
        if (bc.internal && rc.internal) add(out, "internal", rc.internalPath);
        if (bc.external && rc.external) add(out, "external", rc.externalPath);
        if (bc.storage && rc.storage) add(out, "storage", rc.storagePath);
        return out;
    }

    private void add(List<Backups.Source> out, String kind, String path) {
        File d = RedirectConfig.resolve(sd, pkg, path);
        if (d.isDirectory()) out.add(new Backups.Source(kind, d));
    }

    // --------------------------------------------------------------- restore
    private void restoreDialog(final Backups.Item it) {
        if (sRunning) { toast("Another backup or restore is running"); return; }
        final Backups.Info info;
        try {
            info = Backups.readInfo(it.file);
        } catch (Throwable t) {
            info(it.name, "This file can't be restored: " + t.getMessage());
            return;
        }
        if (info.pkg != null && !info.pkg.equals(pkg)) {
            info(it.name, "This backup belongs to " + info.pkg + ", not " + pkg + ".");
            return;
        }
        if (info.sources.isEmpty()) {
            info(it.name, "This backup has no folder information, so it can't be restored automatically.");
            return;
        }

        RedirectConfig rc = RedirectConfig.load(sd, pkg);
        StringBuilder sb = new StringBuilder("Restores the backup into the folders it was made from:\n");
        for (Map.Entry<String, String> e : info.sources.entrySet()) {
            String now = currentPath(rc, e.getKey());
            sb.append("\n\u2022 ").append(e.getKey()).append(" \u2192 ").append(e.getValue());
            if (now != null && !now.equals(e.getValue())) sb.append("\n   (now set to ").append(now).append(")");
        }
        sb.append("\n\nThis app's redirect folders are set to these paths too, so it finds its data "
                + "on the next launch. Force stop the app before restoring.");

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView msg = new TextView(act);
        msg.setText(sb.toString());
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(msg);
        final CheckBox clean = new CheckBox(act);
        clean.setText("Delete the existing files in those folders first");
        box.addView(clean);

        new AlertDialog.Builder(act)
                .setTitle("Restore " + it.name + "?")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        startRestore(it, info, clean.isChecked());
                    }
                }).show();
    }

    private String currentPath(RedirectConfig rc, String kind) {
        String p = "internal".equals(kind) ? rc.internalPath
                : "external".equals(kind) ? rc.externalPath
                : "storage".equals(kind) ? rc.storagePath : null;
        return p == null ? null : RedirectConfig.resolve(sd, pkg, p).getAbsolutePath();
    }

    private void startRestore(final Backups.Item it, final Backups.Info info, final boolean clean) {
        if (sRunning) return;
        sRunning = true;
        sCancel = false;
        sStatus = "Restoring\u2026";
        sStatusColor = SUB;
        applyStatus();

        new Thread(new Runnable() {
            @Override public void run() {
                final long[] last = {0};
                Backups.Progress p = new Backups.Progress() {
                    @Override public void onProgress(int files, long bytes) {
                        long now = System.currentTimeMillis();
                        if (now - last[0] < 250) return;
                        last[0] = now;
                        post("Restoring\u2026 " + files + " files, "
                                + Formatter.formatShortFileSize(actOrNull(), bytes), SUB);
                    }
                    @Override public boolean cancelled() { return sCancel; }
                };
                String msg;
                int color;
                boolean changed = false;
                try {
                    Backups.Result r = Backups.restore(it.file, info, clean, sd, p);
                    if (r == null) {
                        msg = "Restore cancelled \u2014 the folders are only partly restored.";
                        color = WARN;
                    } else {
                        changed = applyRedirect(info);
                        msg = "Restored " + r.files + " files ("
                                + Formatter.formatShortFileSize(actOrNull(), r.bytes) + ")"
                                + (r.failed > 0 ? ", " + r.failed + " failed" : "")
                                + (changed ? ". Redirect folders set to the backup's paths."
                                : ". Couldn't update the redirect settings (grant all-files access).");
                        color = r.failed > 0 || !changed ? WARN : OK;
                    }
                } catch (Throwable t) {
                    msg = "Restore failed: " + t.getMessage();
                    color = WARN;
                }
                sRunning = false;
                sCancel = false;
                post(msg, color, changed);
            }
        }).start();
    }

    /** Points this app's redirect settings at the folders the backup came from. */
    private boolean applyRedirect(Backups.Info info) {
        RedirectConfig cfg = RedirectConfig.load(sd, pkg);      // effective config -> becomes the override
        for (Map.Entry<String, String> e : info.sources.entrySet()) {
            String kind = e.getKey(), path = e.getValue();
            if ("internal".equals(kind)) { cfg.internalPath = path; cfg.internal = true; }
            else if ("external".equals(kind)) { cfg.externalPath = path; cfg.external = true; }
            else if ("storage".equals(kind)) { cfg.storagePath = path; cfg.storage = true; }
        }
        return cfg.save(sd, pkg);
    }

    private void info(String title, String message) {
        new AlertDialog.Builder(act).setTitle(title).setMessage(message)
                .setPositiveButton("OK", null).show();
    }

    // ------------------------------------------------------- rename / delete
    private void renameDialog(final Backups.Item it) {
        final EditText e = new EditText(act);
        e.setSingleLine(true);
        e.setText(it.name);
        e.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(act);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e);
        new AlertDialog.Builder(act)
                .setTitle("Rename backup")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String err = Backups.rename(it, e.getText().toString());
                        if (err != null) toast(err);
                        refresh();
                    }
                }).show();
    }

    private void deleteDialog(final Backups.Item it) {
        new AlertDialog.Builder(act)
                .setTitle("Delete backup?")
                .setMessage(it.name + ".zip will be permanently deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (!Backups.delete(it)) toast("Delete failed");
                        refresh();
                    }
                }).show();
    }

    // --------------------------------------------------------------- widgets
    private void toggle(String title, boolean init, final int which) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.addView(text(title, 15, TXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        Switch s = new Switch(act);
        s.setChecked(init);
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton v, boolean on) {
                if (building) return;
                if (which == 0) bc.internal = on;
                else if (which == 1) bc.external = on;
                else bc.storage = on;
                bc.save(sd, pkg);
            }
        });
        row.addView(s);
        root.addView(row);
    }

    private EditText field(String value, final String def, final int which) {
        EditText e = new EditText(act);
        e.setText(value);
        e.setHint(def);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTextColor(TXT);
        e.setHintTextColor(SUB);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable ed) {
                if (building) return;
                String v = ed.toString().trim();
                if (v.length() == 0) v = def;
                if (which == 0) bc.dir = v;
                else bc.name = v;
                bc.save(sd, pkg);
                if (which == 0) refresh();
            }
        });
        return e;
    }

    private TextView action(String label, int color, View.OnClickListener l) {
        TextView t = text(label, 13, color, true);
        t.setPadding(dp(8), dp(8), dp(4), dp(8));
        t.setOnClickListener(l);
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(s, 13, ACC, true);
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int levelIndex() {
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i] == bc.level) return i;
        return 0;
    }

    private String levelLabel() {
        return "Compression: " + LEVEL_NAMES[levelIndex()] + "  (tap to change)";
    }

    /** {label, versionName} of the target app, null entries if it can't be read. */
    private String[] appInfo() {
        String label = null, ver = null;
        try {
            PackageManager pm = act.getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            ver = pi.versionName;
        } catch (Throwable ignored) {
        }
        return new String[]{label, ver};
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }
}
