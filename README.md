# HC-05 Car Controller v3

A smoother Android controller for an Arduino car using an HC-05 Bluetooth module.

## Big fixes in v3

- Lets you choose any paired Bluetooth device instead of requiring the name to be exactly HC-05.
- Tries three connection methods:
  1. Standard SPP RFCOMM
  2. Insecure SPP RFCOMM
  3. Legacy RFCOMM channel 1 fallback
- Better Android 12+ Bluetooth permission handling.
- Shows the real paired device name + MAC address.
- Large controller layout for touch control.
- Hold direction = move, release = automatic STOP.
- Manual and Obstacle mode buttons.
- Better connection/error messages.
- Haptic feedback on directional controls.

## Arduino commands

- F = Forward
- B = Backward
- L = Left
- R = Right
- S = Stop
- A = Obstacle Avoider mode
- M = Manual Bluetooth mode

Your Arduino sketch must use the same characters.

## Before connecting

1. Pair HC-05 in Android Bluetooth settings first.
2. Typical HC-05 pairing PIN is 1234 (some modules use 0000).
3. Open this app.
4. Select HC-05 from the paired-device dropdown.
5. Tap CONNECT.

## Build on GitHub

The included `.github/workflows/build-apk.yml` builds the debug APK automatically.
After a successful GitHub Actions run, download the `HC05-Car-Controller-v3-APK` artifact.
