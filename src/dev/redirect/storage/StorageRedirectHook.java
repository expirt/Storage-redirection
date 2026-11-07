package dev.redirect.storage;

import android.os.Environment;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Redirects the hooked app's storage to the folders configured for internal data, app external
 * data and main storage (each fully customizable, "{pkg}" = package name).
 *
 * Two modes (per app, set in the module's UI):
 *  - normal : the app is handed the custom folders (paths it sees are the real custom paths).
 *  - fake   : the app keeps seeing the STANDARD paths (/data/user/0/<pkg>, /storage/emulated/0/...,
 *             /storage/emulated/0/Android/data/<pkg>) and every Java-level file operation on them is
 *             redirected underneath (libcore Os / UnixFileSystem / SQLite hooks).
 *
 * Every hook is a "before" hook that sets a result only if the custom dir exists or could be
 * created; on any failure the original method runs, so the app never crashes because of this module.
 */
public class StorageRedirectHook implements IXposedHookLoadPackage {

    /** getDir() names left in the real private dir (WebView needs a real private dir). */
    private static final Set<String> KEEP_PRIVATE_DIRS = new HashSet<>(Arrays.asList("webview"));
    /** Sub-paths of the private data dir that are never redirected (dex/odex, native libs). */
    private static final String[] KEEP_PRIVATE_SUBPATHS = {"/code_cache", "/app_webview", "/lib"};
    private static final String TAG = "[StorageRedirect] ";

    /** libcore.io.Linux methods that take file paths (hooked in fake mode). */
    private static final Set<String> LINUX_PATH_METHODS = new HashSet<>(Arrays.asList(
            "access", "chmod", "chown", "lchown", "lstat", "stat", "mkdir", "mkfifo", "open",
            "readlink", "realpath", "remove", "rename", "statvfs", "symlink", "link",
            "utimensat", "setxattr", "getxattr", "removexattr", "listxattr"));

    private static String sPkg;
    private static String sSd;          // real storage root, e.g. /storage/emulated/0
    private static String sDataDir;     // real private data dir, e.g. /data/user/0/<pkg>
    private static File sInternalRoot;
    private static File sExternalRoot;
    private static File sStorageRoot;
    private static boolean sFake;
    private static String[] sOwn = new String[0];     // our destination dirs: never rewritten again
    private static String[] sFrom = new String[0];
    private static String[] sTo = new String[0];
    private static int[] sKind = new int[0];
    private static final int K_INT = 0, K_EXT = 1, K_OBB = 2, K_STO = 3;
    private static volatile boolean sWarned;

    private enum Kind { DIR, DIRS, FILE }   // DIRS = File[] result, FILE = ensure parent only

    // resolver codes
    private static final int FILES = 1, CACHE = 2, NOBACKUP = 3, GETDIR = 4, DBPATH = 5,
            DBDIR = 6, PREFS = 7, EXT_FILES = 8, EXT_CACHE = 9, OBB = 10, MEDIA = 11,
            ENV_DATA = 12, ENV_FILES = 13, ENV_CACHE = 14, ENV_OBB = 15, ENV_MEDIA = 16,
            STORAGE_ROOT = 17, STORAGE_PUBLIC = 18;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        // Applies to whatever app this module is embedded in / scoped to.
        if (lpparam.packageName == null || "android".equals(lpparam.packageName)) return;
        sPkg = lpparam.packageName;
        try {
            // Everything that needs the REAL storage root is computed before hooking.
            sSd = Environment.getExternalStorageDirectory().getAbsolutePath();
            RedirectConfig cfg = RedirectConfig.load(sSd, sPkg);   // per-app override, else defaults
            if (!cfg.internal && !cfg.external && !cfg.storage) {
                XposedBridge.log(TAG + "all redirects disabled for " + sPkg);
                return;
            }
            sFake = cfg.fakePaths;
            sDataDir = (lpparam.appInfo != null && lpparam.appInfo.dataDir != null)
                    ? lpparam.appInfo.dataDir : "/data/user/0/" + sPkg;

            sInternalRoot = RedirectConfig.resolve(sSd, sPkg, cfg.internalPath);
            sExternalRoot = RedirectConfig.resolve(sSd, sPkg, cfg.externalPath);
            sStorageRoot = RedirectConfig.resolve(sSd, sPkg, cfg.storagePath);
            sOwn = new String[]{sInternalRoot.getAbsolutePath(), sExternalRoot.getAbsolutePath(),
                    sStorageRoot.getAbsolutePath()};
            if (cfg.internal && cfg.external && sOwn[0].equals(sOwn[1]))
                XposedBridge.log(TAG + "WARNING: internal and external use the same folder");

            if (cfg.internal)
                for (String s : new String[]{"files", "cache", "databases", "shared_prefs", "no_backup"})
                    ensure(new File(sInternalRoot, s));
            if (cfg.external) {
                ensure(new File(sExternalRoot, "files"));
                ensure(new File(sExternalRoot, "cache"));
            }
            if (cfg.storage) ensure(sStorageRoot);

            ClassLoader cl = lpparam.classLoader;
            if (cfg.internal || cfg.external) {
                Class<?> ctx = XposedHelpers.findClassIfExists("android.app.ContextImpl", cl);
                if (ctx == null) XposedBridge.log(TAG + "ContextImpl not found");
                else {
                    if (cfg.internal) hookInternal(ctx, cfg.prefs);
                    if (cfg.external) hookExternal(ctx);
                }
            }
            if (!sFake) {                       // fake mode leaves Environment untouched
                if (cfg.external) hookEnvironmentAppDirs(cl);
                if (cfg.storage) hookMainStorage(cl);
            }

            if (sFake || cfg.rawPaths || cfg.storage) {
                buildRewriteTable(cfg);
                if (sFake) hookSinks(cl);
                else hookFileConstructors();
            }
            XposedBridge.log(TAG + "active for " + sPkg + (sFake ? " [fake paths]" : "")
                    + " internal=" + (cfg.internal ? sOwn[0] : "off")
                    + " external=" + (cfg.external ? sOwn[1] : "off")
                    + " storage=" + (cfg.storage ? sOwn[2] : "off"));
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // ------------------------------------------------------------ hook groups
    // getCodeCacheDir() is deliberately NOT hooked: dex/odex output must stay private.
    private static void hookInternal(Class<?> ctx, boolean prefs) {
        hook(ctx, "getFilesDir", Kind.DIR, FILES);
        hook(ctx, "getCacheDir", Kind.DIR, CACHE);
        hook(ctx, "getNoBackupFilesDir", Kind.DIR, NOBACKUP);
        hook(ctx, "getDir", Kind.DIR, GETDIR);
        hook(ctx, "getDatabasePath", Kind.FILE, DBPATH);
        // private helpers used by openOrCreateDatabase / deleteDatabase / databaseList
        hook(ctx, "getDatabasesDir", Kind.DIR, DBDIR);
        if (prefs) hook(ctx, "getPreferencesDir", Kind.DIR, PREFS);
    }

    private static void hookExternal(Class<?> ctx) {
        hook(ctx, "getExternalFilesDir", Kind.DIR, EXT_FILES);
        hook(ctx, "getExternalFilesDirs", Kind.DIRS, EXT_FILES);
        hook(ctx, "getExternalCacheDir", Kind.DIR, EXT_CACHE);
        hook(ctx, "getExternalCacheDirs", Kind.DIRS, EXT_CACHE);
        hook(ctx, "getObbDir", Kind.DIR, OBB);
        hook(ctx, "getObbDirs", Kind.DIRS, OBB);
        hook(ctx, "getExternalMediaDirs", Kind.DIRS, MEDIA);
    }

    /** Best effort: hidden static builders some framework paths still use. */
    private static void hookEnvironmentAppDirs(ClassLoader cl) {
        Class<?> env = XposedHelpers.findClassIfExists("android.os.Environment", cl);
        if (env == null) return;
        hook(env, "buildExternalStorageAppDataDirs", Kind.DIRS, ENV_DATA);
        hook(env, "buildExternalStorageAppFilesDirs", Kind.DIRS, ENV_FILES);
        hook(env, "buildExternalStorageAppCacheDirs", Kind.DIRS, ENV_CACHE);
        hook(env, "buildExternalStorageAppObbDirs", Kind.DIRS, ENV_OBB);
        hook(env, "buildExternalStorageAppMediaDirs", Kind.DIRS, ENV_MEDIA);
    }

    /** Normal mode, main storage: the app is handed a private virtual sdcard. */
    private static void hookMainStorage(ClassLoader cl) {
        Class<?> env = XposedHelpers.findClassIfExists("android.os.Environment", cl);
        if (env == null) return;
        hook(env, "getExternalStorageDirectory", Kind.DIR, STORAGE_ROOT);
        hook(env, "getExternalStoragePublicDirectory", Kind.DIR, STORAGE_PUBLIC);
    }

    // -------------------------------------------------------------- resolver
    /** The REAL (custom) folder for a hooked call, or null to leave the call alone. */
    private static File resolve(int code, XC_MethodHook.MethodHookParam p) {
        Object a0 = (p.args != null && p.args.length > 0) ? p.args[0] : null;
        switch (code) {
            case FILES:    return new File(sInternalRoot, "files");
            case CACHE:    return new File(sInternalRoot, "cache");
            case NOBACKUP: return new File(sInternalRoot, "no_backup");
            case DBDIR:    return new File(sInternalRoot, "databases");
            case PREFS:    return new File(sInternalRoot, "shared_prefs");
            case GETDIR: {
                String n = (String) a0;
                if (n == null || KEEP_PRIVATE_DIRS.contains(n)) return null;
                return new File(sInternalRoot, "app_" + n);
            }
            case DBPATH: {   // relative names only; absolute/invalid left to the original
                String n = (String) a0;
                if (n == null || n.isEmpty() || n.indexOf('/') >= 0) return null;
                return new File(new File(sInternalRoot, "databases"), n);
            }
            case EXT_FILES: {
                File b = new File(sExternalRoot, "files");
                String t = (String) a0;
                return (t == null || t.isEmpty()) ? b : new File(b, t);
            }
            case EXT_CACHE: return new File(sExternalRoot, "cache");
            case OBB:       return new File(sExternalRoot, "obb");
            case MEDIA:     return new File(sExternalRoot, "media");
            case ENV_DATA:  return envDir(a0, p, null);
            case ENV_FILES: return envDir(a0, p, "files");
            case ENV_CACHE: return envDir(a0, p, "cache");
            case ENV_OBB:   return envDir(a0, p, "obb");
            case ENV_MEDIA: return envDir(a0, p, "media");
            case STORAGE_ROOT: return sStorageRoot;
            case STORAGE_PUBLIC: {
                String t = (String) a0;
                return (t == null || t.isEmpty()) ? sStorageRoot : new File(sStorageRoot, t);
            }
            default: return null;
        }
    }

    private static File envDir(Object pkg, XC_MethodHook.MethodHookParam p, String sub) {
        if (p.args.length != 1 || !sPkg.equals(pkg)) return null;
        return sub == null ? sExternalRoot : new File(sExternalRoot, sub);
    }

    // --------------------------------------------------------------- helpers
    private static void hook(Class<?> cls, String method, final Kind kind, final int code) {
        try {
            Set<XC_MethodHook.Unhook> hooked = XposedBridge.hookAllMethods(cls, method,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                File f = resolve(code, param);
                                if (f == null) return;
                                File ok = ensure(kind == Kind.FILE ? f.getParentFile() : f);
                                if (ok == null) return;          // fall back to the original
                                // fake mode: the app is shown the standard path, I/O goes to f
                                File shown = sFake ? toVirtual(f) : f;
                                param.setResult(kind == Kind.DIRS ? new File[]{shown} : shown);
                            } catch (Throwable t) {
                                XposedBridge.log(t);             // original runs
                            }
                        }
                    });
            if (hooked.isEmpty())
                XposedBridge.log(TAG + "not found: " + cls.getSimpleName() + "." + method);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static File ensure(File dir) {
        if (dir == null) return null;
        try {
            if (dir.exists() || dir.mkdirs() || dir.exists()) return dir;
        } catch (Throwable ignored) {
        }
        if (!sWarned) {
            sWarned = true;
            XposedBridge.log(TAG + "cannot create " + dir
                    + " (storage permission?) - falling back to default path");
        }
        return null;
    }

    // -------------------------------------------- fake mode: real -> standard path
    /** The standard-looking path an app expects for a folder inside one of our destinations. */
    static File toVirtual(File real) {
        String p = real.getAbsolutePath();
        String rest = under(p, sOwn[0]);
        if (rest != null) return new File(sDataDir + rest);
        rest = under(p, sOwn[1]);
        if (rest != null) {
            if (rest.equals("/obb") || rest.startsWith("/obb/"))
                return new File(sSd + "/Android/obb/" + sPkg + rest.substring(4));
            return new File(sSd + "/Android/data/" + sPkg + rest);
        }
        rest = under(p, sOwn[2]);
        if (rest != null) return new File(sSd + rest);
        return real;
    }

    private static String under(String p, String base) {
        if (p.equals(base)) return "";
        return p.startsWith(base + "/") ? p.substring(base.length()) : null;
    }

    // ------------------------------------------- standard path -> real (rewrite table)
    private static void buildRewriteTable(RedirectConfig cfg) {
        List<String> from = new ArrayList<>();
        List<String> to = new ArrayList<>();
        List<Integer> kinds = new ArrayList<>();
        String in = sInternalRoot.getAbsolutePath();
        String ex = sExternalRoot.getAbsolutePath();

        // Most specific first: first match wins.
        if (cfg.internal) {
            add(from, to, kinds, "/data/data/" + sPkg, in, K_INT);
            add(from, to, kinds, "/data/user/0/" + sPkg, in, K_INT);
            add(from, to, kinds, sDataDir, in, K_INT);
        }
        String[] roots = {sSd, "/sdcard", "/storage/emulated/0", "/storage/self/primary", "/mnt/sdcard"};
        if (cfg.external) {
            for (String r : roots) {
                add(from, to, kinds, r + "/Android/data/" + sPkg, ex, K_EXT);
                add(from, to, kinds, r + "/Android/obb/" + sPkg, ex, K_OBB);
            }
        }
        if (cfg.storage) {
            String st = sStorageRoot.getAbsolutePath();
            for (String r : roots) add(from, to, kinds, r, st, K_STO);
        }
        sFrom = from.toArray(new String[0]);
        sTo = to.toArray(new String[0]);
        sKind = new int[kinds.size()];
        for (int i = 0; i < sKind.length; i++) sKind[i] = kinds.get(i);
    }

    private static void add(List<String> from, List<String> to, List<Integer> kinds,
                            String f, String t, int kind) {
        from.add(f);
        to.add(t);
        kinds.add(kind);
    }

    /** Maps a standard path to its real location; anything else is returned unchanged. */
    static String rewrite(String p) {
        if (p == null || p.isEmpty() || p.charAt(0) != '/') return p;
        for (String k : sOwn) {                      // already one of our real folders
            int rl = k.length();
            if (p.startsWith(k) && (p.length() == rl || p.charAt(rl) == '/')) return p;
        }
        if (sFake && sSd != null && p.startsWith(sSd + "/")) {
            for (String k : sOwn)                    // parents of our folders (mkdirs, listing)
                if (k.startsWith(p + "/")) return p;
        }
        for (int i = 0; i < sFrom.length; i++) {
            String f = sFrom[i];
            if (!p.startsWith(f)) continue;
            int n = f.length();
            if (p.length() > n && p.charAt(n) != '/') continue;   // /data/data/com.example2
            String rest = p.substring(n);
            for (String k : KEEP_PRIVATE_SUBPATHS)
                if (rest.equals(k) || rest.startsWith(k + "/")) return p;
            return (sKind[i] == K_OBB ? sTo[i] + "/obb" : sTo[i]) + rest;
        }
        return p;
    }

    // ------------------------------------------ normal mode: rewrite File(String...)
    private static void hookFileConstructors() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args[0] instanceof String)
                        param.args[0] = rewrite((String) param.args[0]);
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookConstructor(File.class, String.class, h);
            XposedHelpers.findAndHookConstructor(File.class, String.class, String.class, h);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // ------------------------------------------------ fake mode: redirect the I/O itself
    /**
     * The app keeps File objects with standard paths. Every place that finally touches the
     * filesystem from Java gets its path argument mapped to the real folder first:
     * libcore.io.Linux (everything behind FileInputStream/FileOutputStream/RandomAccessFile,
     * File.exists/mkdir/rename/...), java.io.UnixFileSystem + File natives (listing, delete,
     * length, ...) and SQLiteConnection (database files).
     * Native (C/C++) code and java.nio.file are NOT covered.
     */
    private static void hookSinks(ClassLoader cl) {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object[] a = param.args;
                    if (a == null) return;
                    for (int i = 0; i < a.length; i++) {
                        Object o = a[i];
                        if (o instanceof String) {
                            String s = (String) o;
                            String r = rewrite(s);
                            if (!r.equals(s)) a[i] = r;
                        } else if (o instanceof File) {
                            String s = ((File) o).getPath();
                            String r = rewrite(s);
                            if (!r.equals(s)) a[i] = new File(r);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        int n = hookPathMethods("libcore.io.Linux", cl, h, LINUX_PATH_METHODS);
        n += hookPathMethods("java.io.UnixFileSystem", cl, h, null);
        n += hookPathMethods("java.io.File", cl, h, null);
        n += hookPathMethods("android.database.sqlite.SQLiteConnection", cl, h, null);
        XposedBridge.log(TAG + "fake-path sinks hooked: " + n);
    }

    /** Hooks methods that take a String/File: the named ones, or (names == null) all natives. */
    private static int hookPathMethods(String cls, ClassLoader cl, XC_MethodHook h, Set<String> names) {
        Class<?> c = XposedHelpers.findClassIfExists(cls, cl);
        if (c == null) {
            XposedBridge.log(TAG + "sink class missing: " + cls);
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            boolean pick = names != null ? names.contains(m.getName())
                    : Modifier.isNative(m.getModifiers());
            if (!pick || !takesPath(m)) continue;
            try {
                XposedBridge.hookMethod(m, h);
                n++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "can't hook " + cls + "." + m.getName() + ": " + t);
            }
        }
        return n;
    }

    private static boolean takesPath(Method m) {
        for (Class<?> t : m.getParameterTypes())
            if (t == String.class || t == File.class) return true;
        return false;
    }
}
