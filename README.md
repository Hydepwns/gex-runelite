# GEX RuneLite Plugin

RuneLite plugin for [GEX](https://gex.droo.foo) - crowdsourced Grand Exchange intelligence.

## Why GEX?

**The problem with existing tools:**
- Wiki prices tell you what items *listed* for, not what actually *filled*
- Local-only trackers (like Flipping Utilities) trap your data in JSON files
- Your trading history helps no one, and no one's helps you

**GEX is different:**
- Your fills feed the network, the network feeds you back better estimates
- See what prices are *actually clearing* across hundreds of traders
- Day-aware ETAs: Tuesday 3 PM trades differently than Sunday midnight
- Web dashboard at [gex.droo.foo](https://gex.droo.foo) - check signals without launching the client

## Features

| Feature | What it does |
|---------|--------------|
| **Fill ETAs** | Estimated time to fill based on real network data |
| **Margin overlay** | Profit estimate shown on GE slots |
| **Day/hour curves** | ETAs adjust for time-of-day patterns |
| **Offline queue** | Events sync when you reconnect |
| **Web dashboard** | Browse signals, timing heatmaps, ML predictions |

## How it works

1. Plugin watches your GE slots
2. Events batch and sync to gex.droo.foo every few seconds
3. Backend aggregates fills across all users
4. You get fill estimates trained on real data, not wiki volume

```
Your fills --> GEX Network --> Better predictions for everyone
                   |
                   +--> Fill curves, timing patterns, anomaly detection
```

## Privacy

- Account ID is SHA-256 hashed with a prefix before transmission - the raw RuneLite account hash never leaves your client
- No RSN, no credentials, no personal info transmitted
- You can inspect the source - it's ~800 lines of straightforward Java
- The hashed ID allows the backend to correlate your trades for P&L tracking, but cannot be reversed to identify your account

## Config

| Setting | Default | What it does |
|---------|---------|--------------|
| API Endpoint | gex.droo.foo | Where data syncs (change for self-hosting) |
| Heartbeat | 60s | Full state sync interval |
| Show Overlay | On | Margin/ETA on GE interface |
| Show Panel | On | Side panel with history |
| Day-Aware ETA | On | Use day-specific fill curves |

## Installation

**Plugin Hub:** Search "GEX" and install.

**Manual:** Download the JAR from [releases](https://github.com/Hydepwns/gex-runelite/releases) and drop it in `~/.runelite/plugins/`.

## Building

```bash
./gradlew build
# JAR -> build/libs/gex-*.jar
```

## vs Flipping Utilities

| | GEX | Flipping Utilities |
|---|-----|-------------------|
| Data | Networked | Local JSON |
| Fill estimates | From real fills | From wiki volume |
| Time-of-day aware | Yes | No |
| Web dashboard | Yes | No |
| Your data helps others | Yes | No |
| Others' data helps you | Yes | No |

FU is a great local tracker. GEX is networked intelligence. Use both if you want - GEX doesn't interfere.

## Links

- **Web:** [gex.droo.foo](https://gex.droo.foo)
- **Issues:** [GitHub](https://github.com/Hydepwns/gex-runelite/issues)
- **Backend:** [github.com/Hydepwns/gex](https://github.com/Hydepwns/gex) (Elixir/Phoenix)

## License

BSD 2-Clause
