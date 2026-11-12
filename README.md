# Masar

**[masar.ammarhussein.cloud](https://masar.ammarhussein.cloud)** · developed by Ammar

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
- **A home handle** over any app, CarPlay included: touch the screen and a bar with Home, Back, volume and your apps appears for a few seconds. See [Home handle](#home-handle).
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
| Long-press the dots at the bottom, or the handle's status line | Steps the home handle through bar, pill and off |
| Long-press the clock | Settings: home handle, speed alert, 8 or 12 tiles, accent colour, background photo, night dimming, prayer times, language |
| Tap the prayer line | The day's prayer times |
| Long-press the trip figures (drive page) | Reset the trip |
| Touch the screen in any app | Shows the home bar (or pill) for a few seconds |

## Home handle

Some apps hide the system navigation bar, CarPlay in the Carlinkit AutoKit
app among them, and you may prefer the bar hidden everywhere. The home
handle gives the buttons back on demand: **touch the screen anywhere** and
it appears at the bottom for about four seconds, then goes away.

It has two looks. Long-press the handle's status line on Masar's home page
(or the two page dots) to step through **bar → pill → off**:

- **Bar** (default): a row of buttons like the head unit's own bar. Back,
  Recents, volume down, volume up, Home, and the apps from Masar's tiles 2
  to 4. It works in every app, Masar included.
- **Pill**: a small white capsule. Tap it, or swipe up from it, to go home.
  It covers far less of the screen, and stays away while Masar is in front.

The touch that reveals it still goes to the app underneath as normal. While
it is hidden nothing of Masar's covers the screen. While the bar is showing
it does cover the bottom tenth of the screen, so a control an app puts there
is out of reach for those four seconds; if that gets in the way, use the pill.

**State:** the third status line on Masar's home page says which look is on,
that the handle is off, or that it is waiting for its permission. Tap the
line to grant a missing permission.

**Back and Recents** can only be pressed through an accessibility service,
so Masar has a small one that asks for no events and never reads the screen.
Switch it on once under Settings → Accessibility → *Masar Back and Recents
buttons*, or over adb:

```bash
adb shell settings put secure enabled_accessibility_services cloud.ammarhussein.masar/cloud.ammarhussein.masar.KeysService
adb shell settings put secure accessibility_enabled 1
```

Until then those two buttons open the Accessibility screen. Home, volume and
the app buttons need nothing extra.

**Hiding the system bar everywhere** (Android 10 and older) is a system
setting, not something a launcher can do:

```bash
adb shell settings put global policy_control immersive.navigation=*
```

Undo it with `adb shell settings put global policy_control null`. With it
on, a swipe up from the bottom edge still brings the real bar back briefly.

The handle runs as a foreground service, so Android keeps it alive while
Masar is in the background for a whole drive. A quiet "Masar is running"
notification is the price of that. After a reboot it comes back as soon as
Masar starts as the home app.

**Permission:** the handle draws over other apps, which needs the "Display
over other apps" permission. Tapping the amber status line opens that system
screen. On head units where the screen is missing or doesn't stick, grant it
over adb:

```bash
adb shell appops set cloud.ammarhussein.masar SYSTEM_ALERT_WINDOW allow
```

Then press Home (or restart Masar). To take the permission away again, use
`deny` instead of `allow`.

## Settings

Long-press the clock. Everything is off or neutral until you choose it.

- **Now playing.** A strip under the tiles with the title, artist and
  previous / play / next for whatever is playing on the unit. Android only
  shows this to apps with *Notification access*; the menu line opens that
  screen, or over adb:
  `adb shell cmd notification allow_listener cloud.ammarhussein.masar/cloud.ammarhussein.masar.MediaService`.
  Masar reads no notifications. Audio that an app plays without a media
  session (some CarPlay dongle apps) does not show.
- **Speed alert.** Pick a limit from 60 to 140 km/h. Crossing it beeps once
  and the speed turns red; in other apps a red speed chip shows at the top
  until the car slows down (needs "Display over other apps").
- **Trip.** Distance, moving time, average and top speed on the drive page,
  from GPS. A new trip starts by itself after the car has been parked for 30
  minutes.
- **Prayer times.** The next prayer and the time left, under the date. Worked
  out on the unit from GPS with the Umm al-Qura method, no internet needed.
  Expect a minute or two of difference from a local mosque.
- **Night dimming.** A dark veil over Masar after sunset (or always, or
  never). It dims Masar's own screens, not other apps.
- **Tiles.** 8 in two rows or 12 in three.
- **Accent colour** and **background photo.** The photo is copied into Masar
  and darkened so the tiles stay readable.
- **Language.** English or Arabic, independent of the unit's language.

## Car page (OBD)

Swipe to the third page. With a classic-Bluetooth ELM327 dongle in the OBD
port and paired in the unit's Bluetooth settings, it shows engine speed, the
car's own speed, coolant and intake temperature, engine load, battery voltage,
fuel level where the car reports it, and stored fault codes with a button to
clear them. On GM trucks it also tries the transmission temperature. Readings
the car does not give get no tile. Masar picks the paired device with "OBD" in
its name; tap the status line on the page to choose another.

## Dash cam page

The fourth page shows the front and rear live view of a Wi-Fi dash cam, side
by side; tap one to enlarge it. Written for the 70mai A800S, whose two streams
are plain RTSP. The camera is its own Wi-Fi network without internet, so the
unit leaves the phone's hotspot while the view is open and returns to it when
you close the camera or leave the page. The camera's Wi-Fi password is asked
for once and kept only on the unit.

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
