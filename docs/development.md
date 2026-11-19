# Development

Building, testing and the unit tests are in [companion/README.md](../companion/README.md), Testing. This page covers the test emulator and releases.

## The test emulator

For trying the app when the phone isn't on adb, and for `rc:check`: the official [Android Emulator](https://developer.android.com/studio/run/emulator), headless.

- `mise run emu:start` boots it (AVD `ferb-test`, Pixel 7 sized, Android 16 / API 36 Google APIs x86_64; created on first use) and prints its serial, `emulator-5554`. With it already running it only prints the serial. `mise run emu:stop` stops it. Ferb's repo has the same two tasks for the same emulator.
- Always `adb -s emulator-5554`, never `adb` alone, so the phone and the emulator never mix.
- One model or emulator at a time: it holds Ferb's bench lock while it runs (the bench refuses, and it refuses while the bench runs), and it won't start while the model server has a model loaded (`mise run server:unload` first). A request from the phone can still load the model while it runs.
- Its data stays between starts (it never saves a snapshot, so each start is a cold boot); `rc:check` removes both apps first.
- Once: `sdkmanager --install emulator "system-images;android-36;google_apis;x86_64"`, and `/dev/kvm` usable by you.

What it can't do, so the phone stays the final check: AICore and Gemini Nano; the real assist key (the power button); the microphone (no recording, so no voice); Tailscale, so it can't reach your computer's server (Ferb Link shows "can't reach"); and real-device speed. arm64-only libraries run through the image's ARM translation, slower than on the phone.

## Releasing

1. Write the version's section in `CHANGELOG.md` (`## <version>`), and set `versionName` and `versionCode` in `companion/app/build.gradle.kts`. Commit.
2. `mise run companion:release` builds and signs `dist/ferb-link-<version>.apk` from that commit (you run it: it needs the release key).
3. `mise run rc:check` checks that exact APK on an emulator and writes `dist/ferb-link-<version>.rc`. It runs three passes on the test emulator (above; never a phone), each starting with neither app installed and needing an empty crash buffer:
   - **upgrade:** the last published release, paired and with Jev's key and limit put in through its own screens, then this APK installed over it and cold-started into each screen;
   - **fresh:** this APK alone, cold-started, paired and given Jev's key and limit, then cold-started again into each screen;
   - **cross:** Ferb's last published release beside it.
   It starts the emulator if it isn't running (and stops it after), and refuses while Ferb's own `rc:check` runs.
4. The checklist below, on the phone, then `mise run rc:check phone` to record your answer.
5. `mise run companion:publish`. It refuses unless the record is for this APK's sha256 and commit, and says pass for all four.

A new build (a fix after a failed check) is a new APK: it gets a new sha256, and the checks start again from step 3.

## Release candidate on the phone

With the phone's installed Ferb Link release and its pairing as they are. Ferb's version of this list is in Ferb's `docs/development.md`.

1. Install the candidate over it: `adb install -r dist/ferb-link-<version>.apk`. Never uninstall first: that deletes the pairing and keys.
2. Open Ferb Link. It shows your computer as connected (or why not), and doesn't close.
3. Tap **Check**: the status matches the server (`mise run server:status`).
4. In Ferb, make one request that goes through Ferb Link (a rewrite or Jev, as set up), and see it answered.
5. Force-stop Ferb Link (Settings → Apps → Ferb Link → Force stop) and open it again: it opens.
6. `adb logcat -b crash -d` has nothing from `dev.abhay.ferblink`.

All six pass: answer `pass`. Otherwise `fail` and what went wrong, and fix it before publishing.
