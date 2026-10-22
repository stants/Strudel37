#!/bin/sh
# Builds Strudel37.apk without the Android SDK, using only JDK 17+ and Python 3.
# Put these Maven Central jars in ./tools: android-all-13-robolectric-9030017.jar (org.robolectric:android-all),
# dalvik-dx-16.0.1.jar (com.jakewharton.android.repackaged:dalvik-dx) and apksig-2.3.0.jar (com.android.tools.build:apksig).
# Usage: KEY_PASS=password sh build.sh   (with your key.p12 in this folder)
set -e
cd "$(dirname "$0")"; T=tools; O=build; rm -rf $O; mkdir -p $O/classes $O/apk/assets $O/apk/res/mipmap
python3 axml.py $O/apk
cp res/*.png $O/apk/res/mipmap/
cp ../HTML/strudel37.html $O/apk/assets/index.html
javac -nowarn --release 8 -cp $T/android-all-13-robolectric-9030017.jar -d $O/classes src/com/wil/strudel37/*.java
java -cp $T/dalvik-dx-16.0.1.jar com.android.dx.command.Main --dex --min-sdk-version=24 --output=$O/apk/classes.dex $O/classes
python3 zipalign.py $O/unsigned.apk $O/apk
javac -nowarn -cp $T/apksig-2.3.0.jar -d $O signer/Sign.java
java --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED --add-exports java.base/sun.security.util=ALL-UNNAMED -cp $O:$T/apksig-2.3.0.jar Sign key.p12 "${KEY_PASS:?set KEY_PASS to the keystore password}" $O/unsigned.apk ../Android/Strudel37.apk
