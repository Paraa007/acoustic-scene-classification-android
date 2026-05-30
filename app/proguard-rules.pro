# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# PyTorch Mobile — Reflection-basiertes Modell-Loading
-keep class org.pytorch.** { *; }
-keep class com.facebook.jni.** { *; }
-dontwarn org.pytorch.**
-dontwarn com.facebook.jni.**

# Gson — TypeToken + Reflection
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements com.google.gson.JsonSerializer
-keep public class * implements com.google.gson.JsonDeserializer

# Eigene Modell-Klassen, die per Gson (de-)serialisiert werden — Felder beibehalten
-keep class com.fzi.acousticscene.model.** { *; }
-keep class com.fzi.acousticscene.data.** { *; }

# TarsosDSP
-keep class be.tarsos.dsp.** { *; }
-dontwarn be.tarsos.dsp.**

# Coroutines (defensive)
-dontwarn kotlinx.coroutines.**