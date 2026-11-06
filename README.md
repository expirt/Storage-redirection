# Storage-redirection
tired of being locked out of your own data.?
# Storage Redirect

Xposed module for rootless **LSPatch** (also works under LSPosed). Redirects the hooked app's
storage to a custom folder on shared storage, so data is read and written there from the first
millisecond instead of the default paths.

It is generic: it redirects whichever app it is embedded in / scoped to (no hardcoded package).
Layout: `<root>/<package>/{internal, external, storage}` (default root: `/sdcard/CustomData`).

## What can be redirected (switches in the module's UI)

| Switch | Redirects |
|---|---|
| Internal data | `/data/data/<pkg>`: `getFilesDir`, `getCacheDir`, `getDir`, `getDatabasePath`, no_backup |
| SharedPreferences | `shared_prefs` (needs Internal data) |
| App external data | `/Android/data/<pkg>` and `/Android/obb/<pkg>` |
| Main storage | `/storage/emulated/0` becomes a private virtual sdcard per app |
| Hardcoded paths | Rewrites raw `/data/data/...`, `/sdcard/...` strings in `File(String)` |

`getCodeCacheDir` and the `webview` dir are deliberately left in real private storage.

## Use

1. Install `releases/StorageRedirect-v2.0.apk`, open it, grant all-files access, pick switches.
2. Patch the target app with LSPatch and embed this module.
3. The **target app** also needs all-files access (`MANAGE_EXTERNAL_STORAGE`, Android 11+),
   granted before first launch. Without it the hooks fall back to the default paths
   (logged as `[StorageRedirect] cannot create ...`).
4. Force stop the app after changing settings; they are read at app start.

Settings are stored in `/sdcard/CustomData/.config/redirect.properties`
(rootless mode has no shared-preferences channel between module and target).

## Build

```
sudo apt install openjdk-21-jdk-headless aapt apksigner dalvik-exchange libandroid-23-java zipalign
./build.sh        # -> build/StorageRedirect.apk (signed with a throwaway keystore.jks, gitignored)
```

Xposed API classes in `stubs/` are compile-only stubs and are not packaged.

## Limitations

- Native code (`open`/`fopen`) and NIO `Path` calls are not intercepted.
- Main storage only affects the app's own file calls, not files written by system services.
- Settings are global (no per-app overrides yet).
