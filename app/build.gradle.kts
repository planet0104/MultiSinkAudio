plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.multisinkaudio"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.multisinkaudio"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["appLabel"] = "MultiSinkAudio"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // tinyalsa 的 native 编译已经搬到 :multisink-alsa 模块。
        // app 这边只需要锁住 ABI（让 :multisink-alsa 的 .so 别带上其它架构）。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // platform key 签名。
    //
    // 现在 app 走 tinyalsa 直写 /dev/snd/pcm*，原则上不再需要 signature|privileged 权限，
    // 一把普通 debug key 也能跑。但保留这份配置主要为了：
    //   - 与之前装在设备上的 APK 保持同签名，setup_device.ps1 覆盖安装不会被签名校验拒绝；
    //   - 某些 ROM 把 audio gid 圈得更紧时，platform 同签名仍是兜底途径。
    // 如果你换设备或者不在乎升级覆盖，把整个 signingConfigs 段删掉，buildTypes 里也别再引用即可。
    signingConfigs {
        create("platform") {
            storeFile = file("${rootDir}/Cx3568.jks")
            storePassword = "Wogemu2022"
            keyAlias = "wogemu"
            keyPassword = "Wogemu2022"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("platform")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("platform")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":multisink-alsa"))

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}