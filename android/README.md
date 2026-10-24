# Magic Portal for Android

Native Android client for the Magic Portal backend: everything the web UI does,
plus a camera card scanner and adding cards to the collection.

Kotlin, Jetpack Compose, Material 3. Two Gradle modules:

| Module | Contents |
|---|---|
| `:app` | All screens of the web UI, network, settings |
| `:scanner` | Camera, text recognition, on-device catalog (phase 2) |

## Setup on macOS

Android Studio brings the JDK and the SDK:

```bash
brew install --cask android-studio
```

Start it once and run the "Standard" setup; it installs the SDK to
`~/Library/Android/sdk` and asks you to accept the SDK licenses. Then, in
`~/.zshrc`:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

Do **not** install Gradle separately: `./gradlew` pins the version.

## Phone over Wi-Fi

1. On the phone: Settings → Developer options → **Wireless debugging** on.
2. Pair once: "Pair device with pairing code" shows an address and a code.
   ```bash
   adb pair 192.168.x.y:PAIRING_PORT
   ```
3. Connect and route the backend (the address on the "Wireless debugging"
   screen itself, not the pairing port):
   ```bash
   tools/dev-connect.sh 192.168.x.y:PORT
   ```

After a reboot or toggling wireless debugging the port changes; run the script
again with the new address. Without an argument it reconnects to the last one.

## Backend

The debug build talks to `http://127.0.0.1:8088`. The script sets up
`adb reverse tcp:8088 tcp:8088`, so that address on the phone is the local
stack on the Mac:

```bash
docker compose -f docker-compose.local.yml up -d --build   # from the repo root
```

The local stack stays bound to `127.0.0.1`; nothing is opened to the network.
The release build talks HTTPS to `https://mtg.kirkanos.net` only.

## Build and run

```bash
./gradlew :app:installDebug           # build and install on the connected phone
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:assembleDebug          # APK in app/build/outputs/apk/debug/
```

The debug app is installed as "Magic Portal" with the id
`net.kirkanos.mtgportal.debug`, so it can coexist with a release build.

## Release-Signierung

Der Release-Schlüssel liegt mit sops verschlüsselt in `signing/` – für denselben
age-Empfänger wie `.env.enc`, die CI entschlüsselt ihn mit `sops_age_key`:

- `signing/release.jks.enc` – der Keystore (PKCS12, Alias `mtgportal`)
- `signing/signing.env.enc` – `MTG_KEYSTORE_PASSWORD`, `MTG_KEY_ALIAS`, `MTG_KEY_PASSWORD`

Gradle signiert Release-Builds nur, wenn `MTG_KEYSTORE` auf den entschlüsselten
Keystore zeigt; lokal bleiben sie unsigniert. `versionCode` ist in der CI die
Pipeline-Nummer, damit jede neue APK sich über die alte installieren lässt.

Den Schlüssel nicht verlieren: Android installiert ein Update nur mit derselben
Signatur. Eine Kopie gehört in den Passwortmanager (lokal entschlüsseln mit
`sops --decrypt --input-type binary --output-type binary signing/release.jks.enc`).
