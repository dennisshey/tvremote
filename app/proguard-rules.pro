# BouncyCastle ships provider classes referenced reflectively; keep them intact.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep the small data models we (de)serialize by field name.
-keep class com.sidephone.atvremote.companion.HapCredentials { *; }
