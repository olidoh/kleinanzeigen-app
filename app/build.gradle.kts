plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Fester Signierschlüssel aus der CI (GitHub-Secret), damit neue Versionen sich drüber installieren lassen.
// Ohne Secret wird wie bisher mit dem zufälligen Debug-Schlüssel des Build-Rechners signiert.
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotBlank() }
val sharedKeystore = System.getenv("SIGNING_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.isFile && signingPassword != null }
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// Release-Builds (Intune) brauchen zwingend den festen Schlüssel, sonst lässt sich die App nie wieder aktualisieren.
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
if (releaseRequested && sharedKeystore == null) {
    throw GradleException("Release-Build ohne Signierschlüssel: SIGNING_KEYSTORE_PATH und SIGNING_PASSWORD setzen.")
}

android {
    // Interner Code-Namensraum (nicht sichtbar), bleibt aus der Zeit als „Inserat-Helfer“.
    namespace = "de.olidoh.inserathelfer"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.olidoh.verkora"
        // Android 10+: Fotos lassen sich ohne Speicher-Berechtigung in die Galerie legen.
        minSdk = 29
        targetSdk = 36
        // Version der Veröffentlichung (Intune / Managed Google Play). Nur über tools/version_erhoehen.py ändern;
        // Google Play lehnt gleiche oder kleinere Nummern ab. Debug-Builds zählen stattdessen pro CI-Lauf hoch (unten).
        versionCode = 100
        versionName = "1.0.0"
    }

    // Vertriebswege: "intern" für die private App über Intune (Managed Google Play),
    // "store" für die bisherigen Test-APKs und eine spätere öffentliche Veröffentlichung.
    flavorDimensions += "vertrieb"
    productFlavors {
        create("intern") {
            dimension = "vertrieb"
            applicationIdSuffix = ".intern"
        }
        create("store") {
            dimension = "vertrieb"
        }
    }

    signingConfigs {
        if (sharedKeystore != null) {
            create("shared") {
                storeFile = sharedKeystore
                storePassword = signingPassword
                keyAlias = "inserathelfer"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        debug {
            if (sharedKeystore != null) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            if (sharedKeystore != null) signingConfig = signingConfigs.getByName("shared")
            // Das Anthropic-SDK nutzt Reflection (Jackson); Minify erst mit passenden Regeln aktivieren.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

// Test-APKs aus der CI zählen pro Lauf hoch, damit sie sich drüber installieren lassen.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach {
            it.versionCode.set(buildNumber)
            it.versionName.set("0.1.$buildNumber")
        }
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.webkit:webkit:1.12.1")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("io.coil-kt:coil-compose:2.7.0")
}
