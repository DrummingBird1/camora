# UVC library: native code calls back into these classes by name.
-keep class com.serenegiant.** { *; }
-keep class com.herohan.uvcapp.** { *; }
-keepclassmembers class * { native <methods>; }

# NanoHTTPD looks up some classes reflectively.
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
