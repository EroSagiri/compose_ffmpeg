import org.jetbrains.compose.compose
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
}

group = "com.example"
version = "1.0-SNAPSHOT"
val platform = getCurrentPlatform()
val arch = getCurrentArch()

println("platform: $platform $arch")

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "11"
        }
        withJava()
    }
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material)

//                implementation("org.bytedeco.javacpp-presets:ffmpeg-platform:4.1-1.4.4")
                implementation("org.bytedeco:ffmpeg:5.1.2-1.5.8")
                runtimeOnly("org.bytedeco:ffmpeg:5.1.2-1.5.8:${platform}-${arch}")
//                implementation("org.bytedeco:ffmpeg-platform-gpl:5.0-1.5.7")
            }
        }
        val jvmTest by getting
    }
}

compose.desktop {
    application {
        mainClass = "Dome"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "helloFfmpeg"
            packageVersion = "1.0.0"
        }
    }
}


fun getCurrentPlatform(): String {
    val os = System.getProperty("os.name").toLowerCase()
    return when {
        os.contains("mac") -> "macosx"
        os.contains("win") -> "windows"
        os.contains("nux") -> "linux"
        else -> os
    }
}

fun getCurrentArch(): String {
    val arch = System.getProperty("os.arch").toLowerCase()
    return when {
        arch.contains("64") -> "x86_64"
        arch.contains("86") -> "x86_32"
        else -> arch
    }
}