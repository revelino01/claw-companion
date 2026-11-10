# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes Signature

# Keep Gson serialization models and their fields intact
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class ai.openclaw.companion.model.** { *; }
-keepclassmembers class ai.openclaw.companion.model.** {
    <fields>;
    <methods>;
}

# Keep services and server classes
-keep class ai.openclaw.companion.service.** { *; }
-keep class ai.openclaw.companion.server.** { *; }

-dontwarn fi.iki.elonen.**
