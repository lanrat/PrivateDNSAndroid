[![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/karasevm/PrivateDNSAndroid/total)](https://github.com/karasevm/PrivateDNSAndroid/releases/latest)
[![GitHub Release](https://img.shields.io/github/v/release/karasevm/PrivateDNSAndroid)](https://github.com/karasevm/PrivateDNSAndroid/releases/latest)
[![IzzyOnDroid](https://img.shields.io/endpoint?url=https://apt.izzysoft.de/fdroid/api/v1/shield/ru.karasevm.privatednstoggle&label=IzzyOnDroid)](https://apt.izzysoft.de/fdroid/index/apk/ru.karasevm.privatednstoggle)
[![Translation status](https://hosted.weblate.org/widget/privatednsandroid/private-dns-quick-toggle/svg-badge.svg)](https://hosted.weblate.org/engage/privatednsandroid/)

# Private DNS Quick Toggle
A quick settings tile to switch your private dns provider. Supports any number of providers. Makes it easy to turn adblocking dns servers on or off with just
a single tap.

![Private DNS app screenshot](readme.jpg)

## Installation

### IzzyOnDroid (Recommended, will enable auto-updates on Android 12+)

1. Install an F-droid client such as [Droidify](https://droidify.eu.org/download) or the [official F-Droid client](https://f-droid.org/)
2. Verify that [IzzyOnDroid repo](https://apt.izzysoft.de/fdroid/repo?fingerprint=3BF0D6ABFEAE2F401707B6D966BE743BF0EEE49C2561B9BA39073711F628937A) is added and enabled
3. Search for the full app name "Private DNS Quick Toggle" and install it

### GitHub Releases
Get the latest apk on the [releases page](https://github.com/karasevm/PrivateDNSAndroid/releases/latest) 

## Activation
To change the system DNS options the app requires `android.permission.WRITE_SECURE_SETTINGS` permission. There are multiple ways to provide it.

### Automatic (Shizuku)
1. Install and start [Shizuku](https://shizuku.rikka.app/).
2. Start the app and allow Shizuku access when prompted.

### Manual
For the app to work properly you'll need to provide it permissions via ADB:

1. Get to your PC and download platform tools from google [here](https://developer.android.com/studio/releases/platform-tools).
2. Extract the tools, and open terminal in the same directory ([Windows guide](https://youtu.be/6vVFmOcIADg?t=38), [macos guide](https://www.howtogeek.com/210147/how-to-open-terminal-in-the-current-os-x-finder-location/)).
3. Turn on USB Debugging on your phone (This may require different steps, for Xiaomi you also have to enable `USB Debugging (Security settings)`, but generally [this video guide](https://youtu.be/Ucs34BkfPB0?t=29) should work on most phones)
4. Connect your phone to your PC
5. Run this command in the terminal

```
./adb shell pm grant ru.karasevm.privatednstoggle android.permission.WRITE_SECURE_SETTINGS
```

6. That's it, you should have the app installed.

## Switching back automatically
Turn on **"Turn Private DNS back on automatically"** in the app's Options to have Private DNS switch back on a set number of minutes (5 by default) after you turn it off or to auto, e.g. to briefly get past an ad-blocking DNS. The tile shows when it will switch back (Android 10+). It is started by the tile, the server picker and the app's shortcuts, but not by other apps, so automations that turn Private DNS off are not undone. Turning Private DNS back on cancels it. Android may run it a few minutes late, in which case it happens as soon as you open the quick settings or the app.

## Shortcuts and automation
Long-press the app icon (or add a shortcut from your launcher's widget/shortcut list) to get these actions:

| Shortcut | Effect |
|---|---|
| Next server/mode | Same as tapping the tile |
| Switch mode | Switches between Private DNS and off/auto, keeping the current server |
| Turn on | Turns Private DNS on, with the current server if it is saved and enabled, otherwise the first enabled server |
| Turn off | Turns Private DNS off |

Other apps, such as Tasker or MacroDroid, can trigger the same actions **only after you enable "Allow other apps to change Private DNS" in the app's Options**. This is off by default so that no other installed app can silently turn off your Private DNS. Once enabled, start this activity:

- Package: `ru.karasevm.privatednstoggle`
- Class: `ru.karasevm.privatednstoggle.ExternalShortcutHandlerActivity`
- Action: `ru.karasevm.privatednstoggle.action.SHORTCUT`
- Data: `privatednstoggle://do_cycle`, `privatednstoggle://switch_mode`, `privatednstoggle://turn_on` or `privatednstoggle://turn_off`

Debug builds use the package `ru.karasevm.privatednstoggle.dev` (the class name stays the same). For example, with adb:
```
./adb shell am start -n ru.karasevm.privatednstoggle/.ExternalShortcutHandlerActivity -a ru.karasevm.privatednstoggle.action.SHORTCUT -d privatednstoggle://turn_off
```

## Contributing

### Translation
The easiest way to contribute would be to submit a translation to your language. Thanks to Weblate gratis hosting for open-source projects you can do it without any programming knowledge on [their website](https://hosted.weblate.org/engage/privatednsandroid/).
#### Translation status
<a href="https://hosted.weblate.org/engage/privatednsandroid/">
<img src="https://hosted.weblate.org/widget/privatednsandroid/private-dns-quick-toggle/multi-auto.svg" alt="Translation status" />
</a>

### Code
If you want to contribute code please try to adhere to the following guidelines:
- Include javadoc comments for all the public methods you add
- Keep the code neatly formatted, you can you the built-in Android Studio formatter
- Please describe what your code does and how does it do that when sending a PR
- Before sending a PR please test your change on the oldest and latest supported Android versions (9 and 17 at the time of writing)
- Run `./gradlew assembleDebug testDebugUnitTest lintDebug` before sending a PR, CI runs the same checks
