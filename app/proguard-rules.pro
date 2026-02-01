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

# Apache POI ProGuard rules
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class schemaorg_apache_xmlbeans.** { *; }
-keep class org.openxmlformats.** { *; }

# Prevent obfuscation of reflection-based calls in POI
-keepattributes Signature
-keepattributes *Annotation*

# Keep specific POI classes that use reflection
-keep class org.apache.poi.ss.usermodel.** { *; }
-keep class org.apache.poi.xssf.usermodel.** { *; }
-keep class org.apache.poi.hssf.usermodel.** { *; }

# ZXing QR Code library
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

# Realm Kotlin database (updated for Realm Kotlin)
-keep class io.realm.kotlin.** { *; }
-keep class com.example.berryharvest.data.model.** { *; }

# Berry Harvest data models
-keep class com.example.berryharvest.data.model.** { *; }

# Prevent crashes on release builds
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn schemaorg_apache_xmlbeans.**
-dontwarn org.openxmlformats.**

# Ignore missing Log4j classes (we excluded them)
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.commons.logging.**

# Keep common compression classes
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**