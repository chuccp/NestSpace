# NestSpace plugin authoring guide

This guide covers the current plugin path from source development to a distributable `.nest` package. The authoritative API is [`plugin-api`](https://github.com/chuccp/NestSpace/tree/main/plugin-api).

## 1. Start from the example

The repository provides a complete example with source resource isolation, Agent tools, settings, runtime proxy behavior, and packaging.

```bash
cp -R example-plugin/plugin dev-plugins/hello-plugin
```

Before adding features, change:

- the plugin identity in `plugin-assets/manifest.json`;
- the Gradle namespace;
- the Java package;
- `entry_class` and the entry Java class name.

The plugin module is an Android Library. In source mode, compile against the host ABI without packaging the SDK:

```kotlin
dependencies {
    compileOnly(project(":plugin-api"))
}
```

Do not use `implementation(project(":plugin-api"))`. Host and plugin must share the same API types.

## 2. Directory layout

```text
dev-plugins/hello-plugin/
├── build.gradle.kts
├── consumer-rules.pro
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/example/hello/HelloPlugin.java
    └── plugin-assets/
        ├── manifest.json
        ├── icons/icon.png
        ├── pages/detail.html
        └── contributions/
            ├── tools.json
            ├── commands.json
            └── settings.schema.json
```

`plugin-assets/manifest.json` declares plugin identity, permissions, and contributions. Android's `AndroidManifest.xml` only supports the Android Library build.

## 3. Manifest

```json
{
  "manifest_version": 1,
  "id": "com.example.hello",
  "name": "Hello Plugin",
  "version": "1.0.0",
  "publisher": "Example Studio",
  "description": "Example NestSpace plugin",
  "api_version": 1,
  "min_host_version": "1.0.7",
  "entry_class": "com.example.hello.HelloPlugin",
  "priority": 100,
  "permissions": [
    "agent",
    "storage"
  ],
  "host_permissions": [
    "https://api.example.com/*"
  ],
  "icons": {
    "128": "icons/icon.png"
  },
  "ui": {
    "html_page": "pages/detail.html"
  },
  "contributes": {
    "agent_tools": "contributions/tools.json",
    "settings": "contributions/settings.schema.json"
  }
}
```

Important fields:

- `id` is the permanent namespace for settings, storage, notifications, cache, and runtime rules;
- `entry_class` must be a Java binary class name with a no-argument constructor;
- `api_version` currently must match host API major version `1`;
- `priority` changes Hook order only and is not a permission level;
- unknown permissions and duplicate permission names are rejected;
- `entry_class`, icon, page, and contribution paths cannot escape the plugin package;
- Host rules require `network` and currently support wildcard hosts with `/*`.

Use the smallest permission set required. Prefer grouped permissions such as `agent`, `runtime`, `automation`, `storage`, `secrets`, `content`, `ui`, `notifications`, `network`, and `device`.

## 4. Lifecycle

```java
public final class HelloPlugin implements PluginLifecycle {
    private PluginContext context;

    @Override
    public void onAttach(PluginContext context) {
        this.context = context;
    }

    @Override
    public void onDetach() {
        context = null;
    }
}
```

`PluginContext` is not Android `Context`. Use it to access only the host APIs granted by the Manifest. Release network connections, subscriptions, task handles, databases, and views in `onDetach`.

## 5. Add an Agent tool

```java
public final class HelloPlugin implements
    PluginLifecycle,
    AgentToolProvider {

    private PluginContext context;

    @Override
    public void onAttach(PluginContext context) {
        this.context = context;
    }

    @Override
    public void onDetach() {
        context = null;
    }

    @Override
    public CompletableFuture<List<PluginToolSpec>> listToolsAsync() {
        String schema =
            "{\"type\":\"object\",\"properties\":{" +
            "\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}";
        return CompletableFuture.completedFuture(List.of(
            new PluginToolSpec(
                "hello",
                "Say hello",
                "Return a greeting for the supplied name",
                schema,
                false
            )
        ));
    }

    @Override
    public CompletableFuture<PluginToolResult> invokeAsync(
        PluginToolCall call
    ) {
        String name = new JSONObject(call.getArgumentsJson())
            .optString("name", "world");
        return CompletableFuture.completedFuture(
            PluginToolResult.success("Hello, " + name)
        );
    }
}
```

Set `mutating=true` for tools that change external state so the normal confirmation flow applies. Tool IDs are unique within a plugin; the host adds a plugin namespace before exposing them to Agent/MCP.

Do not block indefinitely. Long tasks should use the task API. A tool can complete immediately, return an asynchronous task ID after the synchronous wait window, or fail at its execution timeout.

## 6. Agent hooks

Available hooks include:

- `AgentHookPlugin` for prompt contributions, pre-model questions, and model requests;
- `AgentToolHookPlugin` for before/after tool calls;
- `AgentToolPresentationPlugin` for result cards and custom Views;
- `AgentToolProvider` for new tools;
- `PluginCommandProvider` for commands;
- `PluginLlmProvider` and `PluginSpeechProvider` for providers.

Question and model-request hooks can bypass or alter normal model behavior, so they require the `agent` permission and should be narrowly scoped.

## 7. Declarative settings

The host renders `PluginSettingView`; plugins do not create the settings page View themselves. Declare settings in `contributes.settings`, then read the same namespace through `host.settings()` and `host.secrets()`.

Store API keys and other credentials with `secrets()`, not ordinary settings. Both are plugin-scoped and encrypted where required.

## 8. Host APIs

Host APIs cover:

- private file storage, cache, settings, secrets, and SQLite;
- HTTP, SSE, and WebSocket within `host_permissions`;
- file picker/export, sharing, and clipboard;
- notifications, commands, managed tasks, and events;
- a restricted Android API facade;
- declarative UI pages and result renderers.

Check the current capability constants in [`plugin-api`](https://github.com/chuccp/NestSpace/tree/main/plugin-api) before relying on a method. Capability failures should be shown as explicit errors.

## 9. LLM and speech providers

`PluginLlmProvider` receives the normalized messages, tools, streaming flag, and model request. It returns text, tool calls, stop reason, and usage. Users select it by configuring `plugin://<provider-id>`.

`PluginSpeechProvider` returns `PluginSpeechAudio` containing PCM16LE data. It cannot play audio directly; the host handles playback, queueing, stop, AudioFocus, and speaker output.

## 10. Runtime proxies

`RuntimeProxyPlugin` contributes target matching and launch decisions:

```text
CONTAINER | HOST_FALLBACK | BLOCKED
```

Version matching supports exact values, prefixes, wildcards, and ranges. For deeper Application/Activity behavior, implement `RuntimeProfileProvider`; the host automatically attaches and detaches the profile with the plugin.

Keep package/version-specific rules outside the main container implementation. A runtime profile that assumes a particular target version should fail closed when the target does not match.

## 11. Debug source plugins

NestSpace discovers `example-plugin/plugin` and directories under `dev-plugins/*` in Debug builds. Run the host from Android Studio, then open **Plugin Center**. Source plugins show a **Source** badge.

If a plugin does not appear:

- verify the plugin ID is unique;
- check `entry_class`;
- check the Gradle namespace and Java package;
- inspect `PluginSourceLoader` logs and Manifest validation errors.

## 12. Package and share

There are two supported paths:

- Android Studio Debug build: source plugin is loaded directly;
- Plugin Center: **Share** or **Export** produces an installable `.nest` package.

The development package is a ZIP-like file containing:

```text
manifest.json
classes.dex
contributions/
pages/
assets/
```

The current `.nest` installer validates paths, archive size, entry count, Manifest, API version, and DEX. Publisher signatures, a trust store, atomic upgrade/rollback, and isolated plugin processes are still future work.

## 13. Before publishing

Verify at minimum:

- Java 11 compilation and R8/consumer rules;
- permission minimization and host allowlists;
- Agent question/request behavior and fallback when a hook fails;
- mutating-tool confirmation and task timeout/cancel behavior;
- settings/secrets persistence and detach cleanup;
- network cancellation and size limits;
- runtime target matching and blocked-policy behavior;
- `.nest` install, restart recovery, disable, export, and uninstall cleanup.

## Related documentation

- [Plugin platform](plugin-center.md)
- [AI assistant](assistant.md)
- [MCP tool reference](mcp-tools.md)
- [Example plugin](https://github.com/chuccp/NestSpace/tree/main/example-plugin)
