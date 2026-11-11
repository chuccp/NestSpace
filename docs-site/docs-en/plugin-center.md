# NestSpace plugin platform and Java SDK

NestSpace plugins are Java Android modules that extend the AI assistant, Plugin Center, Host APIs, model and speech providers, and target-app runtime compatibility without modifying the main application for every extension.

For a hands-on walkthrough, see [Plugin authoring](plugin-authoring.md).

## Current implementation

| Area | Status |
| --- | --- |
| Public `plugin-api` | Implemented as a Java 11 Android library |
| Manifest and permissions | Parsed, validated, grouped, and enforced |
| Source plugins | Automatically loaded in Debug builds |
| External plugins | Installed from `.nest` packages |
| Plugin Center | Search, details, settings, enable/disable, rename, share, export, delete |
| Agent hooks/tools | Prompt, question, model-request, tool, and presentation hooks |
| Host APIs | Storage, secrets, cache, SQLite, files, sharing, clipboard, notification, HTTP, SSE, WebSocket, device facade |
| Providers | LLM and speech providers |
| Runtime proxies | Launch policy, class index, Application/Activity hooks, version matching |
| MCP management | `plugin_list` and `plugin_manage` |

## Module model

The main project uses:

```text
:app -> :plugin-api
```

`example-plugin/plugin` and `dev-plugins/*` are loaded as source plugins in Debug builds. Source plugins share the host ClassLoader and do not require a `.nest` package during normal development.

External packages are validated, extracted, loaded with `DexClassLoader`, and restored after restart. A source plugin with the same ID takes precedence over an installed external package.

## Manifest and permissions

The plugin registry is `manifest.json`, separate from Android's `AndroidManifest.xml`.

```json
{
  "manifest_version": 1,
  "id": "com.example.plugin",
  "name": "Example Plugin",
  "version": "1.0.0",
  "api_version": 1,
  "entry_class": "com.example.plugin.ExamplePlugin",
  "permissions": [
    "agent",
    "storage",
    "network"
  ],
  "host_permissions": [
    "https://api.example.com/*"
  ],
  "contributes": {
    "agent_tools": "contributions/tools.json",
    "settings": "contributions/settings.schema.json"
  }
}
```

Current permission groups:

| Permission | Capabilities |
| --- | --- |
| `agent` | Agent prompts/questions/request hooks, tools, tool hooks, presentations, LLM and speech providers |
| `runtime` | Launch policies and target Application/Activity hooks |
| `automation` | Commands, managed tasks, and events |
| `storage` | Private files, settings, cache, and SQLite |
| `secrets` | Keystore-encrypted secrets |
| `content` | File picking/export, sharing, and clipboard |
| `ui` | Declarative pages and result renderers |
| `notifications` | Plugin notifications |
| `network` | HTTP, SSE, WebSocket, and host allowlists |
| `device` | Restricted Android API facade |

Old fine-grained permission names remain parseable for compatibility. New plugins should use the grouped names.

## Extension points

### Agent hooks

- Prompt contribution adds isolated plugin guidance.
- Pre-model question hooks can answer directly or defer to the normal model flow.
- Model request hooks can inspect, augment, or block requests.
- Tool hooks can modify or block calls; after-tool hooks are observational.
- Result presentation hooks can use text, JSON renderers, or a plugin-created Android View.

Plugin-provided tools enter the same `ToolRegistry` as built-in tools and can be exposed through local Agent and remote MCP. Permission confirmation and task lifecycle rules are shared.

### Host APIs

Declared capabilities can access:

- private files, cache, settings, SQLite, and Keystore-backed secrets;
- HTTP, SSE, and WebSocket with manifest host restrictions;
- file picker/export, sharing, clipboard, and notifications;
- a restricted Android API facade and plugin-scoped tasks/events;
- declarative `PluginSettingView` forms rendered by the host.

Plugins never receive the host Application, an arbitrary Activity, another plugin's storage, API keys, or unrestricted HTTP access.

### LLM and speech providers

An LLM provider is selected with:

```text
plugin://<provider-id>
```

The provider owns its upstream credentials. The host passes messages, tools, and streaming state through the plugin request API.

Speech providers return PCM16LE audio only. The host owns AudioFocus, playback queue, stop, cache, and speaker output. Agent/MCP tools are:

- `speech_speak`
- `speech_stop`
- `speech_status`

## Runtime proxies

`RuntimeProxyPlugin` can match a target by package name, exact/prefix/wildcard/range `versionCode`, and optional class names. It can choose:

- `CONTAINER`: keep the normal container runtime;
- `HOST_FALLBACK`: launch the host-installed app;
- `BLOCKED`: reject launch with a reason.

Deeper target hooks use `RuntimeProfileProvider`. Profiles are automatically registered and removed with the plugin lifecycle.

## Plugin Center

Plugin Center is a standalone desktop entry outside the bottom dock.

- Click a plugin to view version, publisher, capabilities, detail page, and settings.
- Long-press to enable/disable, configure, rename, share, export, or delete.
- Disabling removes hooks, tools, providers, settings, and runtime profiles from the current process while retaining installed files.
- Only external packages can be deleted; source plugins are controlled by the Debug build.

## MCP management

| Tool | Actions |
| --- | --- |
| `plugin_list` | List source, installed `.nest`, and built-in plugins |
| `plugin_manage` | install, uninstall, rename, read/write/remove settings, enable/disable, list/invoke commands |

Install sources include `https://`, `http://`, `content://`, `file://`, and readable local paths. Installation still validates paths, size, Manifest, API version, and DEX.

## `.nest` package

```text
example.nest
├── manifest.json
├── classes.dex
├── contributions/
├── pages/
└── assets/
```

Installation checks ZIP path traversal, entry count and size, Manifest schema, API compatibility, and `classes.dex`. The package is extracted into private storage and `classes.dex` is wrapped as a read-only code archive before loading.

## Security and current limits

- Plugin downloads and DEX execution are not a trusted app-store security model yet.
- Publisher signatures, trust levels, atomic upgrades/rollback, and an online market are not complete.
- External plugins do not yet run in an isolated plugin process.
- Plugins cannot read host or other plugin secrets, arbitrary files, full Agent history, or invoke unrestricted shell commands.
- Target compatibility is best-effort. NestSpace is primarily intended for validating AI capabilities and does not guarantee installed-app compatibility or security.

## Related documentation

- [Plugin authoring](plugin-authoring.md)
- [AI assistant](assistant.md)
- [MCP tool reference](mcp-tools.md)
- [Architecture & limitations](architecture.md)
