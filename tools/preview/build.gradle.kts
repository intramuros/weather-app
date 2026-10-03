import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

dependencies {
    implementation(project(":core"))
    // The pixel-art scenes are WebP, which ImageIO can't read on its own.
    runtimeOnly(libs.imageio.webp)
}

// Animated GIF previews of the live wallpaper.
application {
    mainClass = "io.github.intramuros.weatherbuddy.preview.PreviewKt"
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

tasks.named<JavaExec>("run") {
    args(
        rootProject.layout.projectDirectory.dir("app/src/main/assets").asFile.absolutePath,
        layout.buildDirectory.dir("previews").get().asFile.absolutePath,
    )
}
