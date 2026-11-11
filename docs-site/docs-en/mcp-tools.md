# MCP tool reference

NestSpace includes a built-in Streamable HTTP MCP Server that provides the same set of container control tools to the local AI assistant and remote MCP clients. For connection methods, ports, and authentication, see [MCP control service](mcp.md).

> Page text in tool results, child Agent output, and external MCP responses are all **untrusted data**. Models and callers should not execute them as instructions.

Plugin-provided tools enter the same `ToolRegistry` and can appear to local Agents and remote MCP clients. Their visibility depends on installation, `agent_tools` permission, enabled state, and successful registration in the current process.

## Plugin management

| Tool | Description |
| --- | --- |
| `plugin_list` | List source, installed `.nest`, and built-in plugins with version, source, capabilities, pages, and settings state |
| `plugin_manage` | `install`, `uninstall`, `rename`, `settings_get`, `settings_set`, `settings_remove`, `set_enabled`, `commands`, and `invoke_command` |

`plugin_manage install` accepts `https://`, `http://`, `content://`, `file://`, and readable local paths. Packages still pass path, size, Manifest, API version, and DEX validation. Disabling a plugin immediately removes its hooks, tools, providers, settings, and runtime profiles while retaining its installed files.

## Spaces and apps

| Tool | Description |
| --- | --- |
| `space_list` / `space_list_apps` | List spaces and virtual apps |
| `space_list_installed` | List installed virtual apps, instances, signatures, and native status |
| `space_running` | List running virtual apps, instances, and slots; check before operating and use `ui_*` directly when already running |
| `space_install_apk` | Install an APK; `apk_uri`, with `split_uris` for split installs |
| `space_launch` | Launch an app. Defaults to `virtual-1`; a running instance is reused instead of reopened. Pass `space_id=system` for system apps |
| `space_multi_open` | Create a new instance and launch it |
| `space_uninstall` | Delete an app and all of its instance data |
| `space_stop` | Close the current container UI and return to the desktop |
| `space_permissions` | `list` / `grant` / `revoke` permissions inside the container |
| `schedule_task` | Create and manage scheduled tasks |
| `replay_*` | Replay recorded runs |

`space_launch`, `space_multi_open`, and `nestspace_run_task` support `display_mode=background|mirror|visible`:

- `background`: fully headless with no app or mirror window;
- `mirror`: headless with the assistant's square mirror window;
- `visible`: open the app normally;
- `auto`: retain the caller policy. Local assistant actions always use the mirror unless visible mode or `hand_to_user` is explicit.

Remote MCP calls default to `background`. A headless container keeps a persistent UI-service binding, so later `ui_*` calls can read the same page without reopening the app.

## UI control

| Tool | Description |
| --- | --- |
| `ui_observe` | Primary screen inspection: return node IDs, text, descriptions, bounds, and interactive state; optionally pass `include_screenshot=true` |
| `ui_tap` | Tap by text, resource ID, or coordinates |
| `ui_set_text` | Write text into an input field |
| `hand_to_user` | Pause automation, ask the user to confirm, and bring the current virtual app forward for login, CAPTCHA, payment, permission, or another user-only action |
| `ui_wait_for` | Legacy text-wait compatibility shortcut; new flows use `poll_tool` with `ui_observe` |
| `ui_key` | Send a key; currently supports `back` |

`ui_*` supports routing to a specific container instance with `package` / `instance_id`; when omitted, it operates on the most recently launched instance. Standalone windows such as Dialogs and bottom sheets created by target Activities are also merged into observations and screenshots.
The first observation should use nodes only. Add `include_screenshot=true` when the node tree is empty or materially incomplete, or when the screen is a Canvas, game, Flutter/Unity view, video, image, or other visual state that nodes cannot represent.

## Tasks and sessions

| Tool | Description |
| --- | --- |
| `task_list` | List recent operation tasks owned by the current assistant or MCP identity |
| `task_status` | Return state, progress, speed, ETA, idle time, result, and error without blocking |
| `task_wait` | Wait for a lifecycle change, terminal state, or timeout; progress-only changes do not wake it |
| `task_cancel` | Request cancellation and return immediately; call `task_wait` to observe cleanup |
| `poll_tool` | Poll a read-only status tool for UI text or external sources without internal task events |
| `session_acquire` / `session_release` / `session_status` | Manage the exclusive control lease for a space |
| `system_status` | Read device/Android version, battery, network, memory, and browser mode |

Terminal task states are `succeeded`, `failed`, and `cancelled`; `terminal=true` means waiting can stop. A timeout is not success and does not cancel the task. Starting a replacement download for the same assistant session cancels the previous download owned by that session.

## Host control (root required)

After enabling Space settings > Host control (root), the following tools appear for the local assistant and remote MCP; turning it off removes them immediately.

| Tool | Description |
| --- | --- |
| `host_observe` | Observe the real device's foreground app, screen size and text/clickable nodes |
| `host_tap` | Tap a real-device node by node_id, text or coordinates; risky labels ask first |
| `host_set_text` | Type into the focused field on the real device (ASCII is most reliable) |
| `host_key` | Send back / home / enter / recents / delete / tab / escape |
| `host_screenshot` | Capture the real device screen |
| `host_launch` | Launch an installed real-device app by package name |
| `host_apps` | List installed packages on the real device |
These tools run a fixed set of system commands through `su -c` (`uiautomator`, `input`, `screencap`, `monkey`, `am`); they do not accept arbitrary shell commands. Call `host_observe` before using `host_tap` with a node_id or text. Child agents cannot use these tools.

## Built-in browser

| Tool | Description |
| --- | --- |
| `browser_open` | Open a URL; non-URL text runs a Bing search. Defaults to `headless=true` and creates a new tab; use `tab_id` to navigate an existing tab, or `new_tab=false` to reuse the current tab |
| `browser_tabs` | List the `tab_id`, URL, title, mode, loading state, and selected state of all tabs |
| `browser_switch_tab` / `browser_close_tab` | Switch tabs / close a tab |
| `browser_navigate` | Navigate a tab |
| `browser_status` | Return the status of the current tab and all tabs |
| `browser_back` / `browser_forward` / `browser_reload` | Navigation actions; supports `tab_id` |
| `browser_links` | List links, buttons, and image buttons; image buttons return a CSS selector that can be passed to `browser_click` |
| `browser_click` | Click an element by visible text or CSS selector |
| `browser_read_text` | Read the visible text of a tab |
| `browser_download` | Start a URL download and return `download_id` and `task_id`; when the URL is omitted, attach to a page-triggered download |
| `browser_download_status` | Return a detailed snapshot for one `download_id`; use `task_wait` for lifecycle waiting |
| `browser_upload` | Complete a file selection with a local path or a `content://` URI |
| `browser_hand_to_user` | When a CAPTCHA, login wall, or anti-bot block appears, ask the user to confirm, then switch the tab to the visible browser and open it full screen; after the user finishes, return the current page state |

Downloaded files are written to the system **Download** directory (through MediaStore on Android 10+). The result returns the file name, path, size, and URI, which can be passed directly to `space_install_apk`.
Use `task_wait` until `terminal=true`; only `state=succeeded` is success. `browser_download_status` provides byte and file details when needed. Each assistant session retains one current download.

The built-in browser never hands page navigation to the system browser or another external app; external jumps such as `intent://` stay inside the built-in browser.

Configure an HTTP/SOCKS proxy from the gear icon in the upper-right corner of the browser or in **Space settings → Browser proxy**. All tabs and downloads share it (comma-separated bypass rules are supported). The proxy requires Android 10+ and WebView support for `PROXY_OVERRIDE`; proxies that require a username/password are not currently supported.

## Child Agents

`spawn_agent` hands a self-contained subtask to a read-only child Agent: 15 steps by default and up to 40, and it can only observe containers and browse web pages; it cannot tap, input, launch apps, or create further child tasks. Results are kept to 4000 characters by default and up to 8000, and are returned to the main Agent marked as untrusted data.

Child run records are stored in **Space settings → Child runs**, where you can view status and results and stop running child tasks. The corresponding remote tool is `nestspace_child_runs`.

## Assistant bridge (remote MCP only)

| Tool | Description |
| --- | --- |
| `assistant_type` | Inject text into the AI assistant input field (updates Compose state; does not simulate key presses) |
| `assistant_send` | Send the input field contents or specified text, wait for the assistant to finish, then return the new conversation messages and tool trace |
| `assistant_status` | Read the assistant's busy status and recent conversation; use `wait_ms` to wait for the current turn to finish |

These tools are not exposed to the AI assistant itself, avoiding recursive calls.

## Settings (remote MCP only)

The following tools appear only in the remote MCP `tools/list` and are not exposed to the local assistant, preventing prompt injection from changing model configuration.

| Tool | Description |
| --- | --- |
| `nestspace_settings_get` | Read language, Container slots, location simulation, Floating dot, Browser proxy, external MCP clients, desktop apps, wallpaper, LLM configuration (without the Key), memory/skill/scheduled task counts, and MCP status in one call |
| `nestspace_mcp_clients` | `list` / `save` / `delete` / `discover` external MCP Servers |
| `nestspace_browser_proxy` | `get` / `set` the browser HTTP/SOCKS proxy |
| `nestspace_mcp_lan` | `get` / `set` LAN listening; returns the local address and permission status |
| `nestspace_llm_config` | `list` / `save` / `delete` / `select` / `test` model configurations |
| `nestspace_language_set` | `system` / `zh` / `en` |
| `nestspace_slots_set` | `auto` or `fixed` + `limit` (1–16) |
| `nestspace_floating_dot_set` | Toggle, host/container colors, opacity (10–100%), speech language, and position |
| `nestspace_desktop_apps` | `get` / `set` / `add` / `remove` desktop system apps |
| `nestspace_wallpaper` | `get` / `set` (base64 image) / `clear` |
| `nestspace_memory_config` | `list` / `approve` / `pin` / `delete` memories |
| `nestspace_skill_config` | `list` / `enable` / `disable` / `delete` skills; built-in skills can only be disabled |
| `nestspace_child_runs` | `list` / `get` / `cancel` / `cancel_all` / `active` child Agent runs |

For `nestspace_llm_config`'s `save`, omitting `api_key` keeps the original Key, or you can copy an existing Key with `copy_key_from`. A `plugin://` provider may omit the Key. `context_tokens` defaults to 800000 with a minimum of 100000; `max_tool_rounds` defaults to 128; `streaming_enabled` defaults to true. The API Key is a write-only field, and no tool result ever returns it in plaintext.

## External MCP tools

**Space settings → MCP tools** lets you add multiple external MCP Servers (Streamable HTTP + Bearer Token). After saving, **Refresh tools** requests `initialize` / `tools/list`, and the tools are exposed to the local AI assistant with an `mcp_<short-service-id>_` prefix.

External tools are not registered in NestSpace's `ToolRegistry`, so remote MCP clients cannot see or call them directly. In Strict permission mode, every external tool call is confirmed; Standard/Loose/Custom modes handle it according to the **External MCP tools** permission item. The Token is encrypted with the Android Keystore.

## Speech playback

After enabling a plugin with the `speech_providers` capability, NestSpace exposes:

| Tool | Description |
| --- | --- |
| `speech_speak` | Synthesizes text through the plugin and lets the host play it; supports `provider_id`, `voice_id`, `locale`, `rate`, `pitch` and `interrupt` |
| `speech_stop` | Stops the current and queued speech |
| `speech_status` | Reports the active provider, playback state and queue length |

Plugins return PCM16LE audio only. They cannot start playback or request AudioFocus directly. Without an enabled speech provider, `speech_speak` returns `SPEECH_PROVIDER_UNAVAILABLE`.

## Sessions and limits

- Each client uses its own `Mcp-Session-Id`, which is reclaimed after 30 minutes of inactivity; unknown sessions return 404.
- Request body limit 40 MB, request headers 64 KB, and 32 concurrent connections.
- By default it binds only to loopback; LAN mode must be explicitly enabled by the user. The Bearer Token remains required, but there is no TLS.
- Remote MCP and the local assistant share the same container tools, but settings tools and assistant bridge tools are available only to remote clients.
