# SeaPanel

A lightweight **Paper / Purpur** plugin with an embedded **Web Dashboard** accessible directly via your server's IP address (`http://<server-ip>:8080`) — no domain or external web server required.

Compatible with **Minecraft 1.21.11 – 26.3+** (Java 21+).

## Features

- **Zero-Config IP Access** — Embedded HTTP server (`0.0.0.0:8080`) with auto-generated password on first startup.
- **Server Icon Integration** — Automatically displays your server's `server-icon.png` in the navbar, with a Minecraft Grass Block fallback.
- **Overview & Live Telemetry** — Real-time TPS (with live sparkline), MSPT, JVM Memory, CPU load, Uptime, Broadcast, Save All, and Stop Server.
- **Player Management** — View online players, ping, HP/Food, coordinates, and full inventory. Heal/Feed, Toggle Fly, OP/De-OP, Gamemode, Teleport, Private Message, Clear Inventory, Kick, Ban, and Unban.
- **Plugins & Malware Scanner** — List installed plugins, Enable/Disable plugins, and scan plugin `.jar` bytecode for backdoors, Force-OP (`setOp`), Discord Webhooks, `ProcessBuilder` / `Runtime.exec`, and dynamic classloaders.
- **In-Browser Config Editor** — Safely browse and edit plugin configs (`.yml`, `.json`, `.toml`, `.properties`) and core server configs (`server.properties`, `bukkit.yml`, `paper-global.yml`).
- **Live Console (SSE)** — Real-time server log streaming and command execution with history navigation.
- **Worlds & Whitelist** — Control time, weather, difficulty per world, manage whitelist players, and set default gamemode.
- **Dark / Light Theme** — Minimalist monochrome UI with persistent theme toggle.

## Installation

1. Download or build `SeaPanel-1.0.0.jar` (`mvn clean package`).
2. Drop the `.jar` into your Paper/Purpur server's `plugins/` folder.
3. Start the server and check the console for your dashboard URL (`http://<server-ip>:8080`) and auto-generated password.
4. *(Optional)* Open TCP port `8080` in your firewall if accessing from another device.

## Configuration (`plugins/SeaPanel/config.yml`)

```yaml
web:
  host: "0.0.0.0"
  port: 8080
  password: "" # Auto-generated if left empty
  session-hours: 24
  server-name: "Minecraft Server"
```

## Commands

- `/seapanel info` — Show dashboard URLs
- `/seapanel reload` — Reload `config.yml`
- `/seapanel password <new>` — Update dashboard password and clear active sessions

## Build from Source

```bash
mvn clean package
```

Output JAR: `target/SeaPanel-1.0.0.jar`

## License

MIT © [Sea (seaze-code)](https://github.com/seaze-code)
