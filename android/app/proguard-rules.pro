# Names are kept readable: nothing here is secret, and crash reports stay useful.
-dontobfuscate

# Libraries that are called from native code or by name must be kept whole.
-keep class org.signal.** { *; }
-keep class net.zetetic.** { *; }
-keep class org.briarproject.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-keep class com.journeyapps.** { *; }
-keep class com.google.zxing.** { *; }

-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.serialization.**
