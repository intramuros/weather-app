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
}

application {
    mainClass = "io.github.intramuros.weatherbuddy.placeholders.MainKt"
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

tasks.named<JavaExec>("run") {
    // Write straight into the app's assets.
    args(rootProject.layout.projectDirectory.dir("app/src/main/assets/styles").asFile.absolutePath)
}
