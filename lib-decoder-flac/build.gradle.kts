plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "androidx.media3.decoder.flac"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DWITH_OGG=OFF",
                    "-DBUILD_CXXLIBS=OFF",
                    "-DBUILD_PROGRAMS=OFF",
                    "-DBUILD_EXAMPLES=OFF",
                    "-DBUILD_TESTING=OFF",
                    "-DBUILD_DOCS=OFF",
                    "-DINSTALL_MANPAGES=OFF",
                    "-DINSTALL_PKGCONFIG_MODULES=OFF",
                    "-DINSTALL_CMAKE_CONFIG_MODULE=OFF",
                )
                targets += "flacJNI"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.decoder)
    implementation(libs.androidx.annotation)
    // Media3 的 Java 源码里只有 @Nullable 之类的编译期注解
    compileOnly(libs.checker.qual)
}
