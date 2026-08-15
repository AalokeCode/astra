# Asterisk on macOS for the Android SIP path

This path is active for the local Android client. Public Indian/international
calls can bypass Asterisk through the Plivo/Twilio gateway documented in
`docs/phone-gemini-live.md`.

Asterisk 22.10.1 LTS, built from source into `~/.astra/asterisk`. Homebrew dropped
the `asterisk` formula, and the only SIP server left in brew is FreeSWITCH, whose
config is entirely different.

**Asterisk's macOS support is unofficial.** Four separate defects had to be worked
around before it would start. All are upstream bugs, not configuration mistakes —
none of them are things you did wrong, and a fresh tarball will have them all again.

## Verify the download

No checksums are published for the full tarball (only for the `-patch` variant),
but there is a GPG signature:

```bash
curl -O https://downloads.asterisk.org/pub/telephony/asterisk/asterisk-22.10.1.tar.gz
curl -O https://downloads.asterisk.org/pub/telephony/asterisk/asterisk-22.10.1.tar.gz.asc
gpg --keyserver hkps://pgp.mit.edu --recv-keys F2FC93DB7587BD1FB49E045A5D984BE337191CE7
gpg --verify asterisk-22.10.1.tar.gz.asc asterisk-22.10.1.tar.gz
# expect: Good signature from "Asterisk Development Team <asteriskteam@digium.com>"
```

The key is **not** on keys.openpgp.org or keyserver.ubuntu.com; MIT has it.

## Build

```bash
brew install autoconf automake libtool pkg-config jansson libxml2 libedit

export PKG_CONFIG_PATH="/opt/homebrew/opt/libedit/lib/pkgconfig:/opt/homebrew/opt/openssl@3/lib/pkgconfig:/opt/homebrew/lib/pkgconfig"
export CFLAGS="-I/opt/homebrew/include -I/opt/homebrew/opt/libedit/include -I/opt/homebrew/opt/openssl@3/include"
export LDFLAGS="-L/opt/homebrew/lib -L/opt/homebrew/opt/libedit/lib -L/opt/homebrew/opt/openssl@3/lib"

./configure --prefix="$HOME/.astra/asterisk" \
            --sysconfdir="$HOME/.astra/asterisk/etc" \
            --localstatedir="$HOME/.astra/asterisk/var" \
            --with-jansson=/opt/homebrew --with-libedit=/opt/homebrew/opt/libedit \
            --with-ssl=/opt/homebrew/opt/openssl@3 --with-sqlite3=/opt/homebrew/opt/sqlite \
            --disable-xmldoc --without-x11

# apply the two source patches below, then:
make menuselect.makeopts
menuselect/menuselect --disable res_geolocation menuselect.makeopts
make -j8 && make install && make samples
```

`--disable-xmldoc` is **required**, not optional — see defect 4.

## The four defects

### 1. `main/asterisk.c` — missing `<locale.h>`

```
error: use of undeclared identifier 'LC_ALL'
error: call to undeclared function 'setlocale'
```

`setlocale`/`LC_ALL` are used without including `<locale.h>`. glibc pulls it in
transitively; Darwin's libc does not. Add near the other includes:

```c
#include <locale.h>	/* setlocale/LC_ALL: transitive on glibc, not on Darwin */
```

(Asterisk then asks for the `C.UTF-8` locale, a glibc invention that does not
exist on macOS. The call fails harmlessly — the code comments say "if it fails,
so be it" — and only affects libedit multi-byte handling in the CLI.)

### 2. `main/Makefile` — hardcoded target triple

```
ld: library 'libpjsip-ua-aarch64-apple-darwin25.2.0.a' not found
```

Line ~314 contains a literal `PJ_TARGET := aarch64-apple-darwin25.2.0` — some
developer's own machine (macOS 26). PJProject builds its archives named for the
*actual* host, so the link fails on every macOS except that one. Replace with:

```make
PJ_TARGET := $(shell sed -n 's/^export TARGET_NAME := //p' $(PJPROJECT_SRCDIR)/build.mak)
```

### 3. `res_geolocation` — GNU-ld-only flags

```
ld: unknown option: -znoexecstack
```

`Makefile.rules` uses `-Wl,-znoexecstack` and `-Wl,-b,binary` unconditionally;
Apple's linker has neither. Only `res_geolocation` hits this rule, so disable
that module — it is for emergency-services geolocation and unused here.

### 4. Segfault in libxml2 — the one that actually matters

```
EXC_BAD_ACCESS in xmlAddIDInternal (libxml2.16.dylib)
  <- xmlSetProp <- ast_xml_set_attribute (xml.c:291)
  <- xmldoc_update_config_option (module "cdr")
  <- load_module (cdr.c:4677)
```

Asterisk 22.10.1 is incompatible with libxml2 2.16's ID-attribute handling, and
it crashes while building its XML documentation tree. This fires on **any**
module registering config options, so no amount of config avoids it. Exit code
139 with no log line — `lldb -b -o run -o bt` is what surfaces it.

Fix: `./configure --disable-xmldoc`. The only loss is CLI help text.

Note the environment is also inconsistent: `pkg-config --modversion libxml-2.0`
reports 2.9.13 (the macOS SDK copy) while the binary links 2.16 at runtime.

## Minimal module set

The stock `modules.conf` autoloads 321 modules, several of which (LDAP, ODBC,
RealTime backends) have no configuration and abort startup. `modules.conf` here
loads ~50. Two names that look right but do not exist on this build:

- `res_timing_timerfd.so` — Linux-only; use `res_timing_pthread.so`
- `res_pjsip_transport_udp.so` — folded into `res_pjsip` in Asterisk 22

## pjsip.conf: the AOR must be named after the endpoint

Asterisk's registrar resolves the AOR from the **user part of the `To:` header**,
not from the endpoint's `aors=` setting. Naming the aor something else produces:

```
res_pjsip_registrar.c: find_registrar_aor: AOR '' not found for endpoint '700'
SIP/2.0 404 Not Found
```

— even though `pjsip show endpoint 700` correctly reports `aors : 700-aor`. The
endpoint is identified and authenticated fine; only the registration lookup fails.

So the endpoint, auth and aor all use the caller's number as their section name,
with the auth suffixed:

```ini
[700]
type=endpoint
aors=700
auth=700-auth
...
[700-auth]
type=auth
...
[700]
type=aor
```

Repeating `[700]` for two different object types is correct and supported —
sorcery filters by `type=`. (An earlier version of this file claimed duplicate
categories silently merge; that was wrong, and verified so: both objects load.)

## Run

```bash
~/.astra/asterisk/sbin/asterisk -f -C ~/.astra/asterisk/etc/asterisk/asterisk.conf &

A=~/.astra/asterisk/etc/asterisk/asterisk.conf
~/.astra/asterisk/sbin/asterisk -rx "pjsip show endpoints" -C "$A"
~/.astra/asterisk/sbin/asterisk -rx "pjsip show contacts"  -C "$A"   # after the phone registers
```

Healthy state before the phone registers:

```
Endpoint:  700    Unavailable   0 of inf
   InAuth:  700-auth/700
      Aor:  700
```

`Unavailable` is correct until a contact registers.

## Security

- `bind=0.0.0.0:5060` listens on every interface. Keep it off the public internet —
  SIP scanners find open port 5060 within hours and attempt toll fraud.
- This build has **SRTP compiled out** (`PJMEDIA_HAS_SRTP=0`, no libsrtp). With UDP
  signaling that means signaling and audio are both unencrypted on the wire. Fine
  on a home LAN; not acceptable on shared or public Wi-Fi.
- `pjsip.conf` holds a plaintext credential — kept at mode 0600.

## If this ever needs redoing

FreeSWITCH is `brew install freeswitch` with no patching. The Android app is
server-agnostic — it only needs something that accepts a SIP REGISTER and answers
extension 700. If the four patches above stop applying to a newer Asterisk, switching
is cheaper than fixing.

## The Gemini Live assistant bridge

`AudioSocket()` in the dialplan opens a TCP connection to the gateway and streams
call audio both ways. The gateway streams directly to the shared Gemini Live
session and executes tools through the same `Assistant` instance as the CLI.

```bash
.venv/bin/python -m app.main --sip          # listens on 127.0.0.1:8090
```

Then dial **700** from the phone. **701** is the plain echo test, kept for
diagnosing audio without involving the assistant.

Wire format is `[type:1][length:2 BE][payload]`, audio as signed 16-bit LE mono
at 8 kHz. The gateway resamples input to Gemini's native 16 kHz and Gemini's
24 kHz output back to 8 kHz.

Two things that are easy to get wrong:

- **Pace the replies.** Asterisk plays what it receives; writing the whole buffer
  at once overruns its jitter buffer and the caller hears garbled speech. Send one
  20 ms frame every 20 ms.
- **Clear queued output on interruption.** Gemini's VAD supplies barge-in and
  the gateway immediately drops buffered AudioSocket frames.

`${UNIQUEID}` is not a valid UUID. The checked-in dialplan uses `${UUID()}` from
`func_uuid.so`, which AudioSocket accepts directly.
