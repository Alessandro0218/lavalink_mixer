import org.apache.tools.ant.filters.ReplaceTokens
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    `java-library`
}

group = "dev.lavalink.mixer"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.lavalink.dev/releases")
}

dependencies {
    // Provided by the Lavalink server at runtime (transitively includes
    // spring-context/spring-web for @Service/@RestController and
    // kotlinx-serialization-json for filter config parsing).
    compileOnly("dev.arbjerg.lavalink:plugin-api:4.2.2")
    // Must match the Lavaplayer version the server runs.
    compileOnly("dev.arbjerg:lavaplayer:2.2.6")
    // compileOnly is not inherited by tests in Gradle; tests also execute code
    // touching these APIs, so they need runtime presence too.
    testImplementation("dev.arbjerg.lavalink:plugin-api:4.2.2")
    testImplementation("dev.arbjerg:lavaplayer:2.2.6")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.20")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.3")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks {
    named<Test>("test") {
        useJUnitPlatform()
    }

    processResources {
        filter(ReplaceTokens::class, mapOf("tokens" to mapOf("version" to project.version.toString())))
    }
}
