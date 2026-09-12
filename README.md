# Peace

An encrypted admin suite that keeps you in control of a Minecraft server: a server-side
Peace plugin, a Fabric client "Peace" desktop, and an injector that hides the Peace plugin
inside any other plugin's jar.

## What is this project

A client-server pair plus a packaging tool. The Peace plugin listens for AES-256-GCM
encrypted ops on a vanilla plugin-message channel and owns the server (console RCE, file
operations, griefing tools, shields such as anti-ban and silent join). The Peace mod turns
the client into a remote desktop for that server: a grid of windows (Terminal, Files,
Admin, Griefing, Settings, Servers, Danger) with encrypted request/response over custom
payloads, opened by RIGHT SHIFT in-game or the PEACE logo button on the title/pause menus.
The injector embeds the obfuscated Peace plugin into a "seed" plugin jar and instruments its
lifecycle, so the server loads no standalone Peace plugin.

## Install

Requirements: Java 25 toolchain, Gradle with the Fabric `fabric-loom` versions pinned in
`mod/gradle.properties`, and for the injector a JDK plus the ASM 9.10.1 jar (auto-detected
from the Gradle cache or from `$ASM`).

1. Build the Peace plugin: `cd plugin && gradle build`.
   Produces `plugin/build/libs/Peace.jar` (ProGuard-obfuscated) and
   `plugin/build/libs/mapping.txt`. The plugin reads `config.yml`: `psk`, `announce`,
   `auth`, and `protected` shields.
2. Build the mod: `cd mod && gradle build`. Put the jar from
   `mod/build/libs/peace-mod-<version>.jar` into your Fabric client's `mods` folder,
   set the same PSK in `mods/<profile>/peace.txt`, then launch.
3. Build the injector: `cd injector && ./build_injector.sh`. Produces a standalone `Injector.jar`
   (GUI; `java -jar Injector.jar`). It embeds `res/lib/peace.jar` and `res/lib/mapping.txt`
   from the current plugin build.
4. Inject: pick your own seed plugin jar (the target you want Peace hidden in), an output
   path, the PSK, an optional `auth` list (only those names/UUIDs may use Peace; empty means
   anyone holding the PSK), and optional peaceping announce settings (token, channel id, host,
   port). The output `bundle/Core.jar` drops straight into `server/plugins/`.
   Equivalent CLI: `./run.sh <seed.jar> <out.jar> [config.yml]`.

On first boot the merged plugin posts one peaceping message (`peaceping <host>:<port> online`)
to your Discord channel; on clean stop it posts `offline`. The Peace Servers app discovers
that advertisement and auto-adds the server; each entry shows an online/offline status dot.

## Why it's better

A detector that looks for a Peace plugin on the disk won't find one - there is no standalone
Peace plugin, only a service folded into the seed jar and instrumented at bytecode level. The
opened payload channel rides vanilla custom payloads wrapped in AES-256-GCM frames, so static
string scanners and naive packet greps see random ciphertext. Everything configurable lives in
one merged `config.yml`, one injector run, and one disk artifact to carry.

## Why should I care

Keeping a server is only half the job; keeping access is the other. Peace gives you a control
channel that survives being dropped back onto the disk (the plugin re-notifies via peaceping,
the server reappears in Peace without manual IP entry), a silent boot that posts once online and
once offline, and client-side shields that pardon kicks/bans and whitelist the account the
moment a PSK proof lands. Danger's server "Delete files" and "Shutdown" actions give you the
last word when the machine is no longer useful.

## Advanced

- **Auth list**: baked into the merged `config.yml` by the injector. A proven holder of the PSK
  who is also in `auth` may use Peace. The plugin ignores ops from players who fail the PSK
  proof; with `auth` empty, any PSK holder is implicitly allowed.
- **Peaceping discovery**: the announce block in `config.yml` (`enabled`, `token`, `channel`,
  `host`, `port`) controls the online/offline posts. Only peaceping travels through Discord;
  every other op stays in the encrypted channel.
- **CLI merge**: `Inject <seed.jar> <out.jar> [config.yml]` (embedding its own copies of Peace
  and the mapping), or the legacy `Inject <seed.jar> <peace.jar> <mapping.txt> <out.jar>` for
  explicit jars.
- **File manager context menu**: right-click a row in the Files app for Edit, Copy..., Delete,
  and Send to Discord (the opened file uploads to your announce channel as an attachment).
- **Danger app**: destructive buttons arm on first click and fire on the second click within
  5 seconds, guarding against misclicks on "Delete server files" (wipes the server directory
  after 80 ticks) and "Shutdown" (stops the process).