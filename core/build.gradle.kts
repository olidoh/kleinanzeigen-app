import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Offizielles Anthropic-SDK (Java/Kotlin) für die Claude API
    api("com.anthropic:anthropic-java:3.3.0")
    // HTTP für OpenAI-kompatible Anbieter (Mistral, Langdock, OpenAI …)
    // api: Die App ruft die eBay-Klassen direkt auf, deren Konstruktoren OkHttpClient erwähnen.
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
