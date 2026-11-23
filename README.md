# OpenSSH – SSH, SFTP and database client for Windows 11

A modern desktop app in the spirit of Termius: saved hosts with groups, multiple terminal tabs (SSH and local),
an SFTP file browser with drag & drop, key management, snippets and a built-in database client
(MariaDB/MySQL, PostgreSQL, SQLite, Redis) – optionally through an SSH tunnel.

Written in Kotlin with Compose Multiplatform (Desktop), sshj, JediTerm and pty4j. The user interface is in German.

## Features

| Area | What you get |
|---|---|
| **Hosts** | Create, edit, duplicate, delete; groups with filter chips; full-text search; command to run after login; "forget host key" |
| **Terminal** | Any number of tabs; SSH (xterm-256color, resizing) and local shells (PowerShell 7, Windows PowerShell, CMD, WSL, Git Bash via ConPTY) |
| **SFTP** | Navigation (breadcrumbs, path input, filter, hidden files), upload/download of files **and folders** with progress and cancel, drag & drop from Explorer, multi-select (Ctrl/Shift), rename, delete, `chmod`, edit small text files in place |
| **Split view** | Terminal and SFTP side by side over a *single* SSH connection, resizable divider |
| **Port forwarding** | Local port forwarding per SSH tab (toolbar icon `<·>`) |
| **Keys** | Import from file or clipboard: OpenSSH, PEM/PKCS#8 and PuTTY `.ppk` (v2/v3), with or without passphrase; SHA256 fingerprint; copy public key |
| **Security** | known_hosts verification that asks for unknown hosts and **warns on changed host keys**; passwords and keys stored only in the Windows vault; PuTTY MAC is verified |
| **Databases** | Connect through an SSH tunnel or directly; schemas, tables, views, columns (incl. primary keys); query editor (Ctrl+Enter, also for just the selection); multiple statements per script; result table with detail view and TSV export; cancel running queries |
| **Redis** | Search keys by pattern (SCAN, load more), view values of all types (string, list, set, sorted set, hash, stream), edit strings, set TTL, delete keys |
| **Snippets** | Saved commands, sent to any open terminal (run immediately or insert only) |
| **Design** | Default is **PowerShell blue**: app, title bar and terminal in the console blue `#012456` with the Campbell colors (yellow/cyan accents). Alternatively light, dark or "follow Windows" with a Mica background; terminal schemes PowerShell, Campbell, Midnight, Daylight, Contrast; configurable font and size |

Keyboard shortcuts: **Ctrl+Shift+T** new local terminal · **Ctrl+Shift+W** close tab · **Ctrl+Tab** / **Ctrl+Shift+Tab** switch tabs.
(Ctrl+T/Ctrl+W are intentionally left to the shell.)

## Requirements (Windows 11)

- **JDK 21** (e.g. [Eclipse Temurin 21](https://adoptium.net/) or the Microsoft Build of OpenJDK 21).
  Gradle itself may run on a newer JDK (tested with JDK 25); compiling, running and packaging always use the
  JDK 21 toolchain. If none is installed, the Foojay resolver downloads one automatically.
- **Internet access for the first build** (Gradle dependencies, JediTerm from the JetBrains repository).
- **WiX Toolset 3** for the MSI – does *not* need to be installed manually: the Compose Gradle plugin downloads
  WiX 3.11 automatically on the first `packageMsi` (task `downloadWix`). Only without internet access do you need to
  install WiX 3.x yourself and set the `WIX` environment variable.
- Optional, for Mica: Windows 11 22H2 (build 22621) or newer. Older versions get an opaque background.

## Build, test, run

```powershell
# Build everything and run all tests
.\gradlew build

# Start the app
.\gradlew :app-desktop:run

# Create the MSI installer → app-desktop\build\compose\binaries\main\msi\OpenSSH-1.0.0.msi
.\gradlew :app-desktop:packageMsi
```

The installer installs per user (no admin rights required), adds a start menu entry and a desktop shortcut,
and ships its own Java runtime. New versions replace old ones (fixed `upgradeUuid`; bump `packageVersion`
in `app-desktop/build.gradle.kts` for a new release).

### Local test server

To try the app without your own server there is an embedded SSH/SFTP server (Apache MINA SSHD):

```powershell
.\gradlew :core-ssh:runTestServer
```

It listens on `127.0.0.1:2222`, user `tester`, password `s3cret!`. The "shell" is a simple echo server;
SFTP and port forwarding work fully.

### Portable mode / separate test data

The environment variable `OPENSSH_DATA_DIR` moves hosts, settings and `known_hosts` to any folder:

```powershell
$env:OPENSSH_DATA_DIR = "D:\OpenSSH-Data"; .\gradlew :app-desktop:run
```

## Where is my data?

| What | Where |
|---|---|
| Hosts, key metadata, snippets, database connections, settings | `%APPDATA%\OpenSSH\*.json` |
| Known host keys | `%APPDATA%\OpenSSH\known_hosts` (OpenSSH format) |
| Passwords, passphrases, private keys | Windows Credential Manager (generic credentials `OpenSSH:*`) |
| Secrets larger than 2560 bytes (e.g. RSA-4096 keys) | `%APPDATA%\OpenSSH\secrets\*.bin`, encrypted with DPAPI (bound to the Windows user) |

The JSON files never contain secrets.

## Project structure

```
build-logic/   Convention plugins (openssh.kotlin-library, openssh.serialization, openssh.compose-app)
core-ssh/      Connection, auth (password, keyboard-interactive, OpenSSH/PEM/PKCS#8/PuTTY keys),
               known_hosts, shell with PTY, exec, local port forwarding
               testFixtures: embedded SSH server used by the tests of all modules
core-sftp/     Directory listing, recursive upload/download with progress, TransferQueue (drag & drop)
core-db/       JDBC (MariaDB, PostgreSQL, SQLite) + HikariCP through SSH tunnels, metadata, SQL splitter
  └ redis/     Redis key browser (Lettuce)
vault/         SecretVault interface, Windows Credential Manager + DPAPI file vault (JNA)
terminal/      JediTerm integration for SSH shells and local pty4j shells, themes
app-desktop/   Compose UI, persistence, packaging (MSI)
```

The core modules never depend on the UI; `app-desktop` is the only module using Compose.
All versions live in `gradle/libs.versions.toml`.

## Tests

`.\gradlew build` runs 81 tests, including real integration tests:

- SSH against an embedded server: password and key login, key fallback, rejected host keys, PTY shell, exec, port forwarding
- SFTP: recursive uploads/downloads, progress, cancellation without leftover partial files, transfer queue
- Databases: SQLite via JDBC/HikariCP, Redis via a RESP fake server – directly and through an SSH tunnel
- Vault: on Windows against the real Credential Manager and DPAPI (test entries are removed afterwards)
- Terminal: ConPTY start of a real local shell

## Known limitations

- SSH agent/Pageant, jump hosts and remote/dynamic port forwarding are not built in.
- SQLite databases are opened as local files (not via SFTP from the server).
- The database editor has no syntax highlighting or auto-completion.

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE.md).
