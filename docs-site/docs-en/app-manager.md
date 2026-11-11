# App installation & management

Entry point: **Space settings → App management**.

![App management and multiple instances](screenshots/app-manager.jpg)

The page has two add actions:

1. **Import app**: Select APKs already installed on the host device and install copies into the virtual space.
2. **Import APK**: Select a local APK or all files of a split APK.

Every imported app becomes a NestSpace virtual app with its own instances and data directories. The current UI no longer distinguishes "virtual apps" from desktop shortcuts and does not create system-app shortcuts.

## Import an app installed on the host

1. Tap **Import app**.
2. NestSpace lists importable host apps.
3. Select one or more apps.
4. Tap **Import**.

- Only the host APK is imported. Accounts, private data, and sign-in state are not copied.
- The first launch creates **Instance 1**.
- If the package already exists, the list warns that it will be overwritten.
- Overwriting still validates the signature, minimum system version, and container compatibility.
- System-level apps such as Gmail may be importable, but their runtime depends on system services and native libraries.

## Import an APK

1. Tap **Import APK**.
2. Select the APK in the system file picker.
3. Wait for parsing, copying, and installation.

For a split app, select all files at once:

```text
base.apk
split_config.arm64_v8a.apk
split_config.xxhdpi.apk
...
```

Selecting only the base leaves out code, native libraries, or resources, and the app may fail at launch.

| Check | Behavior on failure |
| --- | --- |
| Package name | Reject installation |
| Signature consistency | Return `SIGNATURE_MISMATCH` when the same package has a different signature |
| `minSdk` | Reject when it is higher than the current system version |
| `REQUIRE_SECURE_ENV` | Return `SECURE_ENV_REQUIRED` when the target requires a secure environment |
| ABI | Installation can succeed; launch returns `ABI_MISMATCH` when no matching native library exists |

## Virtual apps and instances

Each app row shows its icon, name, package, version, instances, launch and duplicate actions, uninstall action, and native-library state when present.

- Tap an instance to launch it.
- Tap **Launch** to use the most recently selected instance.
- Tap **Duplicate** to create another independent instance.

Each instance has separate `files`, `cache`, `code_cache`, `databases`, `getDir`, SharedPreferences, and native-library extraction directories. Instances cannot read each other's data.

### Instance details

Tap an instance to open its detail page. It shows the instance ID, creation time, last launch, environment version, compatibility settings, last probe, and actual data directory.

**Clear this instance's data** resets only that instance. Other instances of the same app and the installed APK are kept.

### Simultaneous app limit

The process pool is managed automatically by default and has up to 16 slots. When memory is tight, the least recently used background container is closed; foreground containers are kept. A fixed limit from 1–16 can be selected in **Space settings → Apps & containers → Container slots**.

## Uninstall

Uninstalling a virtual app deletes its APK, every instance, instance data, caches, databases, and SharedPreferences under the NestSpace namespace. This cannot be undone.

Plugin data uses a separate boundary. Uninstalling a virtual app does not delete plugins, and deleting a plugin does not delete virtual apps.

## Common installation issues

### `SIGNATURE_MISMATCH`

An APK with a different signature was previously installed under the same package. Uninstall the old app or rebuild with the same signature.

### `SECURE_ENV_REQUIRED`

The target requires a secure environment that NestSpace does not provide, so installation is rejected.

### `ABI_MISMATCH`

The APK's native ABIs do not match the device or selected runtime. Use a matching package; ARM64 devices and ARM-translation emulators should normally use the arm64 host APK.

### Black screen or crash after launch

This is usually caused by app hardening, native SDKs, dynamic loading, or dependency conflicts. Run **Space settings → Apps & containers → Container advanced → Container self-check** and include the app version, error, and self-check report when reporting an issue.

### Installed but no desktop icon

Check the app under **Space settings → App management** and launch it there. A reset or damaged desktop layout can hide the icon without removing the app.

## Related documentation

- [Settings](settings.md)
- [Desktop & appearance](desktop.md)
- [Architecture & limitations](architecture.md)
- [Troubleshooting](troubleshooting.md)
- [Plugin authoring guide](plugin-authoring.md)
