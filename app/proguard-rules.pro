# Keep JNI bridge methods referenced from native code.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class dev.nyandroid.terminal.backend.Pty { *; }
