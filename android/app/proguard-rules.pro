# Minification is disabled on purpose: the app is small, and keeping R8 off
# removes any chance of reflection-based breakage on niche devices.
-keep class com.moboserver.app.** { *; }
