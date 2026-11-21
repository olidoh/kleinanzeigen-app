plugins {
    id("com.android.application") version "9.5.1" apply false
    // Kotlin für Android ist seit AGP 9 eingebaut; diese Version gilt auch für die App.
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
