plugins {
    // 固定在 1.9.23：这正是本机 Gradle 发行版自带的编译器版本，
    // 本地验证与 CI 用同一个编译器，避免「本机过、CI 挂」。
    kotlin("jvm") version "1.9.23"
}

// 这些坐标要和 android 构建里 includeBuild 的依赖替换对上：
//   implementation("com.ttsproxy:ttsproxy-core")
group = "com.ttsproxy"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    // Windows 控制台默认不是 UTF-8，不设的话断言失败信息里的中文会变成乱码
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
