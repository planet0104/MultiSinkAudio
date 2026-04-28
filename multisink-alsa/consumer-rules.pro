# 让 R8 / ProGuard 不要把 native 调用入口和被 JNI 反查的类删掉。
# TinyAlsaPlayer 的 native 方法会被 JNI 直接按符号名调用；
# 如果有人在 release flavor 里开 minify，要保住这些。
-keep class com.example.multisinkaudio.alsa.TinyAlsaPlayer { *; }
-keep class com.example.multisinkaudio.alsa.TinyAlsaPlayer$Card { *; }
-keepclasseswithmembernames class com.example.multisinkaudio.alsa.** {
    native <methods>;
}
