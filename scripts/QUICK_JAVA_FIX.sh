#!/bin/bash
# Schnelle Lösung: Android Studio JDK verwenden

# Setze JAVA_HOME auf Android Studio's JDK
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# Prüfe Java
echo "Java Version:"
java -version

echo ""
echo "✅ Java ist jetzt konfiguriert!"
echo ""
echo "Jetzt kannst du Gradle ausführen:"
echo "./gradlew clean assembleDebug installDebug"
echo ""
