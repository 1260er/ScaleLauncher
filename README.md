# ScaleLauncher 3.0

Separate, privacy-friendly companion app for the Xiaomi Body Composition Scale S400 and openScale.

## Data flow

```text
Xiaomi S400 BLE advertisements
        -> local AES-CCM decryption
        -> automatic openScale user assignment
        -> complete openScale Provider API 2 import
        -> optional Health Connect write for one main user
```

No internet permission and no Xiaomi cloud are used.

## Multi-user assignment

- All openScale users can be loaded as profiles.
- Each active profile stores birthday, height, sex, reference weight and an allowed weight deviation.
- The initial reference weight is suggested from the newest five openScale measurements.
- A measurement is assigned only when one valid profile is at least 1.0 kg closer than the second-best valid profile.
- Ambiguous or unmatched measurements are stored locally and shown through a silent notification.
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

Measurements assigned to other openScale users are not written to Health Connect.

## Logging

Normal logging contains only important service state, assignment, write success, warnings and errors. Diagnostic logging adds raw assignment differences and calculated body values. The log is bounded and automatically removes old entries.

## Build

The included GitHub Actions workflow builds the debug APK with Java 17 and Android SDK 35.
