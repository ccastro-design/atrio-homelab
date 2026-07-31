# Atrio Homelab

*[Léeme en español](README.es.md)*

**An Android dashboard for your homelab.** All your self-hosted services on one screen,
each opening in its own tab inside the app — no jumping out to a browser, no losing the
session you just logged into.

An *atrio* is the courtyard you cross on your way into a house, before reaching the rooms.
That is exactly what this app does with your services.

It is built for people who self-host at home: a NAS, a handful of containers, maybe a VPN
to reach them from outside, and the habit of typing IP addresses with odd port numbers from
memory.

**Android 8.0+ · GPLv3 · No accounts, no telemetry, no servers of ours**

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.webp" width="200" alt="The dashboard, with services grouped by machine">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.webp" width="200" alt="Docker services, each showing whether it is up">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.webp" width="200" alt="Searching across your services">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.webp" width="200" alt="Scanning the network for machines">
</p>

## Where this came from

Atrio started out as something much smaller. I wanted a quick way to hand a link to the
aMule running on my NAS. And, separately, I was tired of typing IP addresses and odd port
numbers every time I needed one of my own services.

So a dashboard grew around the link sender. While away on holiday I realised I also needed
to reach my machines from outside the house, over a VPN. And "send a link" grew up into what
it is today: a customisable dashboard, with VPN support, that also hands `ed2k`, `magnet`
and `.torrent` links to whichever client you tell it to — aMule, qBittorrent, Transmission
or SABnzbd.

That is still all it does. It talks to your machines and to nothing else: it does not search
for content, index anything, host anything, or connect to any server you have not asked it
to.

## What it does

- **Every service in one place**, in groups, each with its own colour. Tap one and it opens
  in a tab that keeps you logged in.
- **Two addresses per machine** — one on your LAN, one over your VPN. The panel picks the
  right one based on the Wi-Fi network you are on.
- **It finds them for you.** Scan your network for machines and open ports, or import the
  dashboard you already run (Homer, Homarr, Heimdall and the like), icons included.
- **Send links to your download clients.** An `ed2k`, a `magnet` or a `.torrent` tapped in
  any app can be handed straight to your aMule, qBittorrent, Transmission or SABnzbd.
- **Credentials encrypted on the device**, with optional biometric unlock.
- **Genuinely yours**: colours, logo, background, card size — all configurable.
- Available in **English and Spanish**.

## What it does not do

- **It collects nothing.** No accounts, no analytics, no telemetry, no crash reporting.
  There is no server belonging to this project — the app talks only to the machines you
  configure yourself.
- **No background work, no notifications.** It checks whether your services are up only
  while the panel is in front of you, so it never fights your phone's battery saver.
- **It ships no third-party logos.** Icons come from Material Icons, from your own service
  (its favicon), or from an image you choose.

## Privacy

Nothing leaves your device. Your panel, your addresses and your credentials are stored in
the app's private storage; passwords are encrypted with AES-256 using a key held in the
device's own keystore. Android's cloud backup is deliberately disabled for this app.

The full policy is in [PRIVACY.md](PRIVACY.md).

### Permissions, and why

| Permission | Why |
| --- | --- |
| Internet, network state | To reach your machines and know whether you are connected |
| Biometrics | Only if you switch on biometric unlock; off by default |
| Location | **Only to read the name of the Wi-Fi network you are on** |

That last one deserves an explanation, because it looks worse than it is. Since Android 8.1
the Wi-Fi network name is readable only by apps holding a location permission — there is no
other way to get it. **Your position is never requested, read or stored.**

The app needs the network name because deciding "I am at home" from an IP address alone is
unsafe: if some device answers at `192.168.1.254` at a friend's house too, the app would
happily send **your saved passwords** to their machine. The network name is the reliable
signal. The permission is only requested when you choose to register a network, the app
never learns one on its own, and everything works without granting it.

## Building from source

You need JDK 17 and the Android SDK (platform 35). With the SDK path set in
`local.properties`:

```
./gradlew assembleDebug
```

The wrapper fetches the right Gradle version on its own, so nothing else needs installing.
`./gradlew test` runs the unit tests.

Written in Kotlin with Jetpack Compose. Minimum supported version is Android 8.0 (API 26).

## Status

First release. Available here as a signed APK, and on its way to Google Play.

### Looking for testers

Google Play requires a new developer account to run a closed test with **12 testers for 14
consecutive days** before an app can reach the store. Atrio is there now, and short of
people.

If you run a homelab and have a few minutes, joining is a real help: you opt in with your
Google account, install the app from Play, and stay opted in for two weeks. Nothing else is
asked of you, and there is nothing to pay — the app is free and always will be.

Open an issue or write to **atrio.homelab.app@proton.me** and I will send you the link.
Feedback on what does not make sense is more than welcome; that is the point of a test.

## Licence

[GPLv3](LICENSE). Anyone can read the source and check what it does with their passwords,
which in an app like this one is not a minor detail. The copyleft also means nobody can
take it closed-source and bolt ads onto it.

## Contact

atrio.homelab.app@proton.me
