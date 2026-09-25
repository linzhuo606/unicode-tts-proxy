pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ttsproxy-android"
include(":app")

// 复合构建：core 是一个独立的纯 Kotlin 构建，本机没有 Android SDK 也能单独跑它的测试。
// Gradle 会把 com.ttsproxy:ttsproxy-core 自动替换成这个构建的产物。
includeBuild("../core")
