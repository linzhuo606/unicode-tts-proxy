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
        versionCode = 11
        versionName = "0.2.1"
    }

    // 正式版签名。密钥不入库，从环境变量读（CI 里来自 GitHub Secrets）；没配时 release 包不签名。
    // 签名必须始终是同一把：换一把，用户就只能卸载重装，自定义词典和引擎选择全丢。
    // 也绝不能把密钥放进仓库——拿到它的人能做出「升级包」替换掉这个读屏引擎，
    // 而它听得到锁屏时输入的每一个字。
    //
    // 本机没设环境变量时，退回到 tools/setup-release-signing.sh 生成的 ~/ttsproxy-signing/：
    // 那把密钥和 GitHub Secrets 里的是同一把。这样本机编出来的测试包（连 debug 包也是）
    // 和 Releases 上的正式版签名一致，装来装去都是覆盖安装，不用卸载重装。
    val homeSigning = File(System.getProperty("user.home"), "ttsproxy-signing")
    val homeKeystore = File(homeSigning, "release.jks").takeIf { it.exists() }
    val homePassword = File(homeSigning, "password.txt").takeIf { it.exists() }?.readText()?.trim()
    val releaseKeystore = System.getenv("TTSPROXY_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
        ?: homeKeystore?.takeIf { homePassword != null }
    val fromEnv = System.getenv("TTSPROXY_KEYSTORE") != null
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = if (fromEnv) System.getenv("TTSPROXY_KEYSTORE_PASSWORD") else homePassword
                keyAlias = if (fromEnv) System.getenv("TTSPROXY_KEY_ALIAS") else "ttsproxy"
                keyPassword = if (fromEnv) System.getenv("TTSPROXY_KEY_PASSWORD") else homePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // 本机测试包也用正式签名，见上
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
