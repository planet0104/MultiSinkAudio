plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.multisinkaudio.alsa"
    compileSdk {
        version = release(36)
    }

    // 跟 app 模块用同一份本地 NDK，避免 AGP 联网下载。
    ndkVersion = "28.0.13004108"

    defaultConfig {
        minSdk = 24

        // 这个 lib 自己只关心 native，不暴露 instrumentation test。
        // 如果以后想跑 androidTest 再加 testInstrumentationRunner。

        // RK3588 / 大部分 Android 平板都是 arm64-v8a。
        // 只打这一个 abi，避免编译时拉满 cpu 还编出用不上的 .so。
        // 如果要发 maven 让别人复用，可以打开下面三行多 abi。
        ndk {
            abiFilters += listOf("arm64-v8a")
            // abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-24"
                )
                cppFlags += listOf("-std=c++17")
                cFlags += listOf("-std=c99")
            }
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    // 接 src/main/cpp/CMakeLists.txt：编出 libmultisinkalsa.so，
    // 自动打进 aar 的 jni 目录，依赖方 System.loadLibrary("multisinkalsa") 即可加载。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // consumerProguardFiles 已经在 defaultConfig 里登记，
            // 这里就不重复加规则了。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // 故意只用 SDK / NDK，不引入任何 androidx，
    // 让这个 lib 可以被任何 minSdk>=24 的工程白嫖。
}
