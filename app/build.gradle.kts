plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Fester Signierschlüssel aus der CI (GitHub-Secret), damit neue Versionen sich drüber installieren lassen.
// Ohne Secret wird wie bisher mit dem zufälligen Debug-Schlüssel des Build-Rechners signiert.
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotBlank() }
val sharedKeystore = System.getenv("SIGNING_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.isFile && signingPassword != null }
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// eBay-Anbindung: nicht geheime Werte aus GitHub-Variablen (oder local gradle.properties). Das Secret liegt
// nur im Token-Dienst, nie in der App. Siehe docs/eBay-Einrichtung.md.
fun ebayValue(name: String): String =
    (System.getenv(name) ?: providers.gradleProperty(name).orNull).orEmpty().trim()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "de.olidoh.inserathelfer"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.olidoh.inserathelfer"
        // Android 10+: Fotos lassen sich ohne Speicher-Berechtigung in die Galerie legen.
        minSdk = 29
        targetSdk = 36
        // Jeder CI-Lauf zählt hoch, damit Android die neue APK als Update annimmt.
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"

        buildConfigField("String", "EBAY_CLIENT_ID", quoted(ebayValue("EBAY_CLIENT_ID")))
        buildConfigField("String", "EBAY_RUNAME", quoted(ebayValue("EBAY_RUNAME")))
        buildConfigField("String", "EBAY_TOKEN_SERVICE_URL", quoted(ebayValue("EBAY_TOKEN_SERVICE_URL")))
        buildConfigField("boolean", "EBAY_SANDBOX", (ebayValue("EBAY_SANDBOX") == "true").toString())
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
            // Das Anthropic-SDK nutzt Reflection (Jackson); Minify erst mit passenden Regeln aktivieren.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

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
