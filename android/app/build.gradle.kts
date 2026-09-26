plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ttsproxy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ttsproxy"
        // 链路 C 依赖 UtteranceProgressListener.onAudioAvailable / onBeginSynthesis，
        // 这两个回调是 API 24 起才有的。
        minSdk = 24
        // 本机 SDK 目前只装了 android-34。TTS 引擎用不到 Android 15 的新行为，
        // 要升到 35 只需 sdkmanager "platforms;android-35" 后改这两个数字。
        targetSdk = 34
        versionCode = 9
        versionName = "0.1.8"
    }

    // 正式版签名。密钥不入库，从环境变量读（CI 里来自 GitHub Secrets）；没配时 release 包不签名。
    // 签名必须始终是同一把：换一把，用户就只能卸载重装，自定义词典和引擎选择全丢。
    // 也绝不能把密钥放进仓库——拿到它的人能做出「升级包」替换掉这个读屏引擎，
    // 而它听得到锁屏时输入的每一个字。
    val releaseKeystore = System.getenv("TTSPROXY_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("TTSPROXY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TTSPROXY_KEY_ALIAS")
                keyPassword = System.getenv("TTSPROXY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("com.ttsproxy:ttsproxy-core")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
}
