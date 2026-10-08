import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    filter.excludeTestsMatching("*.BuienradarForecastLiveTest")
    // StyleTest checks the app's pictures, so a changed picture must re-run it.
    inputs.dir(rootProject.layout.projectDirectory.dir("app/src/main/assets/scenes")).withPropertyName("scenes")
}

tasks.register<Test>("forecastLiveTest") {
    description = "Verify the live Buienradar forecast metadata and PNG contract."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("*.BuienradarForecastLiveTest")
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}
