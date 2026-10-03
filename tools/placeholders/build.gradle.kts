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
    // The finished pixel-art scenes are WebP, which ImageIO can't read on its own.
    runtimeOnly(libs.imageio.webp)
}

application {
    mainClass = "io.github.intramuros.weatherbuddy.placeholders.MainKt"
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

tasks.named<JavaExec>("run") {
    // Write straight into the app's assets.
    args(rootProject.layout.projectDirectory.dir("app/src/main/assets/styles").asFile.absolutePath)
}

// Animated GIF previews of the live wallpaper.
tasks.register<JavaExec>("preview") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.github.intramuros.weatherbuddy.placeholders.PreviewKt"
    jvmArgs("-Djava.awt.headless=true")
    args(
        rootProject.layout.projectDirectory.dir("app/src/main/assets/styles").asFile.absolutePath,
        layout.buildDirectory.dir("previews").get().asFile.absolutePath,
    )
}
