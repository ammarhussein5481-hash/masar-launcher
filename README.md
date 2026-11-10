# Masar

A calm, glanceable home screen for Android car head units. *Masar* (مسار) means "route".

![Design preview](docs/preview.png)

*Design preview at 1200×720. Your own app icons and names appear in the tiles.*

## Why

Most car launchers are either phone launchers stretched onto a dashboard or
trial-ware with a paywall. Masar is two pages that do the few things a
driver needs, with targets big enough to hit without looking twice.

- **Eight large tiles.** Seven apps of your choice and an "All apps" tile.
- **CarPlay first.** If the Carlinkit AutoKit app is installed, it takes the highlighted first tile.
- **Clock and date** that follow the system 12/24-hour setting.
- **GPS speed and heading**, plus how many satellites are in use.
- **Internet indicator**, so a dropped hotspot is obvious at a glance.
- **A drive page** one swipe away, with three panels:
  - **Compass** that turns with the direction of travel, plus coordinates and altitude.
  - **Map** that follows the car. Roads already driven stay visible offline, and a tap opens your navigation app.
  - **Weather** where you are: current conditions and the next three days.
- **A home handle** over full-screen apps such as CarPlay: touch the screen, tap the pill, and you're back. See [Home handle](#home-handle).
- **Scales itself.** Every size is a fraction of the screen height, so it looks the same on any resolution or density.
- No ads, no trial, no account.

The drive page is the only part that uses the internet: map tiles come from
[OpenStreetMap](https://www.openstreetmap.org/copyright) and the forecast from
[Open-Meteo](https://open-meteo.com/). Both receive the car's position, which is
how they know what to send back. The home page works with no connection at all.

## Using it

| Action | Result |
|---|---|
| Tap a tile | Opens the app |
| Long-press a tile | Choose which app lives there, or empty it |
| Tap **All apps** | Full app grid |
| Long-press an app in the grid | Pin to a tile, app info, or uninstall |
| Tap the speed area | Grants location access (needed once, for speed) |
| Swipe sideways, or tap the dots at the bottom | Switches between home and the drive page |
| Tap the map | Opens the position in your navigation app |
| Tap **+** / **−** on the map | Zooms in or out |
| Tap the weather | Refreshes it now |
| Press Home or Back | Returns to the home page |
| Long-press the dots at the bottom, or the handle's status line | Turns the home handle on or off |
| Touch the screen in another app, then tap the white pill | Returns to Masar (when the home handle is on) |

## Home handle

Full-screen apps such as CarPlay in the Carlinkit AutoKit app hide the
system navigation bar, so there is no Home button to press. While Masar is
not on screen, **touch the screen anywhere** and a white pill appears at the
bottom centre for about three seconds. **Tap the pill**, or swipe up from
it, and Masar's home page comes back.

The touch that reveals the pill still goes to the app underneath as normal.
While the pill is hidden nothing of Masar's covers the screen, so it never
swallows a tap meant for another app.

**State:** the third status line on Masar's home page says whether the
handle is on, off, or waiting for its permission. Tap that line to grant a
missing permission.

**Turning it off and on:** long-press that status line, or the two page
dots at the bottom of Masar. The choice is remembered. It is on by default.

It runs as a foreground service, so Android keeps it alive while Masar is in
the background for a whole drive. A quiet "Home handle is on" notification is
the price of that. After a reboot it comes back as soon as Masar starts as
the home app.

**Permission:** the handle draws over other apps, which needs the "Display
over other apps" permission. Masar opens that system screen for you the
first time (and whenever you turn the handle on without it). On head units
where that screen is missing or doesn't stick, grant it over adb:

```bash
adb shell appops set cloud.ammarhussein.masar SYSTEM_ALERT_WINDOW allow
```

Then press Home (or restart Masar) and the handle appears in other apps.
To take the permission away again, use `deny` instead of `allow`.

## Install

Download `masar.apk` from the [latest release](../../releases/latest), then:

```bash
adb install -r masar.apk
adb shell pm grant cloud.ammarhussein.masar android.permission.ACCESS_FINE_LOCATION
adb shell appops set cloud.ammarhussein.masar SYSTEM_ALERT_WINDOW allow
adb shell cmd package set-home-activity cloud.ammarhussein.masar/.MainActivity
adb shell input keyevent HOME
```

To go back to the previous launcher, set it as home again with
`cmd package set-home-activity <package>/<activity>`, or pick it in
Settings → Apps → Default apps → Home app.

## Requirements

Android 8.0 or newer. Built and tuned on a Rockchip RK3399 head unit running Android 10.

## Build

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. There are no dependencies
beyond the Android SDK and the Kotlin standard library. Every push to `main`
makes GitHub Actions build a signed APK and publish it as a new release.

## Licence

MIT
