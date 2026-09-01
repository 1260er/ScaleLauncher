# Einrichtung und Verwendung der festen Release-Signatur

ScaleLauncher verwendet eine dauerhaft gleichbleibende Release-Signatur.
Der private Signierschlüssel wird nicht in Git gespeichert. GitHub Actions erhält ihn nur
über verschlüsselte Repository-Secrets.

## 1. Signierschlüssel lokal erzeugen

Auf dem eigenen Linux-Rechner:

```bash
mkdir -p "$HOME/ScaleLauncher-signing"
chmod 700 "$HOME/ScaleLauncher-signing"

keytool -genkeypair -v \
  -keystore "$HOME/ScaleLauncher-signing/scalelauncher-release.p12" \
  -storetype PKCS12 \
  -alias scalelauncher \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=ScaleLauncher, OU=Release, O=Pritcloud, C=DE"
```

Das Passwort sicher in einem Passwortmanager speichern. Der Schlüssel muss dauerhaft
aufbewahrt werden; ohne ihn sind keine Updates derselben App mehr möglich.

## 2. Zwei getrennte Sicherungskopien anlegen

```bash
sha256sum "$HOME/ScaleLauncher-signing/scalelauncher-release.p12"
keytool -list -v \
  -keystore "$HOME/ScaleLauncher-signing/scalelauncher-release.p12" \
  -alias scalelauncher
```

Mindestens zwei verschlüsselte Sicherungskopien an getrennten Orten aufbewahren.
Nicht ins Git-Repository committen.

## 3. GitHub-Secrets einrichten

Im Repository `1260er/ScaleLauncher` unter:

`Settings → Secrets and variables → Actions → New repository secret`

werden diese vier Secrets angelegt:

- `SCALELAUNCHER_KEYSTORE_BASE64`
- `SCALELAUNCHER_KEYSTORE_PASSWORD`
- `SCALELAUNCHER_KEY_ALIAS`
- `SCALELAUNCHER_KEY_PASSWORD`

Werte:

- `SCALELAUNCHER_KEYSTORE_BASE64`: Ausgabe dieses Befehls:

  ```bash
  base64 -w 0 "$HOME/ScaleLauncher-signing/scalelauncher-release.p12"
  ```

- `SCALELAUNCHER_KEYSTORE_PASSWORD`: Passwort des Keystores
- `SCALELAUNCHER_KEY_ALIAS`: `scalelauncher`
- `SCALELAUNCHER_KEY_PASSWORD`: Passwort des Schlüssels

Bei PKCS12 ist das Schlüsselpasswort üblicherweise identisch mit dem Keystore-Passwort.

## 4. Stabile Veröffentlichung 1.4.0

Nach abgeschlossener Endabnahme und Push von Version 1.4.0:

```bash
git tag -a v1.4.0 -m "ScaleLauncher 1.4.0"
git push origin v1.4.0
```

Der Workflow `Signed release` erzeugt:

- `ScaleLauncher-1.4.0.apk`
- `ScaleLauncher-1.4.0.apk.sha256`
- eine GitHub Release-Seite für Obtainium und manuelle Downloads

## 5. Jede weitere Veröffentlichung

Vor jeder Veröffentlichung in `app/build.gradle.kts` beide Werte erhöhen, zum Beispiel:

```kotlin
versionCode = 6
versionName = "1.5.0"
```

Dann committen, pushen und den passenden Tag setzen:

```bash
git tag -a v1.5.0 -m "ScaleLauncher 1.5.0"
git push origin v1.5.0
```

Die Tag-Version muss exakt mit `versionName` übereinstimmen. Der Release-Workflow bricht
ansonsten absichtlich ab.

## 6. Unabhängiger F-Droid-Quellbuild

Vor dem stabilen Tag muss der Release-Build ohne private Signierschlüssel erfolgreich durchlaufen:

```bash
env -u SCALELAUNCHER_KEYSTORE_PATH -u SCALELAUNCHER_KEYSTORE_PASSWORD -u SCALELAUNCHER_KEY_ALIAS -u SCALELAUNCHER_KEY_PASSWORD -u SCALELAUNCHER_REQUIRE_RELEASE_SIGNING gradle --no-daemon clean testDebugUnitTest assembleRelease
```

Dieser Build darf keinen privaten ScaleLauncher-Signierschlüssel voraussetzen. Die offizielle GitHub-Veröffentlichung verwendet dagegen weiterhin die dauerhaft hinterlegte Release-Signatur.

## 7. Reihenfolge einer stabilen Veröffentlichung

1. `versionName` und monoton steigenden `versionCode` festlegen.
2. CHANGELOG, README, Datenschutz- und Fastlane-Metadaten aktualisieren.
3. Tests und unabhängigen Release-Quellbuild ausführen.
4. Änderungen committen und auf den Release-Branch pushen.
5. Dev-Release und CI vollständig prüfen.
6. Praktische Endabnahme durchführen.
7. Erst danach den annotierten stabilen Tag erstellen und pushen.
8. Signed-Release-Workflow, APK-Signatur und SHA256 prüfen.
9. GitHub-Release abschließen.
10. Anschließend F-Droid- und IzzyOnDroid-Aufnahme beziehungsweise Aktualisierung anstoßen.

Ein veröffentlichter stabiler Tag darf nicht nachträglich verändert werden.
