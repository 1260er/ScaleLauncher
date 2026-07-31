# ScaleLauncher 3.1

Separate, privacy-friendly companion app for the Xiaomi Body Composition Scale S400 and openScale.

## Data flow

```text
Xiaomi S400 BLE advertisements
        -> local AES-CCM decryption
        -> strict packet A + packet B validation
        -> automatic openScale user assignment
        -> verified openScale Provider API 2 import
        -> optional Health Connect write for one main user
```

No internet permission and no Xiaomi cloud are used.

## Reliability and all-or-nothing measurements

- The foreground notification and app screen use the same persisted service state.
- A heartbeat detects when the service no longer answers.
- A watchdog restarts a stalled BLE scan automatically.
- Monitoring starts only when battery optimization is disabled, unused-app management is disabled and notifications are available.
- A weighing is accepted only when both encrypted S400 packets are present:
  - packet A: weight and high impedance
  - packet B: low impedance
- Every expected body-composition result must be valid. Approximate or partial results are discarded.
- Repeated tail advertisements are ignored and cannot create a false second measurement.
- openScale Provider API 2 is mandatory. The stored generic value set is read back and checked.
- If openScale contains only a partial value set, ScaleLauncher attempts an immediate rollback by timestamp.
- An incomplete weighing creates a visible, silent notification asking the user to repeat it.

## Multi-user assignment

- All openScale users can be loaded as profiles.
- Each active profile stores birthday, height, sex, reference weight and an allowed weight deviation.
- The initial reference weight is suggested from the newest five openScale measurements.
- A measurement is assigned only when one valid profile is at least 1.0 kg closer than the second-best valid profile.
- Ambiguous or unmatched complete measurements are stored locally and shown through a silent notification.
- Pending measurements can later be assigned to a user or discarded in the app.
- After a successful import, the reference weight is refreshed from the newest five openScale measurements.

## Health Connect

Health Connect can be enabled for exactly one configured main user on the device. The user can select which supported values are written:

- weight
- body fat
- body water mass
- bone mass
- lean body mass
- basal metabolic rate
- weight and height for BMI calculation by compatible apps

Measurements assigned to other openScale users are not written to Health Connect. ScaleLauncher verifies that Health Connect confirms the same number of records it attempted to write.

## Logging

Normal logging contains only important service state, assignment, write success, warnings and errors. Diagnostic logging adds assignment details and calculated body values. The persistent ring log is limited to 150 entries or about 48 KB and automatically removes the oldest entries.

## Build

The included GitHub Actions workflow builds the debug APK with Java 17 and Android SDK 35.
