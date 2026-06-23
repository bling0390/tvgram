# TdApi uses native methods; keep the JNI bridges intact under R8.
-keepclasseswithmembernames class org.drinkless.td.libcore.telegram.NativeClient {
    native <methods>;
}
-keep class org.drinkless.td.libcore.telegram.TdApi$* { *; }
-keep class org.drinkless.td.libcore.telegram.TdApi { *; }