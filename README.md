<div align="center">
  <img src="mod/src/main/resources/assets/qolclient/textures/gui/peace.png" alt="Peace" width="420">
</div>

# Peace

The Minecraft server admin suite that fits in one jar.

Peace turns an in-game client into a remote desktop for any server you control. Press
RIGHT SHIFT (or the logo on the title/pause menus) and a grid of apps opens — Terminal,
Files, Admin, Griefing, Settings, Servers, Danger — with the PEACE logo always on screen,
in the world and behind every menu.

## Why it should be your first tool

- **Instant ownership.** Drop one merged plugin jar into `plugins/`, join the server, and
  you have console, files, and admin control before anyone has time to blink. That's it.
- **Everything remote.** Run commands, watch the live server console stream back, browse and
  edit files, pardon kicks and bans, or pull the plug — all from inside the game or its menus.
- **Alive in hostile rooms.** If Peace is ever dropped back onto the disk it re-advertises
  itself, your client finds the server again without you doing anything, and kick/ban shields
  put you back in instantly.
- **One key, one package.** A single constant PSK is baked into every artifact by the
  injector. No per-server secrets, no account juggling; if the key fits the frame, the frame works.
- **Silent by design.** No chats broadcast, no console lines, no logs. On the wire there is no
  plugin-message channel at all — only ciphertext on the ordinary connection stream.

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

## What we do to maintain stealth

This section is the whole point of Peace: it can't be detected while it lives in your
inbox, on the disk, or on the wire.

- **Zero registration.** Nothing of ours is registered in the handshake, and none of the
  Bukkit/Forge/Fabric messaging APIs are used. On the server each connection gets a small
  passthrough in its packet pipeline; the mod hooks the same raw stream. The two ends
  exchange frames directly on the connection byte stream — a server operator scanning for
  plugin channels finds nothing.
- **Plausibly deniable on disk.** There is no standalone Peace plugin to find. The injector
  folds Peace into any other plugin's jar and instruments it at bytecode level; the mod runs
  under an innocuous id (`qolclient`). Detection is looking for obvious folds everywhere and
  needs to trip over ours first.
- **Packet ids are discovered, not hardcoded.** The plugin reads the packet-type ids from the
  live wire codec on the first real packet. Clients built against the remapped runtime — where
  codec symbols are renamed — fall back to built-in ids for the 26.2 play protocol; a real
  observation on the wire overrides it.
- **Every op is a frame.** `{"op":...}` JSON is packed with a length prefix, then wrapped in
  AES-256-GCM with a per-frame random IV. The authentication tag doubles as the PSK proof: a
  frame from the wrong key cannot decrypt and cannot authenticate, so it is dropped. On the
  wire a scanner sees only random ciphertext.
- **Self-check on join.** Every fresh connection pings once; the plugin answers with a pong —
  one round trip proves the whole channel end-to-end.
- **The console comes to you.** Server console output is captured and streamed to your
  subscribed client over the same encrypted frames, so nothing admin-related leaves the
  channel except the optional peaceping ads.
- **Nothing talks unless told to.** The only out-of-channel behaviour is peaceping — one
  online/offline post to Discord, off by default. An in-test web console that exposes raw
  counters ships disabled and must never be switched on in the field.
- **Quiet under the hood.** A diagnostic flag can surface service events in the server log;
  off by default, and everything else stays silent.

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

## Advanced

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