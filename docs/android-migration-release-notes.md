
## Android downloads are changing

You can continue installing SonarBridge manually from GitHub. Future GitHub
APKs, named `sonarbridge-X.Y.Z-play.apk`, are the same app offered by
[Google Play](https://play.google.com/store/apps/details?id=ca.dynamicsolutions.sonarbridge).
[Download the Play-signed APK from GitHub](https://github.com/RyanEwen/sonarphone-nmea-bridge/releases/download/v0.2.5/sonarbridge-0.2.5-play.apk).
The new app does not download APK updates itself. Download future APKs from
GitHub manually, or use Google Play for updates where available.

If you already use an older GitHub APK, first install the
[final legacy migration update (v0.2.4)](https://github.com/RyanEwen/sonarphone-nmea-bridge/releases/tag/v0.2.4)
when offered. It preserves your settings and explains the
switch. Wait until a `-play.apk` is available before uninstalling.

For the one-time move: record your Wi-Fi name/password, units, sonar controls,
calibration offsets and alarm settings, and save any raw frame logs you need.
Disconnect the bridge, uninstall the old app, install the new app from GitHub
or Google Play, then re-enter your settings and grant permissions. Android
requires this because the signing keys differ. Uninstalling clears local
settings and app files; they do not transfer automatically.

After reconnecting, check the sonar readings and Navionics feed before going
out on the water. Existing Play installations update normally.
