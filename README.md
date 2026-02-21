# GEX RuneLite Plugin

Connects your Grand Exchange to [GEX](https://gex.droo.foo), a trading assistant that tracks fills across the network to give you better data than you'd get alone.

## Installation

Search "GEX" in the Plugin Hub and install.

## What it does

The plugin watches your GE slots and sends updates to the GEX backend. In return, you get:

- Fill time estimates based on actual fill data (not just wiki volume)
- Day-of-week aware ETAs — weekends trade differently than Tuesday morning
- Margin estimates shown right on your GE interface
- Notifications when offers complete, stall out, or hit your profit thresholds

If you go offline, events queue up locally and sync when you reconnect.

## Config options

| Setting | What it does |
|---------|--------------|
| API Endpoint | Where to send data (default: gex.droo.foo) |
| Heartbeat | How often to sync full state (default: 60s) |
| Overlay | Show profit/ETA on GE slots |
| Panel | Side panel with trade history |
| Day-Aware ETA | Use day-specific curves for estimates |

## Privacy

Your account hash is anonymized before transmission. No RSN, no credentials, no personal info leaves your client.

## Building from source

```bash
./gradlew build
```

JAR goes to `build/libs/`.

## License

BSD 2-Clause
