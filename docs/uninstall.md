# Completely remove ASTRA

This removes the original macOS assistant, its Android dialer, local Asterisk
installation, runtime data, credentials, and optionally the source repository.
ASTRA Link is separate; use its own `docs/uninstall.md` for the installed PWA
and tunnel.

## 1. Back up anything you want to keep

Conversation memory, logs, quota state, and the local Asterisk installation
live under `~/.astra`. The project `.env` contains provider credentials. Copy
only the files you intentionally want to retain before continuing.

## 2. Remove the Android dialer

On the phone, open **Settings → Apps → ASTRA Dialer → Uninstall**.

With Android debugging enabled, the equivalent command is:

```bash
adb uninstall com.prenoma.assistantdialer
```

Verify that it is gone:

```bash
adb shell pm list packages | grep com.prenoma.assistantdialer
```

No output means the package is absent. An icon named **ASTRA** without
“Dialer” may instead be the ASTRA Link PWA; follow the ASTRA Link removal guide
for that icon.

## 3. Stop Mac processes

From this repository:

```bash
./scripts/astra stop
```

The launcher only stops processes that it started and whose command lines
still match. Confirm that ASTRA's listeners are gone:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
lsof -nP -iTCP:8090 -sTCP:LISTEN
lsof -nP -iUDP:5060
```

Inspect any remaining PID before terminating it; those ports may belong to an
unrelated application.

## 4. Remove local data and source

Move `~/.astra` to the Trash. That one directory contains ASTRA's database,
logs, PID files, quota state, and locally installed Asterisk tree.

Then move this repository directory to the Trash. Removing the checkout also
removes its ignored `.env`, `.venv`, web dependencies, build output, Android
build caches, and source code. Do not delete the parent Projects directory.

Empty the Trash only after confirming that no memory or configuration needs to
be recovered.

## 5. Revoke external access

- Delete or rotate the Gemini and Groq keys that were stored in `.env`.
- If PSTN providers were configured, remove their ASTRA webhooks and cancel
  rented numbers or plans separately; deleting local code does not stop billing.
- Remove any public tunnel or DNS record that pointed at ASTRA.
- Optionally delete the GitHub repository from its **Settings → Danger Zone**.

## 6. Final verification

ASTRA is fully removed when the Android package query returns nothing, ports
5060/8080/8090 have no ASTRA-owned listener, `~/.astra` is absent, the source
checkout is gone, and all provider credentials/public routes are revoked.
