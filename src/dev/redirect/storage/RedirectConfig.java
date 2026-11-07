package dev.redirect.storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Settings shared between the UI (writer) and the hook (reader, inside the target app).
 *
 *   <sdcard>/CustomData/.config/redirect.properties        defaults for every app
 *   <sdcard>/CustomData/.config/apps/<package>.properties  per-app override (file exists = override on)
 *
 * Files are the only channel that works in rootless LSPatch (no XSharedPreferences / no root).
 */
final class RedirectConfig {

    boolean internal = true;     // /data/data/<pkg>
    boolean prefs = true;        // shared_prefs (only meaningful with internal)
    boolean external = true;     // /Android/data/<pkg> + /Android/obb/<pkg>
    boolean storage = false;     // main storage /storage/emulated/0 (virtual sdcard per app)
    boolean rawPaths = false;    // rewrite hardcoded path strings
    boolean fakePaths = false;   // the app keeps seeing the standard paths; I/O is redirected underneath

    static final String DEF_INTERNAL = "CustomData/{pkg}/internal";
    static final String DEF_EXTERNAL = "CustomData/{pkg}/external";
    static final String DEF_STORAGE = "CustomData/{pkg}/storage";

    // Destination folder of each redirect. Absolute, or relative to the storage root.
    // "{pkg}" is replaced by the app's package name.
    String internalPath = DEF_INTERNAL;
    String externalPath = DEF_EXTERNAL;
    String storagePath = DEF_STORAGE;

    /** Resolves a configured path for one app to an absolute folder. */
    static File resolve(String sd, String pkg, String path) {
        String p = path.replace("{pkg}", pkg == null ? "<package>" : pkg).trim();
        return p.startsWith("/") ? new File(p) : new File(sd, p);
    }

    static File dir(String sd) {
        return new File(sd, "CustomData/.config");
    }

    /** pkg == null -> the global defaults file. */
    static File file(String sd, String pkg) {
        if (pkg == null) return new File(dir(sd), "redirect.properties");
        return new File(new File(dir(sd), "apps"), pkg + ".properties");
    }

    static boolean hasOverride(String sd, String pkg) {
        return pkg != null && file(sd, pkg).isFile();
    }

    /** Effective config for a package: its override if present, otherwise the defaults. */
    static RedirectConfig load(String sd, String pkg) {
        if (hasOverride(sd, pkg)) return read(file(sd, pkg));
        return read(file(sd, null));
    }

    static boolean deleteOverride(String sd, String pkg) {
        File f = file(sd, pkg);
        return !f.exists() || f.delete();
    }

    /** Packages that currently have an override file. */
    static Set<String> overriddenPackages(String sd) {
        Set<String> out = new HashSet<>();
        try {
            File[] fs = new File(dir(sd), "apps").listFiles();
            if (fs == null) return out;
            for (File f : fs) {
                String n = f.getName();
                if (n.endsWith(".properties"))
                    out.add(n.substring(0, n.length() - ".properties".length()));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    RedirectConfig copy() {
        RedirectConfig c = new RedirectConfig();
        c.internal = internal;
        c.prefs = prefs;
        c.external = external;
        c.storage = storage;
        c.rawPaths = rawPaths;
        c.fakePaths = fakePaths;
        c.internalPath = internalPath;
        c.externalPath = externalPath;
        c.storagePath = storagePath;
        return c;
    }

    private static RedirectConfig read(File f) {
        RedirectConfig c = new RedirectConfig();
        FileInputStream in = null;
        try {
            if (!f.isFile()) return c;
            in = new FileInputStream(f);
            Properties p = new Properties();
            p.load(in);
            c.internal = bool(p, "internal", c.internal);
            c.prefs = bool(p, "prefs", c.prefs);
            c.external = bool(p, "external", c.external);
            c.storage = bool(p, "storage", c.storage);
            c.rawPaths = bool(p, "rawPaths", c.rawPaths);
            c.fakePaths = bool(p, "fakePaths", c.fakePaths);
            // legacy single "root" key (v2.0/v2.1)
            String r = p.getProperty("root");
            if (r != null && r.trim().length() > 0) {
                r = r.trim();
                c.internalPath = r + "/{pkg}/internal";
                c.externalPath = r + "/{pkg}/external";
                c.storagePath = r + "/{pkg}/storage";
            }
            c.internalPath = str(p, "internalPath", c.internalPath);
            c.externalPath = str(p, "externalPath", c.externalPath);
            c.storagePath = str(p, "storagePath", c.storagePath);
        } catch (Throwable ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
        return c;
    }

    boolean save(String sd, String pkg) {
        FileOutputStream out = null;
        try {
            File f = file(sd, pkg);
            File d = f.getParentFile();
            if (!d.exists() && !d.mkdirs() && !d.exists()) return false;
            Properties p = new Properties();
            p.setProperty("internal", String.valueOf(internal));
            p.setProperty("prefs", String.valueOf(prefs));
            p.setProperty("external", String.valueOf(external));
            p.setProperty("storage", String.valueOf(storage));
            p.setProperty("rawPaths", String.valueOf(rawPaths));
            p.setProperty("fakePaths", String.valueOf(fakePaths));
            p.setProperty("internalPath", internalPath);
            p.setProperty("externalPath", externalPath);
            p.setProperty("storagePath", storagePath);
            out = new FileOutputStream(f);
            p.store(out, "Storage Redirect" + (pkg == null ? " (defaults)" : " - " + pkg));
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) { }
        }
    }

    private static String str(Properties p, String k, String def) {
        String v = p.getProperty(k);
        return (v == null || v.trim().length() == 0) ? def : v.trim();
    }

    private static boolean bool(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }
}
