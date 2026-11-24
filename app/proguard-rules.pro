# Native JNI bindings (LAME, Vosk, JNA)
-keep class com.github.axet.lamejni.** { *; }
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    public *;
}
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
-dontwarn org.vosk.**
