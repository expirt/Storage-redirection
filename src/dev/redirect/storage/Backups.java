package dev.redirect.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Plain zip backups of an app's redirected folders. Layout inside a backup:
 *   backup-info.properties
 *   internal/...   external/...   storage/...      (only the sources that were included)
 * There is deliberately no restore: backups are ordinary archives you can copy or open anywhere.
 */
final class Backups {

    static final class Source {
        final String kind;   // internal | external | storage
        final File dir;
        Source(String kind, File dir) { this.kind = kind; this.dir = dir; }
    }

    static final class Item {
        File file;
        String name;   // without .zip
        long size;
        long time;
    }

    interface Progress {
        void onProgress(int files, long bytes);
        boolean cancelled();
    }

    private Backups() { }

    // ------------------------------------------------------------------ names
    /** Makes a safe file name (without extension). Returns "" if nothing usable is left. */
    static String sanitize(String n) {
        if (n == null) return "";
        n = n.trim();
        if (n.toLowerCase(Locale.ROOT).endsWith(".zip")) n = n.substring(0, n.length() - 4);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            b.append((c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0) ? '_' : c);
        }
        String s = b.toString().trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        if (s.length() > 120) s = s.substring(0, 120).trim();
        return s;
    }

    static String expand(String template, String pkg, String app, String ver, Date when) {
        String s = template
                .replace("{pkg}", pkg)
                .replace("{app}", app == null ? pkg : app)
                .replace("{ver}", ver == null ? "" : ver)
                .replace("{date}", new SimpleDateFormat("yyyyMMdd", Locale.US).format(when))
                .replace("{time}", new SimpleDateFormat("HHmmss", Locale.US).format(when));
        return sanitize(s);
    }

    static File uniqueFile(File dir, String base) {
        File f = new File(dir, base + ".zip");
        int i = 2;
        while (f.exists()) f = new File(dir, base + " (" + (i++) + ").zip");
        return f;
    }

    // ---------------------------------------------------------------- listing
    static List<Item> list(File dir) {
        List<Item> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            String n = f.getName();
            if (!f.isFile() || !n.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
            Item it = new Item();
            it.file = f;
            it.name = n.substring(0, n.length() - 4);
            it.size = f.length();
            it.time = f.lastModified();
            out.add(it);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return a.time == b.time ? a.name.compareToIgnoreCase(b.name) : (a.time < b.time ? 1 : -1);
            }
        });
        return out;
    }

    /** Returns null on success, otherwise a short error message. */
    static String rename(Item it, String newName) {
        String base = sanitize(newName);
        if (base.length() == 0) return "Invalid name";
        if (base.equals(it.name)) return null;
        File dst = new File(it.file.getParentFile(), base + ".zip");
        if (dst.exists()) return "A backup with that name already exists";
        return it.file.renameTo(dst) ? null : "Rename failed";
    }

    static boolean delete(Item it) {
        return it.file.delete();
    }

    // ----------------------------------------------------------------- create
    /**
     * Zips the sources into dir/base.zip (a unique name is used if it exists).
     * Returns the file, or null if cancelled. Written as .part first, so a failed or cancelled
     * run never leaves a half-written .zip behind.
     */
    static File create(File dir, String base, List<Source> sources, int level,
                       Map<String, String> info, Progress p) throws IOException {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists())
            throw new IOException("Can't create " + dir);
        File out = uniqueFile(dir, base);
        File part = new File(dir, out.getName() + ".part");
        String skip = dir.getAbsolutePath();
        int[] files = {0};
        long[] bytes = {0};
        boolean ok = false;
        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(part)));
            zos.setLevel(level);

            Properties pr = new Properties();
            pr.putAll(info);
            for (Source s : sources) pr.setProperty("source." + s.kind, s.dir.getAbsolutePath());
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            pr.store(bo, "Storage Redirect backup");
            ZipEntry ie = new ZipEntry("backup-info.properties");
            ie.setTime(System.currentTimeMillis());
            zos.putNextEntry(ie);
            zos.write(bo.toByteArray());
            zos.closeEntry();

            for (Source s : sources) {
                if (p.cancelled()) break;
                addTree(zos, s.dir, s.kind + "/", skip, files, bytes, p);
            }
            zos.close();
            zos = null;
            if (p.cancelled()) return null;
            if (!part.renameTo(out)) throw new IOException("Can't finalize " + out.getName());
            ok = true;
            return out;
        } finally {
            if (zos != null) try { zos.close(); } catch (Throwable ignored) { }
            if (!ok) part.delete();
        }
    }

    private static void addTree(ZipOutputStream zos, File dir, String prefix, String skip,
                                int[] files, long[] bytes, Progress p) throws IOException {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        byte[] buf = new byte[64 * 1024];
        for (File f : fs) {
            if (p.cancelled()) return;
            String path = f.getAbsolutePath();
            if (path.equals(skip) || path.startsWith(skip + "/")) continue;   // never back up the backups
            if (f.isDirectory()) {
                ZipEntry de = new ZipEntry(prefix + f.getName() + "/");
                de.setTime(f.lastModified());
                zos.putNextEntry(de);
                zos.closeEntry();
                addTree(zos, f, prefix + f.getName() + "/", skip, files, bytes, p);
            } else if (f.isFile()) {
                InputStream in = null;
                try {
                    in = new BufferedInputStream(new FileInputStream(f));
                } catch (IOException unreadable) {
                    continue;                                                  // skip files we can't read
                }
                try {
                    ZipEntry fe = new ZipEntry(prefix + f.getName());
                    fe.setTime(f.lastModified());
                    zos.putNextEntry(fe);
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                        bytes[0] += n;
                        if (p.cancelled()) break;
                    }
                    zos.closeEntry();
                    files[0]++;
                    p.onProgress(files[0], bytes[0]);
                } finally {
                    try { in.close(); } catch (Throwable ignored) { }
                }
            }
        }
    }

    // ---------------------------------------------------------------- restore
    /** What a backup says about where it came from (backup-info.properties). */
    static final class Info {
        int format = 1;
        String pkg, app, version, created;
        final Map<String, String> sources = new LinkedHashMap<>();   // kind -> absolute folder
    }

    static final class Result {
        int files, skipped, failed;
        long bytes;
    }

    static Info readInfo(File zip) throws IOException {
        ZipFile z = new ZipFile(zip);
        try {
            ZipEntry e = z.getEntry("backup-info.properties");
            if (e == null) throw new IOException("Not a Storage Redirect backup");
            Properties p = new Properties();
            InputStreamReader r = new InputStreamReader(z.getInputStream(e), "UTF-8");
            try { p.load(r); } finally { r.close(); }
            Info in = new Info();
            try { in.format = Integer.parseInt(p.getProperty("format", "1").trim()); } catch (Throwable ignored) { }
            in.pkg = p.getProperty("package");
            in.app = p.getProperty("app");
            in.version = p.getProperty("version");
            in.created = p.getProperty("created");
            for (String k : p.stringPropertyNames()) {
                String v = p.getProperty(k);
                if (v == null || v.trim().length() == 0) continue;
                if (k.startsWith("source.")) in.sources.put(k.substring(7), v.trim());
            }
            return in;
        } finally {
            z.close();
        }
    }

    /**
     * Extracts a backup back into the folders it was made from (info.sources).
     * clean = delete the existing contents of those folders first.
     * Returns null if cancelled (the restore is then incomplete).
     */
    static Result restore(File zip, Info info, boolean clean, String sdRoot, Progress p) throws IOException {
        Map<String, File> targets = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : info.sources.entrySet()) {
            File t = new File(e.getValue());
            if (!t.isAbsolute()) throw new IOException("Bad path in backup: " + e.getValue());
            targets.put(e.getKey(), t);
        }
        if (targets.isEmpty()) throw new IOException("This backup has no folder information");

        if (clean) {
            String sd = new File(sdRoot).getCanonicalPath();
            String zp = zip.getCanonicalPath();
            for (File t : targets.values()) {
                String tp = t.getCanonicalPath();
                if (sameOrInside(tp, sd) || sameOrInside(tp, zp))
                    throw new IOException("Won't clear " + tp + " (it contains the storage root or this backup)");
            }
            for (File t : targets.values()) deleteChildren(t);
        }

        Result r = new Result();
        ZipFile z = new ZipFile(zip);
        try {
            byte[] buf = new byte[64 * 1024];
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                if (p.cancelled()) return null;
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.equals("backup-info.properties")) continue;
                int slash = name.indexOf('/');
                File root = slash > 0 ? targets.get(name.substring(0, slash)) : null;
                if (root == null) { r.skipped++; continue; }
                String rel = name.substring(slash + 1);
                if (rel.length() == 0) { root.mkdirs(); continue; }

                File dest = new File(root, rel);
                String c = dest.getCanonicalPath();
                String rc = root.getCanonicalPath();
                if (!(c.equals(rc) || c.startsWith(rc + File.separator))) { r.skipped++; continue; }  // zip-slip

                try {
                    if (e.isDirectory()) { dest.mkdirs(); continue; }
                    File par = dest.getParentFile();
                    if (par != null && !par.exists()) par.mkdirs();
                    InputStream in = z.getInputStream(e);
                    OutputStream out = null;
                    try {
                        out = new BufferedOutputStream(new FileOutputStream(dest));
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            r.bytes += n;
                        }
                    } finally {
                        if (out != null) try { out.close(); } catch (Throwable ignored) { }
                        try { in.close(); } catch (Throwable ignored) { }
                    }
                    if (e.getTime() > 0) dest.setLastModified(e.getTime());
                    r.files++;
                    p.onProgress(r.files, r.bytes);
                } catch (IOException io) {
                    r.failed++;
                }
            }
        } finally {
            z.close();
        }
        return r;
    }

    /** true if b equals a or lies inside a. */
    private static boolean sameOrInside(String a, String b) {
        return b.equals(a) || b.startsWith(a.endsWith("/") ? a : a + "/");
    }

    private static void deleteChildren(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) deleteChildren(f);
            f.delete();
        }
    }
}
