# Storage Redirect

Xposed module for rootless **LSPatch** (also works under LSPosed). Redirects the hooked app's
storage to a custom folder on shared storage, so data is read and written there from the first
millisecond instead of the default paths.

It is generic: it redirects whichever app it is embedded in / scoped to (no hardcoded package).
Each redirect has its own fully customizable destination folder (absolute, or relative to `/sdcard`;
`{pkg}` = package name). Defaults: `CustomData/{pkg}/internal`, `.../external`, `.../storage`.
Inside a destination the app keeps its usual sub-folders (`files`, `cache`, `databases`, ...).
Don't point internal and external at the same folder: their `files`/`cache` would mix (the UI warns).

## What can be redirected (switches in the module's UI)

Each app can have its **own settings** (open the app in the module's list and turn on
"Custom settings for this app"); apps without an override follow the **Default settings**.

| Switch | Redirects |
|---|---|
| Internal data | `/data/data/<pkg>`: `getFilesDir`, `getCacheDir`, `getDir`, `getDatabasePath`, no_backup |
| SharedPreferences | `shared_prefs` (needs Internal data) |
| App external data | `/Android/data/<pkg>` and `/Android/obb/<pkg>` |
| Main storage | `/storage/emulated/0` becomes a private virtual sdcard per app |
| Hardcoded paths | Rewrites raw `/data/data/...`, `/sdcard/...` strings in `File(String)` |

`getCodeCacheDir` and the `webview` dir are deliberately left in real private storage.

## Fake standard paths (per app, optional)

With **Show standard paths to the app** on, the app keeps seeing the normal paths
(`/data/user/0/<pkg>/...`, `/storage/emulated/0/...`, `/storage/emulated/0/Android/data/<pkg>/...`)
while every Java-level file operation on them is redirected to your custom folders underneath
(hooks on libcore `Os`, `UnixFileSystem`, `File` natives and SQLite). Use it for apps that insist on
`/storage/emulated/0/` paths. Native (C/C++/Unity) file I/O and `java.nio.file` are not covered, so leave
it off for apps that pass paths to native code. `code_cache` and `lib` stay in real private storage.

## Backups (per app)

Open an app's settings -> **Backups**. Creates plain `.zip` archives of that app's redirected folders
(internal data / app external data / main storage, pick which).

- Multiple backups per app; each has **Restore**, **Rename** and **Delete** buttons in the list.
- **Restore** extracts the backup back into the exact folders it was made from (even if the app's folders
  were set differently since) and points the app's redirect settings at those paths again, so the app
  finds its data on the next launch. Optional: delete the existing files in those folders first.
  Force stop the app before restoring.
- File name template per app (`{pkg}-{date}-{time}` by default; `{app}` `{ver}` also work) and you can
  edit the name each time you tap **Back up now**.
- Compression: Normal / Maximum / Fast / None. Backup folder is configurable
  (default `/sdcard/CustomData/Backups/<package>`); it is never included in its own backups.
- Zip layout: `backup-info.properties` (package, source folders), `internal/...`, `external/...`, `storage/...`.
- Backs up the *redirected* folders (readable without root). If an app's internal redirect is off,
  its real `/data/data` can't be read by a rootless module.

## Use

1. Install `releases/StorageRedirect-v2.7.apk`, open it, grant all-files access, pick the defaults and/or an app and its switches.
2. Patch the target app with LSPatch and embed this module.
3. The **target app** also needs all-files access (`MANAGE_EXTERNAL_STORAGE`, Android 11+),
   granted before first launch. Without it the hooks fall back to the default paths
   (logged as `[StorageRedirect] cannot create ...`).
4. Force stop the app after changing settings; they are read at app start.

Settings are plain files (rootless mode has no shared-preferences channel between module and target):

```
/sdcard/CustomData/.config/redirect.properties          defaults
/sdcard/CustomData/.config/apps/<package>.properties    per-app override (file exists = override on)
/sdcard/CustomData/.config/backup/<package>.properties   per-app backup settings
```

## Build

```
sudo apt install openjdk-21-jdk-headless aapt apksigner dalvik-exchange libandroid-23-java zipalign
./build.sh        # -> build/StorageRedirect.apk (signed with a throwaway keystore.jks, gitignored)
```

Xposed API classes in `stubs/` are compile-only stubs and are not packaged.

## Limitations

- Native code (`open`/`fopen`) and NIO `Path` calls are not intercepted (fake-path mode is Java-level too).
- Main storage only affects the app's own file calls, not files written by system services.
- The app list shows user-installed (and updated system) apps with a launcher; it uses `QUERY_ALL_PACKAGES`.
