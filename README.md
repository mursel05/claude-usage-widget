# Claude Usage Widget (Android)

A home-screen widget that shows your Claude **5-hour** and **weekly** usage with reset times. There's no launcher app: the widget is the only UI.

A **2×2** widget (can be stretched wider) with two rings:

- **5-hour** and **Weekly**: percent in the ring, label and live countdown under it ("in 2h 13m", ticks every minute while the screen is on).
- **Free resets** row: how many "Reset for free" limit resets you have and when the earliest expires. Tap it to open claude.ai usage settings. Hidden when you have none.
- Ring and percent turn amber at 70% and red at 90%.
- Data refreshes every 15 minutes (the Android minimum) and right after a window resets.
- **Tap anywhere on the widget to refresh now.** While it loads, the numbers dim and a spinner shows "Updating…". Taps within 30 seconds of the last refresh are ignored.
- When the session expires, the widget says so. Tap it to sign in again.
- Free and open source. The only dependency is AndroidX WorkManager.

## Build the APK (free, no Android Studio)

1. Create a GitHub repo (private is fine) and push this folder to `main`.
2. Go to **Actions → Build APK**. The run starts on push, or you can start it with *Run workflow*.
3. When it finishes, download the **claude-usage-widget** artifact (a zip containing `app-debug.apk`).

Or open the folder in Android Studio (it uses its own Gradle; accept if it offers to add a Gradle wrapper) and press **Run**.

## Install

1. Copy `app-debug.apk` to your phone and open it. Allow "Install unknown apps" for your file manager or browser when asked.
2. Long-press the home screen → **Widgets** → **Claude Usage** → drag it onto the screen.
3. A claude.ai login opens. **Sign in with email.** Google sign-in doesn't work in in-app browsers. The screen closes by itself once you're logged in.

Android 10+ may show an "app info" icon in the app drawer, because the app has no launcher screen. That's normal.

## How it works

| File | Role |
|---|---|
| `LoginActivity.kt` | Widget configure screen: claude.ai login in a WebView, captures the session cookie and org id |
| `ClaudeApi.kt` | `GET https://claude.ai/api/organizations/{orgId}/usage?cedar_ember=1&skip_spend=1` → `five_hour` / `seven_day` `{utilization, resets_at}`, `cedar_ember.grants[]` `{resets_left, ends_at}` |
| `Refresh.kt` | WorkManager: 15-min periodic job + on-demand refresh |
| `WidgetRenderer.kt` | RemoteViews rendering, ring bitmaps, minute countdown tick, tap actions |
| `UsageStore.kt` | App-private SharedPreferences (cookie, org id, cached usage) |

## Caveats

- **Unofficial endpoint.** `/api/organizations/{id}/usage` is claude.ai's internal web API. Anthropic can change or block it at any time.
- **The session cookie is a full-access credential.** It's stored in app-private storage with backup disabled and is only ever sent to `claude.ai`. Anyone with root access to your phone could read it.
- **Signing.** CI signs with a permanent key from the `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` repo secrets, so new builds install over old ones. Without those secrets it falls back to a throwaway debug key, and you'd have to uninstall between builds.
- Only Pro, Max, Team and Enterprise plans have usage windows. Free accounts may show `—`.
