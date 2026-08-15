-keep class org.linphone.** { *; }
-keep class org.linphone.core.tools.** { *; }
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
-dontwarn org.linphone.**
