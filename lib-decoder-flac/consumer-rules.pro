# 原生方法名不能被混淆：JNI 是按 Java_包名_类名_方法名 查找的。
-keepclasseswithmembernames class * {
    native <methods>;
}

# 这些类的成员被 native 层按名字访问。
-keep class androidx.media3.decoder.flac.FlacDecoderJni { *; }
-keep class androidx.media3.extractor.FlacStreamMetadata { *; }
-keep class androidx.media3.extractor.metadata.flac.PictureFrame { *; }

# DefaultRenderersFactory 用 Class.forName("...LibflacAudioRenderer") 反射加载，
# 只在字符串里出现，R8 看不到引用，必须显式保留。
-keep class androidx.media3.decoder.flac.LibflacAudioRenderer { *; }
-keep class androidx.media3.decoder.flac.FlacExtractor { *; }
-keep class androidx.media3.decoder.flac.FlacLibrary { *; }
