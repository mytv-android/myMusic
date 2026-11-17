plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mytv.mymusic.lxruntime"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake { targets += "lx-runtime" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
