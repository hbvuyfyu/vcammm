-keep class com.vcam.** { *; }
-keep class com.topjohnwu.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-dontwarn com.topjohnwu.**
