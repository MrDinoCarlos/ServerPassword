# ServerPassword 1.1.0

Server-wide access protection using a shared password. Includes masked native dialogs and inventory keypads/keyboards, without resource packs or client mods. Passwords are never accepted in chat or command arguments.

## Choose the correct JAR

Install **one JAR** for your server range. Replace the previous plugin file to avoid duplicate installations.

| Server | File |
| --- | --- |
| Spigot / Paper 1.20.X | `ServerPassword-1.1.0-1.20.X.jar` |
| Spigot / Paper 1.21.X | `ServerPassword-1.1.0-1.21.X.jar` |
| Paper 26.1.X | `ServerPassword-1.1.0-26.1.X.jar` |
| Paper 26.2.X | `ServerPassword-1.1.0-26.2.X.jar` |
| Paper 26.3.X | `ServerPassword-1.1.0-26.3.X.jar` |

Minecraft 1.20.5+ and 1.21.X require Java 21; the Paper 26.X JARs require Java 25. The 1.20.X JAR uses Java 17 bytecode for earlier releases in that range. No other plugins are required.

## Modern login

New installations default to `input-mode: MODERN`.

- `modern-gui.input: AUTO` selects a keypad when the password contains only digits, and a virtual keyboard otherwise.
- `NUMERIC` forces the keypad; configuration validation rejects passwords containing letters.
- `TEXT` selects the virtual keyboard. Digit-only credentials still use the keypad and cannot exceed nine digits.
- Passwords stay masked in every screen. There are no editable text fields, anvils, chat prompts or reveal controls, including when old configuration values request them.
- The virtual keyboard supports case, symbols, clearing and backspace. Its charset must contain every character in the password; extend `modern-gui.virtual-keyboard.charset` before importing a credential that needs additional characters.
- Results use green feedback on success, or red code vibration followed by clearing on failure, with configurable vanilla sounds.
- Help, a language selector and a countdown are included. Switching views preserves input, remaining time and attempts.

Native dialogs use a compact **3x4 keypad**, masked PIN slots and a separate Confirm button. More opens secondary controls; Back returns to login. The red X disconnects directly. Text entry uses a QWERTY button keyboard. The bundled layouts and translations fit a 320x240 vanilla GUI without vertical scrolling.

The live countdown updates the BossBar. Its in-panel time snapshot refreshes on button presses. At ten seconds the timer turns orange (yellow BossBar), and at five seconds it turns red. The keypad refreshes only at these urgency transitions, not every second. Configurable ticks accelerate in the final seconds. Warning and critical thresholds are configurable.

`modern-gui.presentation: AUTO` uses native dialogs when Paper and the client support them. Paper's API starts at 1.21.7, and clients need Java Edition 1.21.6 or later. Other combinations use a masked inventory keypad or keyboard. `INVENTORY` forces inventories. `DIALOG` prefers dialogs while retaining the same safe fallback when capabilities are unavailable or rendering fails.

ViaVersion installed on the backend exposes the actual client protocol. If translation occurs only on a proxy and the original version is unknown, select `presentation: INVENTORY`.

Minecraft has no native password-type text field, so editable fields are deliberately not used. On older versions, button-based inventory entry keeps the entered value masked. `/serverpass` without arguments reopens login; secret arguments are rejected. Locked chat is cancelled and is never submitted for verification.

When native dialogs are unavailable, one console warning at startup explains the Paper requirement and masked inventory fallback. Joins and reloads do not repeat it.

## Original menu

`input-mode: LEGACY` retains the original arrow selector configured under `code-gui`, but selected characters use neutral masked items rather than revealing their letter or digit textures. `CODE_GUI` remains an alias. MODERN is recommended for direct keypad entry, including on older servers.

LEGACY retains its nine-character limit. The saved `password.legacy-alphabet` policy is checked against its available character set. Numeric passwords always use digits only. MODERN uses its own masked inventory fallback on unsupported versions.

## Passwords, validation and sessions

A fresh installation has **no password** (`password.hash: ''`, `password.length: 0`). Until setup is complete, only administrators on an online-mode server can join. Offline/proxy backends require the console hash-import flow below for initial setup. Rejected players remain locked if another plugin cancels their kick.

Run `/spass set` **without arguments** in game. PIN setup uses a centered number keypad with the original configured digit-head textures, a short inventory title, and a live masked progress bar that distinguishes entry from confirmation. Text setup uses character heads and shows page arrows only when needed. The menu asks for the password twice and clears its buffers on completion, mismatch, close, disconnect or expiry. The default policy requires exactly four digits, including leading zeros. Set `password-policy.pin-length` to change this or select `password-policy.mode: TEXT` for a longer password. Policy settings govern the next change; they do not replace the existing credential.

For console setup, run the standalone tool in a real local terminal, not in the Minecraft console:

```text
java -jar ServerPassword-PasswordTool.jar password-import.yml
```

The tool prompts twice with hidden input. It writes only a salted hash and metadata and refuses to overwrite an existing file. Copy that file to `plugins/ServerPassword/password-import.yml`, then run `spass import` in the Minecraft console. A successful import deletes the import file, updates the configuration and invalidates sessions. The build produces the tool in `build/tools`; `java tools/HashPassword.java password-import.yml` is also supported with Java 17+.

Remembered sessions are available only when the backend itself uses online-mode authentication. Offline and proxy backends require the password on every connection, even if a remembered duration is configured; proxy forwarding alone is not treated as proof of a player's identity. Current authorization is tied to the exact player connection. Setting the remembered duration to zero prevents old remembered sessions being restored.

## Administration commands

All `/spass` subcommands require `serverpassword.admin` (operators by default) and support permission-aware Tab completion. Password arguments are never suggested.

| Command | Behavior |
| --- | --- |
| `/spass settings` | Show policy, interface and timeout without displaying the password or hash. |
| `/spass mode PIN` or `/spass mode TEXT` | Select the policy for the next password change. |
| `/spass length 6` | In PIN mode, require exactly 1–9 digits for the next `/spass set`. In TEXT mode, change the text input maximum. |
| `/spass max-length 64` | Set the MODERN text input maximum, between 1 and 128. |
| `/spass set` | Open masked password creation and confirmation. Requires an authenticated administrator. |
| `spass import` (console only) | Import `password-import.yml` generated locally using hidden input. |
| `/spass gui MODERN` or `/spass gui LEGACY` | Switch the login interface. |
| `/spass presentation AUTO` | Select AUTO, DIALOG or INVENTORY presentation. |
| `/spass input AUTO` | Select AUTO, TEXT or NUMERIC; digit-only credentials always use the keypad. |
| `/spass timeout 60` or `/spass timeout off` | Change or disable the authentication timeout. |
| `/spass attempts 5` | Change the attempt limit per connection. |
| `/spass cooldown 2` | Change the delay between attempts in seconds. |
| `/spass remember 120` | Change remembered-session duration in minutes; zero disables remembering. |
| `/spass notify-admins OFF` | Disable admin update chat notices; console checking remains enabled. |
| `/spass reload` | Reload configuration and language files. |

Changes are validated, saved and applied immediately. Invalid changes preserve the accepted configuration and file. Policy changes do not replace the password: use `/spass set` afterward. Example: `/spass mode PIN`, `/spass length 6`, then `/spass set` to enter six digits in the menu. Numeric passwords longer than nine digits are rejected even under TEXT policy.

Only a salted PBKDF2-SHA256 hash with 600000 iterations is stored after setup.

Use `/spass set` to change it. Hashing runs in the background. Concurrent changes are rejected until the pending change finishes. Credential changes require connected players to authenticate again and invalidate remembered sessions and pending checks for the old credential.

Older plaintext configurations are migrated automatically, preserving the password and replacing plaintext with its hash. No plaintext backup is created. Use the command rather than manually changing the hash, length or character policy so the metadata stays consistent.

Settings are validated before they are saved or applied. Rejected configuration reloads preserve the active settings. If startup validation or protection initialization fails, admission remains closed and the server stops instead of continuing without password protection. Enabling the plugin with players already online requires those players to authenticate as well.

## Password privacy boundaries

Supported input flows never put the entered password in command arguments, chat messages, GUI labels or native text fields. A server-wide Log4j filter drops obsolete password-command log records before appenders receive them, including namespaced variants. Failure to install that protection stops startup. Existing historical logs are not rewritten: rotate a password previously used with a plaintext command.

Only a one-way salted PBKDF2 hash is stored, rather than reversible encryption. A plugin cannot hide a password or reconstructible keystrokes from someone controlling the client, server process, malicious plugins or an unencrypted network connection. Use authenticated, encrypted connections and secure any proxy/backend transport. Offline-mode Minecraft does not gain transport encryption from this plugin. Choose a long text password when hash disclosure is a concern; a four-digit PIN has only 10,000 possibilities.

## Automatic file updates

Startup and reload add missing options to `config.yml`, preserving existing values, custom keys and user comments. Files are saved using temporary-file replacement.

Languages are extracted to `plugins/ServerPassword/lang/en.yml`, `es.yml` and `de.yml`. Edit these files to customize translations. Updates add missing messages without replacing existing text. Invalid language YAML keeps the previously loaded or bundled translations without overwriting the invalid file.

Language detection uses the client locale and falls back to `default-language`. The language selector stores each player's preference. Comments and documentation are written in English; player-facing messages remain translated.

## Modrinth updates

The checker reads public versions **at startup and then every 24 hours**, filtering for the server's Minecraft version and platform. Each check reports its result or connection error once in the console. Failed checks wait until the next daily run rather than retrying immediately. Player joins and `/spass reload` do not query Modrinth; administrator join notices reuse the cached result.

`updates.notify-admins: false` disables **only the join chat notice** for authenticated players with `serverpassword.admin`. The checker and console reporting remain enabled. Updates are never downloaded or installed automatically.

## Build and verification

Paper 26.3 distinguishes the explicit invulnerability flag from immunity while a client loads or uses a protected game mode. Login snapshots use the explicit flag and restore it on authentication or disconnect, preserving protection that already existed before login.

For players affected by an older build, an authenticated administrator can use `/spass repairprotection [player]` to remove the existing permanent invulnerability flag. Omitting the name repairs the administrator's own player; console use requires an online player name. This is an explicit repair and does not reset inventories, profiles, flight or potion effects.

`gradlew.bat authProtectionProbeJar` builds a separate Paper 26.3 regression plugin in `build/integration/tools`. Use it only in a loopback fixture started with `-Dperplayerprofiles.integration=local-smoke`. It checks client-loading immunity, creative mode, damage frames, preexisting explicit protection, native enemy targetability and authenticated repair, then stops that fixture. It is never included in the runtime plugin JARs.

Run `gradlew.bat build` on Windows or `./gradlew build` elsewhere. Java 17, 21 and 25 toolchains are needed; Gradle can obtain them through the configured resolver.

The build compiles the common implementation against 1.20.1, the dialog adapter against 1.21.7, and each Paper 26 variant against its own API. Tests run as part of the build, producing five JARs in `build/libs`, each containing its matching `plugin.yml` and license. The complete source archive is written to `build/distributions/ServerPassword-1.1.0-sources.zip`. Tests cover input flows, first-time setup, hashing, session invalidation, migration, settings preservation and version comparison.

To check native dialog construction against Paper's real implementation, also supply an extracted Paper 26.3 server JAR: `gradlew.bat build "-PpaperServerJar=C:/server/versions/26.3/paper-26.3.jar"`. These checks run with Java 25 and reproduce Paper's dialog validation, including its pause/after-action constraint. This reads the JAR without starting or changing the server. Visual verification with a Minecraft client is a separate check.

UI tests check the bundled English, Spanish and German screens against the minimum 320x240 GUI size and export their actual View records to `build/reports/login-ui`. On Windows with PowerShell 7, run `pwsh ./tools/preview-dialogs.ps1 -ClientJar "C:/Minecraft/versions/26.3/26.3.jar"` to render layout previews with the installed client's own font and widget assets. These are previews of the layout, not screenshots of a running client. Minecraft assets are read locally and are never included in the plugin or source archive.

## License

Copyright (C) 2026 MrDinoCarlos. The original ServerPassword code and documentation are licensed under **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International** (SPDX: `CC-BY-NC-SA-4.0`). [LICENSE](LICENSE) contains the complete, unmodified official English legal code. Every JAR and source archive includes both the license and [NOTICE](NOTICE), which identifies the author and project.

Sharing and adapting are permitted for noncommercial purposes, with attribution and an indication of changes. Shared adaptations must retain the same license, subject to its terms. The NonCommercial condition applies to commercial uses as well as paid redistribution; it is not limited to selling the plugin. See the [official license summary](https://creativecommons.org/licenses/by-nc-sa/4.0/) and the full legal code for the exact scope.

Creative Commons [does not recommend its licenses for software](https://creativecommons.org/faq/#can-i-apply-a-creative-commons-license-to-software); this project uses CC BY-NC-SA 4.0 at its author's request. The generated source ZIP is available for convenient access and modification. Third-party build tools, dependencies and materials retain their own licenses.
