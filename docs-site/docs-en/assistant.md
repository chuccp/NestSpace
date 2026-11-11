# AI assistant

The AI assistant can observe virtual apps in NestSpace, tap the interface, enter text, take screenshots, and operate the built-in browser. It always works inside "virtual space 1" and does not control other apps in the system.

![AI assistant](screenshots/assistant.jpg)

## Opening the assistant

- **AI assistant** on the desktop.
- The Floating dot at the bottom of the screen (once enabled).
- Voice assistant: Tap the circle on the Floating dot.

The desktop assistant and the voice assistant share the same conversation history, so you can keep asking follow-up questions.

## Model required

The current version does not include an offline rule-based Agent. Until a usable model is configured, the assistant shows a disconnected state and configuration button, and it does not execute commands or tool calls.

After configuring an endpoint in **Space settings → Model settings**, the assistant uses the model to plan steps and call tools. Supports:

- **Claude-compatible**: `POST /v1/messages`, using native `tools` / `tool_use` / `tool_result`.
- **OpenAI-compatible**: `POST /v1/chat/completions`, using native `tool_calls` / `tool` messages.

## Configuring a model

Entry point: **Space settings → Model settings**.

You can save multiple configurations and switch between them at any time. Each configuration contains:

| Field | Description |
| --- | --- |
| Name | Display name for the configuration |
| Protocol | Claude-compatible / OpenAI-compatible |
| Base URL | Endpoint address, for example `https://api.anthropic.com` |
| API Key | Write-only field, encrypted with the Android Keystore |
| Model | Model name required by the endpoint |
| Context size | Context token limit; default 800000, minimum 100000 |
| Max output | Visible output budget, excluding reasoning |
| Temperature | 0–2 |
| Max tool rounds | Maximum number of tool calls per conversation turn; default 128 |
| Thinking level | Quick / light / standard / deep |
| Streaming | On by default; disable it to wait for the complete response |

After saving, tap **Test connection** to send a real request and confirm that the address, Key, and model are usable.
Common compatible endpoints provide presets that fill Base URL and model automatically. A Claude-compatible endpoint automatically receives `/v1/messages` when the version path is missing.

Plugin model providers use `plugin://<provider-id>` as the Base URL and the model ID returned by the plugin. Their API Key field may be left empty because the plugin owns the upstream credentials.

The system prompt is a global setting, available under **Space settings → System prompt**, and is shared by all models. At runtime, the current time, memories, skill index, and tool rules are also appended automatically.

## Application display modes

Local assistant launches and in-app actions use a headless mirror by default. The assistant page shows a square mirror window while it operates the app. Call `hand_to_user` when login, CAPTCHA, payment, permission approval, or another user-only decision is required. If the task remains idle for 10 seconds after it finishes, the mirror and target process close together.

MCP callers and assistant task tools can choose:

- `background`: headless with no app or mirror window;
- `mirror`: headless with the square mirror window;
- `visible`: open the app normally.

Only one managed mirror is retained at a time. Assistant-controlled apps stay in the mirror unless visible mode or `hand_to_user` is explicitly requested.

## Chat interface

- The message stream shows user messages, assistant messages, and errors.
- Replies use streaming output by default; it can be disabled in each model configuration.
- Tool calls are displayed as expandable cards with running status, parameters, and results.
- Replies support basic Markdown, code blocks, copying, and text selection.
- Browser downloads show progress, speed, and remaining time in the conversation and task center.
- You can start a new conversation and return to the welcome page and quick task cards.
- **History** opens saved runs that can be restored or deleted.

![Assistant performing a multi-step task](screenshots/assistant-task.jpg)

## Thinking level

You can switch the thinking level in the chat interface; it is saved separately for each model:

| Level | Behavior |
| --- | --- |
| Quick | Execute directly and give the result, avoiding lengthy reasoning |
| Light | Make a brief assessment first, then call the necessary tools |
| Standard | Briefly analyze before acting, and verify results after tool calls |
| Deep | Break down the task and compare options first, and verify results after every tool call before continuing |

For supported models, native parameters are also sent: Claude uses `thinking.budget_tokens`, while the o series / GPT-5 / Codex use `reasoning_effort`. Other compatible endpoints use prompt constraints only, avoiding request failures caused by unsupported parameters.

## Tool scope

The assistant can use:

- `space_*`: List, install, launch, duplicate, uninstall, stop, and manage permissions;
- `ui_*`: Observe nodes, tap, input, and go back; `ui_observe` optionally includes a screenshot;
- `browser_*`: Open tabs, read pages, click, download, upload, and hand to user;
- `task_list` / `task_status` / `task_wait` / `task_cancel`: Inspect, wait for, and cancel asynchronous tasks;
- `poll_tool`: Poll an external source or UI text that has no internal task event;
- `spawn_agent`: Hand read-only subtasks to a child Agent;
- `schedule_task`: Create scheduled tasks.

Remote MCP settings tools and assistant bridge tools are not exposed to the assistant itself, avoiding recursive calls and prompt injection.

All screen inspection uses `ui_observe`. The first observation reads node IDs, text, descriptions, bounds, and clickable/scrollable/editable state. Only when nodes are missing or materially incomplete, or when the page is a Canvas, game, Flutter/Unity view, video, image, or other visual scene, pass `include_screenshot=true` to add an image.

## Built-in browser rules

- HTTP/HTTPS links and page-triggered navigation stay in the built-in browser;
- `target=_blank`, `window.open`, download popups, and `intent://` fallback URLs are kept inside;
- assistant downloads return `download_id` and `task_id`; use `task_wait` until `terminal=true`;
- only `state=succeeded` is success. `task_wait` timeouts do not cancel the task;
- each assistant session has one current download; starting a replacement cancels only that session's old download.

## Permission confirmation

**Space settings → Permissions** controls whether sensitive actions require confirmation:

- **Strict**: Installation/uninstallation, permission changes, risky taps, downloads, and more all require confirmation; reusable procedures also show a confirmation card first.
- **Standard**: Adds installation/uninstallation confirmation on top of Loose; memories and automatically drafted skills are saved directly (skills are disabled by default).
- **Loose**: Only money-related actions such as payments, checkout, transfers, and red packets prompt for confirmation; memories and automatically drafted skills are saved directly (skills are disabled by default).
- **Custom**: You choose the categories that require confirmation; when **Risky taps** confirmation is enabled, reusable procedures show a confirmation card first.

## Memory & skills

### Memories

After each conversation turn, the assistant automatically organizes key facts, user preferences, and reusable procedures. When confirmation is needed, a confirm/ignore card is shown in the conversation stream; you can also view, approve, pin, or delete entries at any time in **Space settings → Memory & skills**.

- Facts and preferences always take effect directly; reusable procedures take effect directly under Standard/Loose permissions.
- In Strict mode, or in Custom mode with **Risky taps** confirmation enabled, procedures show a confirmation card first.
- Passwords, verification codes, bank card details, and ID card information are not recorded automatically.
- Memories are stored in the app-private directory and encrypted; when used, relevant entries are sent to the current model service as part of the system prompt.

### Skills

There are 7 built-in skill packs covering information research, messages and email, content organization, downloads and uploads, settings guidance, scheduled tasks, and skill writing. The assistant reads them by index rather than putting all of them into the prompt.

- After successfully completing a task with at least 4 tool steps, the assistant automatically drafts a skill; new skills are disabled by default and an enable card is shown in the conversation.
- Each use records success or failure; 2 failures in the most recent 3 uses disable a skill automatically, and the count restarts after you re-enable it manually.
- Custom skills can be edited, support importing `SKILL.md`, and support version history and restoring older versions. Built-in skills can only be disabled; they cannot be deleted or overwritten.

## Scheduled tasks

Ask the assistant to create scheduled tasks, for example:

> Check the weather every morning at 9 and tell me only if it will rain.

One-time, daily, weekly, and fixed-interval schedules are supported. When a task is due, it runs in the original conversation and sends a result notification. You can view and delete tasks in **Space settings → Scheduled tasks**.

## Voice assistant

### Enabling the Floating dot

**Space settings → Floating dot** → turn on **Show floating dot** and grant the "Display over other apps" permission.

- The Floating dot can be dragged and automatically snaps to the edge.
- You can set separate colors for the host UI and for containers.
- You can adjust opacity and position.
- By default, the desktop and containers use the container color (blue), while the host UI uses the host color (orange).

### Using it

1. Tap the circle on the Floating dot to open the compact bottom voice assistant.
2. Tap the microphone or the circle to start/stop listening.
3. Interim recognition results are shown in real time, and the final result is sent automatically.
4. When the reply is finished, listening starts again automatically, so you can continue with follow-up questions.

The text input box is hidden by default; tap the keyboard icon in the lower-left corner to switch to text input.

### Offline speech models

- Uses Vosk for local recognition; audio never leaves the device.
- Chinese and English small models can be downloaded on demand.
- Multiple sources are provided: the HF China mirror, Hugging Face, and Vosk official; each source is tested three times and the fastest working line is selected, with automatic switching if a download is interrupted.
- The download UI shows transfer speed and estimated remaining time.
- After download, the model is loaded in the background and preloaded automatically on the next start.
- Supports two small models: Chinese and English.

## Child Agents

`spawn_agent` can hand a self-contained subtask to a read-only child Agent:

- 15 steps by default, up to 40 steps;
- It can only observe containers and browse web pages; it cannot tap, input, launch apps, install apps, or create further child tasks;
- Results are kept to 4000 characters by default, up to 8000 characters;
- Results are returned to the main Agent as untrusted data.

View and stop runs in **Space settings → Child runs**.

## Plugin extensions

Plugins can extend Agent prompts, pre-model questions, model requests, tools, tool-result views, settings, and target-app runtime profiles. Plugin tools share the permission and confirmation flow with external MCP tools. Plugins can also provide LLM and speech providers.

Open **Plugin Center** from the desktop and install a `.nest` package there. Long-press an installed plugin to configure, rename, share, export, enable/disable, or delete it. Disabling a plugin immediately removes its hooks, tools, providers, settings, and runtime profiles from the current process.

## Common issues

### Test connection fails

- Check whether the Base URL includes the correct path (Claude defaults to `https://api.anthropic.com`, OpenAI defaults to `https://api.openai.com`).
- Check whether the API Key is valid and has a remaining balance.
- Check whether the model name matches the one provided by the endpoint.
- Some compatible endpoints do not support native `thinking` or `reasoning_effort`; use prompt constraints instead or switch models.

### The assistant does not call tools

- Confirm that a model has been configured and the connection test passes.
- Confirm that the target app is installed in "virtual space 1" and has been launched.
- Increase **Max tool rounds** or reduce task complexity.
- Check the tool cards in the conversation to see whether the tools report errors.

### Voice recognition does not respond

- Confirm that recording permission has been granted.
- Confirm that the offline speech model has finished downloading.
- Switch the speech model once in **Space settings → Floating dot**.

### Scheduled tasks do not run

- Confirm that the task is enabled.
- Check whether the system restricts NestSpace's background activity or exact alarm permission.
- Some systems delay alarms in power-saving mode; allow background activity and try again.

## Related documentation

- [Settings](settings.md)
- [Built-in browser](browser.md)
- [MCP tool reference](mcp-tools.md)
