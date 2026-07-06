# Glooko Gluroo Bridge

Android app that reads insulin and pump data from [Glooko](https://www.glooko.com/) using the unofficial API approach from [spamsch/glooko-reader](https://github.com/spamsch/glooko-reader), then publishes bolus and pump events to [Gluroo](https://gluroo.com/) via its Nightscout-compatible backend (Gluroo Global Connect).

**CGM values are not uploaded.** Configure CGM separately in Gluroo (Dexcom, xDrip, etc.).

## What it syncs

| Data | Destination |
|------|-------------|
| Bolus deliveries | Nightscout `Meal Bolus` / `Correction Bolus` treatments |
| Carbs at bolus time | `carbs` field on treatment |
| IOB at bolus time | `notes` field (informational) |
| Pump mode changes | Optional `Note` treatment |

## Important limitations

- **Not real-time**: Omnipod 5 syncs to Glooko every 1–4 hours.
- **Unofficial Glooko API**: Uses browser-login flow; may break if Glooko changes their web app.
- **Not for dosing**: For family sharing and retrospective monitoring only.
- Log into the **Glooko mobile app** at least once before using this bridge.

## Gluroo setup

1. In Gluroo: **Menu > Settings > Gluroo Global Connect Nightscout**
2. Copy your Nightscout URL and API secret into this app
3. Enter your Glooko email and password
4. Tap **Test** to verify both connections
5. Enable **background sync** and tap **Save**
6. Confirm bolus events appear in the Gluroo event log after the next sync

CGM source in Gluroo is unaffected — configure it separately as needed.

## Build

Requires Android SDK and JDK 17.

```bash
./gradlew assembleDebug
```

Install the debug APK from `app/build/outputs/apk/debug/`.

## Architecture

- `glooko/` — Glooko authentication and data parsers (ported from glooko-reader)
- `nightscout/` — Gluroo GGC treatment publisher
- `sync/` — WorkManager background sync with Room deduplication
- `ui/` — Jetpack Compose settings and status screen

## License

MIT
