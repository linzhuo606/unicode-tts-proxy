plugins {
    kotlin("jvm") version "1.9.23"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.ttsproxy.tools.GenerateDictKt")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
