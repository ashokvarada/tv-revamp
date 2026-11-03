# TV Revamp

A cleaner, faster Home screen for Android TV and Google TV, in the style of Prime Video and Apple TV: your apps in one
row, the newest shows on each of your streaming services behind them, a single Continue watching row across every
app, and an Explore page of mood and genre rows. It's built on
[cleanyourtv](https://github.com/Gathin23/cleanyourtv) by Gathin23, which also removes the bloat and ads from your TV
(see [CLEANYOURTV.md](CLEANYOURTV.md)).

Tested on a **Google TV Streamer (Android 14)**. Any Android TV or Google TV on Android 8 or later should work; the
Home button setup can differ between makes (see [Other TVs](#other-tvs)).

---

## What you get

**Home**
- **One row of apps**, a theme-coloured rectangle round the one in focus.
- **What's new behind each app**: the focused app's newest titles fill the screen, one every 5 seconds, with the
  title, language, where to watch it and its TMDB rating. Your preferred languages come first.
- **Press Up for the details**: the summary appears and the picture zooms in. Left and Right move between titles;
  **OK** watches it, **hold OK** removes it for good.
- **Watch now goes straight to the show where it can**: titles in Continue watching open directly in their app, and
  YouTube and Prime Video open their own search with the title filled in. Other apps (Netflix, Hulu, Crunchyroll,
  Max) don't accept that, so those go through Google TV's search.
- **Continue watching**: one row under the apps (press **Down**), every app's unfinished titles together, a series
  shown once at its latest episode. Only what you watched in the last 7 days by default.
- **Refresh** (top right) fetches the latest titles now, with a full-screen wipe.
- **Side bar** (press **Left**): Home, Explore and Personalize.
- **Hold Back** in any app goes straight Home. **Hold Home** offers to switch back to the TV's original Home.

**Explore**: everything new on your services, in rows by mood ("Edge of your seat", "Feel-good", "Mind-bending"…) and
by genre (Action, Adventure, Comedy, Romance, Drama, Horror, Thriller, Sci-Fi, Fantasy). OK opens the details with
Watch now and Not interested.

**Personalize**: your name in the greeting ("Good evening, Sam"), how far back Continue watching goes (7, 14 or 30 days,
or all), and the highlight colour, picked from 30 swatches. The colour is used everywhere on Home and in Explore.

**Services covered**: Netflix, Prime Video, Disney+, Hulu, Apple TV+, Max, Crunchyroll, ZEE5, Tubi, Plex and YouTube
(only the ones installed on your TV are used).

---

## How it works

Two apps, both built from this repo:

| Folder | App | What it does | Internet |
|---|---|---|---|
| [`launcher/`](launcher) | **Home** (`com.gathin.tvhome`) | The Home screen, side bar and Personalize | **None** |
| [`companion/`](companion) | **Latest** (`com.tvlatest`) | Fetches new titles from TMDB once a day, reads Android's Continue watching list, and shows Explore | TMDB only |

The Home screen reads everything from the Latest app, so it never goes online itself. Nothing is sent anywhere but
TMDB; there are no accounts, ads or analytics.

---

## What you need

| | Where to get it |
|---|---|
| **A computer** (Windows for the scripts below; macOS and Linux work with the same adb commands) | |
| **Android SDK platform-tools** (`adb`) and **SDK Platform 34** | Easiest: install [Android Studio](https://developer.android.com/studio), open *SDK Manager*, tick *Android 14 (API 34)* and *Android SDK Platform-Tools*. Or the [command-line tools](https://developer.android.com/studio#command-line-tools-only). |
| **JDK 17 to 21** (not newer: the Gradle version here doesn't support JDK 22+) | [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21) |
| **A free TMDB API key** | Sign up at [themoviedb.org](https://www.themoviedb.org/signup), then *Settings → API → Create → Developer*. Either the *API Key* or the *API Read Access Token* works. |
| **Git** | [git-scm.com](https://git-scm.com/downloads) |
| **The TV and computer on the same network** | |

## Setup

### 1. Turn on debugging on the TV

1. *Settings → System → About*, then select **Android TV OS build** seven times until it says you're a developer.
2. *Settings → System → Developer options*: turn on **USB debugging**, and **Network debugging** (on some TVs it's
   called *ADB over network*; on Android 11+ you can use *Wireless debugging* instead).
3. Note the TV's IP address: *Settings → Network & Internet →* your network.

### 2. Get the code

```powershell
git clone https://github.com/<your-account>/tv-revamp.git
cd tv-revamp
```

### 3. Connect to the TV

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" connect 192.168.1.50:5555
```

Use your TV's IP address. The TV asks **Allow USB debugging?**: tick *Always allow* and choose **Allow**.

### 4. (Optional) Remove the bloat first

This repo includes everything from cleanyourtv. To debloat the TV safely (disable, never uninstall, with a backup and a
restore script), follow [CLEANYOURTV.md](CLEANYOURTV.md) and [PROMPT.md](PROMPT.md). You can skip this and just change
the Home screen.

### 5. Build and install

```powershell
powershell -ExecutionPolicy Bypass -File revamp\install.ps1 -TvIp 192.168.1.50
```

This builds both apps, installs them, makes the new Home the TV's Home, turns on the Home button helper, and lets the
Latest app read Continue watching. The first build takes a few minutes while Gradle downloads its parts.

### 6. Add your TMDB key

```powershell
powershell -ExecutionPolicy Bypass -File revamp\set-tmdb-key.ps1 -TvIp 192.168.1.50 -Regions US -Langs en
```

Paste your key when asked. It's sent straight to the TV and stored only there.
- `-Regions`: the countries whose streaming catalogues to use, comma separated (for example `US,IN` or `GB`).
- `-Langs`: original languages to show first, comma separated ISO codes (for example `ta,te,hi,en` or `es,en`).

Within a minute the new titles appear behind the apps. Run the script again any time to change them.

### 7. Check the Home button

Press **Home** on the real remote. Keys sent from the computer don't go through the Home button helper, so only the
real remote shows whether it works. If the TV's own Home appears instead, see [Troubleshooting](#troubleshooting).

---

## Remote controls

| Where | Key | Does |
|---|---|---|
| An app | **Up** | Opens the details for the title behind it |
| | **Down** | Scrolls down to Continue watching |
| | **Left** (first app) | Opens the side bar |
| Details | **Left / Right** | Previous / next title (Left on the first opens the side bar) |
| | **OK** | Watch now |
| | **Hold OK** | Remove this title for good |
| | **Down / Back** | Back to the app |
| Continue watching | **OK** | Resume in its app |
| | **Hold OK** | Remove it |
| Top bar | Refresh · Settings · Wi-Fi | **Right** from Wi-Fi or **Left** from Refresh opens the side bar |
| Anywhere | **Hold Back** | Home |
| | **Hold Home** | Switch between this Home and the TV's own |

---

## Customise

- **Apps in the row**: `dock_apps` in [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml),
  as package names. Then run `install.ps1` again.
- **Streaming services and their TMDB ids**:
  [`companion/app/src/main/java/com/tvlatest/Services.java`](companion/app/src/main/java/com/tvlatest/Services.java).
- **Explore's mood and genre rows**:
  [`companion/app/src/main/java/com/tvlatest/Moods.java`](companion/app/src/main/java/com/tvlatest/Moods.java).
- **Name, colour, Continue watching age**: on the TV, in the side bar's **Personalize**.
- **Removed a title by mistake?** `adb shell pm clear com.tvlatest` brings back everything you removed. It also clears
  the key, so run `set-tmdb-key.ps1` again afterwards.

## Good to know

- **Continue watching** only shows apps that share their list with Android. On the test TV that was Netflix,
  Crunchyroll, Max and Plex; Prime Video, Disney+, Hulu, Apple TV and YouTube don't share theirs. Plex doesn't say
  when you watched something, so its titles only appear with Personalize → *All*.
- **Profiles**: apps like Netflix and Prime Video draw their own screens, so Home can't pick a profile for you. They
  may ask "Who's watching?" when opened.
- **Google TV and Cast**: the TV's original launcher stays installed, because Cast and the setup wizard need it. Home's
  helper simply sends its home screen back to this one (`stock_home_redirect` in `config.xml`). That setting is for
  Google TV; on other TVs set it to your TV's launcher package, or leave it empty.

## Other TVs

The Home button is the part that varies most between makes. If pressing Home on your remote doesn't open the new Home,
[CLEANYOURTV.md](CLEANYOURTV.md) and [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) cover the options (making it the
default Home, the Home key helper, and tested settings for many TVs in [`examples/`](examples)).

## Troubleshooting

| Problem | Fix |
|---|---|
| `adb connect` fails | Network debugging is off, or the TV's IP changed. Check both, then accept the prompt on the TV. |
| Build fails with "Unsupported class file major version" | Your JDK is too new. Install JDK 21 and set `JAVA_HOME` to it. |
| Build says the SDK isn't found | Set `ANDROID_HOME` to your Android SDK folder, or delete `local.properties` so the script writes it again. |
| No titles behind the apps | Run `set-tmdb-key.ps1` again and check the key. The TV needs internet; a refresh takes under a minute. |
| Home button opens the TV's own Home | The helper was switched off (a reinstall or force stop does this). Run `install.ps1 -SkipBuild` again. If you chose the TV's Home with *hold Home*, hold Home again to switch back. |
| Continue watching is empty | Raise the limit in Personalize, or watch something in an app that shares its list (see above). |

## Undo

- **Go back to the TV's Home for now**: hold **Home** on the remote and choose to switch.
- **Remove the apps completely**:
  ```powershell
  adb shell settings put secure enabled_accessibility_services ''
  adb uninstall com.gathin.tvhome
  adb uninstall com.tvlatest
  ```
  The TV goes back to its own Home. If you debloated it, [CLEANYOURTV.md](CLEANYOURTV.md) explains how to restore
  what was disabled.

## Credits and licence

- Built on [cleanyourtv](https://github.com/Gathin23/cleanyourtv) by Gathin23 and its contributors, under the
  [MIT licence](LICENSE). This repo is a fork and keeps that licence.
- Title data and images come from [TMDB](https://www.themoviedb.org). *This product uses the TMDB API but is not
  endorsed or certified by TMDB.*
- Streaming service names and logos belong to their owners. This project isn't affiliated with any of them.
