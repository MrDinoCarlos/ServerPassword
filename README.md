# ServerPassword 1.0.0

Server-wide access protection using a shared password. Includes native dialogs, the original PIN selector, and private chat login on older versions, without resource packs or client mods.

## Choose the correct JAR

Install **one JAR** for your server range. Replace the previous plugin file to avoid duplicate installations.

| Server | File |
| --- | --- |
| Spigot / Paper 1.20.X | `ServerPassword-1.0.0-1.20.X.jar` |
| Spigot / Paper 1.21.X | `ServerPassword-1.0.0-1.21.X.jar` |
| Paper 26.1.X | `ServerPassword-1.0.0-26.1.X.jar` |
| Paper 26.2.X | `ServerPassword-1.0.0-26.2.X.jar` |
| Paper 26.3.X | `ServerPassword-1.0.0-26.3.X.jar` |

Minecraft 1.20.5+ and 1.21.X require Java 21; the Paper 26.X JARs require Java 25. The 1.20.X JAR uses Java 17 bytecode for earlier releases in that range. No other plugins are required.

## Modern login

New installations default to `input-mode: MODERN`.

- `modern-gui.input: AUTO` selects a keypad when the password contains only digits, and text input otherwise.
- `NUMERIC` forces the keypad; configuration validation rejects passwords containing letters.
- `TEXT` opens text entry directly. Digit-only credentials still use the keypad and cannot exceed nine digits.
- Native text login opens the editable field immediately. Physical typing and an expandable QWERTY keyboard update the same value; button presses preserve unfinished typed text.
- The password display supports masking/revealing, clearing and backspace.
- Results use green feedback on success, or red code vibration followed by clearing on failure, with configurable vanilla sounds.
- Help, a language selector and a countdown are included. Switching views preserves input, remaining time and attempts.

Native dialogs use a compact **3x4 keypad**, visible PIN slots with filled masks and an underline, a small control row, and a separate Confirm button. More opens secondary controls in their own panel; Back always returns to login. The red X disconnects directly. Text entry uses one editable field and an expandable QWERTY keyboard with case, symbol and disconnect controls. The bundled layouts and translations fit a 320x240 vanilla GUI without vertical scrolling.

The live countdown updates the BossBar without rebuilding a text field. Its in-panel time snapshot refreshes on button presses. At ten seconds the timer turns orange (yellow BossBar), and at five seconds it turns red. The keypad refreshes only at these urgency transitions, not every second. Configurable ticks accelerate from once per second to twice per second, then four times per second in the final three seconds. Warning and critical thresholds are configurable.

`modern-gui.presentation: AUTO` uses native dialogs when Paper and the client support them. Paper's API starts at 1.21.7, and clients need Java Edition 1.21.6 or later. Other combinations use the original up/down selector for numeric credentials, and private chat for text passwords. `INVENTORY` forces this compatibility behavior. `DIALOG` prefers dialogs while retaining the same fallback when capabilities are unavailable.

ViaVersion installed on the backend exposes the actual client protocol. If translation occurs only on a proxy and the original version is unknown, select `presentation: INVENTORY`.

Minecraft does not expose a native password-type text field: **the shared editable text field is visible**, including while the virtual keyboard is open. Mask/reveal controls affect native keypad slots and noneditable code previews. Older versions enter text directly in chat, with no inventory, anvil or virtual keyboard.

Minecraft 1.20.X and 1.21.4 do not support native dialogs. Numeric passwords use the original menu: an up arrow, a visible digit head and a down arrow for every position, plus clear, attempts, countdown and confirmation controls. Each digit cycles from 0 to 9 and back, retaining leading zeroes. The menu reads the existing `code-gui` custom head textures. Custom heads do not require a resource pack; Minecraft loads their skins normally. The native dialogs do not use head textures.

For text passwords on unsupported versions, a private prompt asks the player to type the password in chat and press Enter. The plugin cancels the chat event, clears its public message and recipients, and verifies the exact text without broadcasting or echoing it. Case, spaces and symbols are preserved. `/serverpass <password>` remains available, including for passwords beginning with `/`. The same hashing, attempts, cooldown, bans, session invalidation and timeout protections apply. A timeout configured as REOPEN starts another chat login period.

When the server's native dialog API is unavailable, ONE console warning at startup explains the Paper 1.21.7+ requirement and the selector/chat fallback. Player joins, password changes and configuration reloads never repeat this warning, and it is never sent to player chat.

## Original menu

`input-mode: LEGACY` retains the original arrow, letter and head menu configured under `code-gui`. `CODE_GUI` remains a LEGACY alias. Existing installations retain their configured value: change it to MODERN and run `/spass reload` to enable the new interface.

LEGACY retains its nine-character limit. The saved `password.legacy-alphabet` policy is checked against its available character set. Numeric passwords always use digits only. The same menu is selected automatically for PIN login on unsupported versions even while MODERN is configured; text login there uses private chat.

## Passwords, validation and sessions

A fresh installation has **no password** (`password.hash: ''`, `password.length: 0`). Until setup is complete, only players with `serverpassword.admin` can join. Administrators receive a red warning after joining, and the console receives the same setup instructions at startup and reload. Other players are disconnected with a setup-pending message.

Run `/spass set <PIN>` in game or `spass set <PIN>` in the console. The default policy requires exactly **four digits**, including leading zeros. There is no shared default password. Set `password-policy.pin-length` to change the required length, or `password-policy.mode: TEXT` to accept text passwords; reload, then set the new password. These settings govern the next password change and do not replace the existing credential. Upgrades infer a suitable policy from the saved password.

## Administration commands

All `/spass` subcommands require `serverpassword.admin` (operators by default) and support permission-aware Tab completion. Password arguments are never suggested.

| Command | Behavior |
| --- | --- |
| `/spass settings` | Show policy, interface and timeout without displaying the password or hash. |
| `/spass mode PIN` or `/spass mode TEXT` | Select the policy for the next password change. |
| `/spass length 6` | In PIN mode, require exactly 1–9 digits for the next `/spass set`. In TEXT mode, change the text input maximum. |
| `/spass max-length 64` | Set the MODERN text input maximum, between 1 and 128. |
| `/spass set <password>` | Set the password and require authentication again. Spaces are supported in TEXT mode. |
| `/spass gui MODERN` or `/spass gui LEGACY` | Switch the login interface. |
| `/spass presentation AUTO` | Select AUTO, DIALOG or INVENTORY presentation. |
| `/spass input AUTO` | Select AUTO, TEXT or NUMERIC; digit-only credentials always use the keypad. |
| `/spass timeout 60` or `/spass timeout off` | Change or disable the authentication timeout. |
| `/spass attempts 5` | Change the attempt limit per connection. |
| `/spass cooldown 2` | Change the delay between attempts in seconds. |
| `/spass remember 120` | Change remembered-session duration in minutes; zero disables remembering. |
| `/spass notify-admins OFF` | Disable admin update chat notices; console checking remains enabled. |
| `/spass reload` | Reload configuration and language files. |

Changes are validated, saved and applied immediately. Invalid changes preserve the accepted configuration and file. Policy changes do not replace an existing hashed password: use `/spass set` afterward. Example: `/spass mode PIN`, `/spass length 6`, then `/spass set <your six digits>`. Numeric passwords longer than nine digits are rejected even under TEXT policy.

Only a salted PBKDF2-SHA256 hash with 600000 iterations is stored after setup.

Use `/spass set <new password>` to change it. Hashing runs in the background. The command updates credential metadata and requires connected players to authenticate again. Remembered sessions for offline players are invalidated as well. Pending checks for the old credential are discarded.

Older plaintext configurations are migrated automatically, preserving the password and replacing plaintext with its hash. No plaintext backup is created. Use the command rather than manually changing the hash, length or character policy so the metadata stays consistent.

Settings are validated before they are saved or applied. Rejected reloads preserve the active settings; invalid configuration at startup prevents the plugin from enabling. `/spass reload` applies interface, configuration and translation changes.

## Automatic file updates

Startup and reload add missing options to `config.yml`, preserving existing values, custom keys and user comments. Files are saved using temporary-file replacement.

Languages are extracted to `plugins/ServerPassword/lang/en.yml`, `es.yml` and `de.yml`. Edit these files to customize translations. Updates add missing messages without replacing existing text. Invalid language YAML keeps the previously loaded or bundled translations without overwriting the invalid file.

Language detection uses the client locale and falls back to `default-language`. The language selector stores each player's preference. Comments and documentation are written in English; player-facing messages remain translated.

## Modrinth updates

The checker reads public versions at startup and every six hours, filtering for the server's Minecraft version and platform. Check results and connection errors are always reported in the console.

`updates.notify-admins: false` disables **only the join chat notice** for authenticated players with `serverpassword.admin`. The checker and console reporting remain enabled. Updates are never downloaded or installed automatically.

## Build and verification

Run `gradlew.bat build` on Windows or `./gradlew build` elsewhere. Java 17, 21 and 25 toolchains are needed; Gradle can obtain them through the configured resolver.

The build compiles the common implementation against 1.20.1, the dialog adapter against 1.21.7, and each Paper 26 variant against its own API. Tests run as part of the build, producing five JARs in `build/libs`, each containing its matching `plugin.yml` and license. The complete source archive is written to `build/distributions/ServerPassword-1.0.0-sources.zip`. Tests cover input flows, first-time setup, hashing, session invalidation, migration, settings preservation and version comparison.

To check native dialog construction against Paper's real implementation, also supply an extracted Paper 26.3 server JAR: `gradlew.bat build "-PpaperServerJar=C:/server/versions/26.3/paper-26.3.jar"`. These checks run with Java 25 and reproduce Paper's dialog validation, including its pause/after-action constraint. This reads the JAR without starting or changing the server. Visual verification with a Minecraft client is a separate check.

UI tests check the bundled English, Spanish and German screens against the minimum 320x240 GUI size and export their actual View records to `build/reports/login-ui`. On Windows with PowerShell 7, run `pwsh ./tools/preview-dialogs.ps1 -ClientJar "C:/Minecraft/versions/26.3/26.3.jar"` to render layout previews with the installed client's own font and widget assets. These are previews of the layout, not screenshots of a running client. Minecraft assets are read locally and are never included in the plugin or source archive.

## License

Copyright (C) 2026 MrDinoCarlos. The original ServerPassword code and documentation are licensed under **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International** (SPDX: `CC-BY-NC-SA-4.0`). [LICENSE](LICENSE) contains the complete, unmodified official English legal code. Every JAR and source archive includes both the license and [NOTICE](NOTICE), which identifies the author and project.

Sharing and adapting are permitted for noncommercial purposes, with attribution and an indication of changes. Shared adaptations must retain the same license, subject to its terms. The NonCommercial condition applies to commercial uses as well as paid redistribution; it is not limited to selling the plugin. See the [official license summary](https://creativecommons.org/licenses/by-nc-sa/4.0/) and the full legal code for the exact scope.

Creative Commons [does not recommend its licenses for software](https://creativecommons.org/faq/#can-i-apply-a-creative-commons-license-to-software); this project uses CC BY-NC-SA 4.0 at its author's request. The generated source ZIP is available for convenient access and modification. Third-party build tools, dependencies and materials retain their own licenses.
