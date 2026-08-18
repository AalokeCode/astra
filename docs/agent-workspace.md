# ASTRA agent workspace

ASTRA can operate Claude Code and Codex as visible, interactive Kitty tabs.
Kitty remains the terminal and approval surface; ASTRA adds a shared control
layer for browser, typed assistant, and Gemini Live voice commands.

## Start it

From the ASTRA repository:

```bash
./scripts/astra workspace
```

Open `http://localhost:3000`, choose **Agents**, and enter `PHONE_STREAM_TOKEN`
in Connection settings. The token stays in session storage for that browser
tab. Provider credentials and MCP configuration remain on the Mac.

`./scripts/astra start` still starts the complete legacy stack: Asterisk, SIP
AudioSocket, the HTTP gateway, and the web app. The `workspace` command avoids
starting Asterisk or SIP.

## Natural commands

These requests work through ASTRA voice or its normal assistant input:

- “Launch a Claude instance in `/path/to/project` and prompt it to fix the build.”
- “Launch Codex in my ASTRA project, but don't prompt it yet.”
- “Tell me what's happening in that instance.”
- “Pause that instance and ask it to work on the failing API test instead.”
- “Steer it: preserve the existing database schema and keep the change scoped.”
- “Focus the Codex tab.”
- “Close that coding-agent instance.”

When exactly one ASTRA-managed tab is open, “it” and “that instance” resolve to
that tab. With multiple tabs, ASTRA returns their IDs and asks which target you
mean rather than guessing.

## Browser controls

- **New instance** opens a Claude Code or Codex tab in the selected project.
- **Prompt** sends a normal next task.
- **Steer** marks the message as a direction change for current work.
- **Pause** sends `SIGINT` to the foreground agent and keeps the tab open.
- **Focus tab** brings the real Kitty tab forward for approvals or direct work.
- **Close** requires a second click and closes only that managed tab.
- **Shut down** requires a second click and quits ASTRA's dedicated Kitty
  workspace, including every managed agent tab. The web app and gateway remain
  available so a later launch can create a fresh workspace.
- **Notify me** requests browser notification permission. Notifications fire
  only when an instance changes to **Needs you** or **Blocked**.

ASTRA polls the local gateway every 1.5 seconds. Terminal capture uses Kitty's
current visible screen rather than reading agent transcript databases, so the
status corresponds to what is actually on screen.

## Safety model

- Agent HTTP routes require the same 32+ character `PHONE_STREAM_TOKEN` used by
  the browser voice WebSocket.
- Browser agent control accepts only `http://localhost:3000` and
  `http://127.0.0.1:3000` origins.
- Kitty remote control uses `socket-only` mode on
  `~/.astra/run/agent-workspace.sock`; it does not open a TCP control port.
- Project paths must resolve inside `ALLOWED_DIRS` and must already exist.
- Prompts are capped at 20,000 characters. Put larger context in project files.
- Codex starts with `workspace-write` and `on-request` approvals. Claude Code
  starts in `manual` permission mode. ASTRA never enables bypass flags.
- ASTRA actions use argv arrays and Kitty stdin; prompt text is never evaluated
  by a shell.
- Prompt submission sends text without a newline and then emits a real Kitty
  `Enter` key event. This avoids Claude/Codex interpreting submission as
  Shift+Enter or multiline input.

## MCP behavior

ASTRA does not copy tokens or synthesize a second MCP configuration. Each agent
inherits its own native local MCP setup. MCP startup output is visible in the
terminal panel, and recognized failures receive a deterministic explanation.

On the Mac used during implementation, Codex reported two existing local MCP
problems:

- `figma`: OAuth token refresh failed. Re-authenticate that MCP server.
- `chrome-devtools`: the server closed during the initialize handshake. Run the
  server directly or inspect its configured command before restarting Codex.

These failures do not prevent the Codex CLI itself from opening; ASTRA marks the
instance **Blocked** so the failure is not hidden.

## Failure explanations

The gateway recognizes common quota/rate-limit, authentication, permission,
MCP, network, and missing dependency messages. It returns a summary and a next
action. Unknown output remains unclassified—ASTRA does not invent a cause.

If Kitty does not open, verify:

```bash
command -v kitty
command -v claude
command -v codex
./scripts/astra status
```

Then inspect `~/.astra/logs/gateway-console.log`. Closing the last managed Kitty
tab ends that dedicated workspace; the next launch creates a fresh one and a
new private socket.
