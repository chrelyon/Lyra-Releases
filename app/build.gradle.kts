import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional local-only signing configuration passed as a Gradle property.
// Absolute external paths are supported; never commit passwords or keys.
val signingPropertiesOverride = providers.gradleProperty("lyraSigningPropertiesFile")
    .orNull?.trim()?.takeIf { it.isNotEmpty() }
val keystorePropertiesFile = signingPropertiesOverride?.let { rootProject.file(it) }
    ?: rootProject.file("keystore.properties")

// InnerTubeX e' una dipendenza interna del modulo :innertube. La versione viene
// letta dal build.gradle.kts del modulo e resa disponibile a BuildConfig, cosi'
// la schermata Diagnostica mostra sempre la versione realmente compilata.
val innerTubeBuildFile = rootProject.file("innertube/build.gradle.kts")
val innerTubeXVersion = Regex(
    """com\.github\.MetrolistGroup\.innertubex:innertubex:([^"]+)"""
).find(innerTubeBuildFile.readText())?.groupValues?.get(1)
    ?: error("Impossibile rilevare la versione InnerTubeX da innertube/build.gradle.kts")
val releaseBuildRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}

val unsignedRelease = providers.gradleProperty("lyraUnsignedRelease").orNull == "true"

val keystoreProperties = Properties().apply {
    if (!unsignedRelease && keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    } else if (releaseBuildRequested && !unsignedRelease) {
        error(
            "Missing signing properties file (pass -PlyraSigningPropertiesFile=PATH or create keystore.properties in project root). " +
                "Create it before building a signed Lyra release."
        )
    }
}

fun releaseSigningProperty(name: String): String? {
    if (unsignedRelease) return null
    val value = keystoreProperties.getProperty(name)?.trim().orEmpty()
    if (value.isNotEmpty()) return value
    if (releaseBuildRequested && !unsignedRelease) {
        error("Missing '$name' in keystore.properties")
    }
    return null
}

android {
    namespace = "app.lyra.music"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.lyra.music"

        minSdk = 26
        targetSdk = 36

        versionCode = 59
        versionName = "1.0.21"

        buildConfigField("String", "INNERTUBEX_VERSION", "\"$innerTubeXVersion\"")
    }

    signingConfigs {
        create("release") {
            val configuredStoreFile = releaseSigningProperty("storeFile")
            if (configuredStoreFile != null) {
                val releaseStoreFile = rootProject.file(configuredStoreFile)
                if (releaseBuildRequested && !releaseStoreFile.isFile) {
                    error("Release keystore not found: ${releaseStoreFile.absolutePath}")
                }
                storeFile = releaseStoreFile
            }
            storePassword = releaseSigningProperty("storePassword")
            keyAlias = releaseSigningProperty("keyAlias")
            keyPassword = releaseSigningProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (unsignedRelease) null else signingConfigs.getByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.17.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.9.3")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")

    // Backup cloud affidabile anche oltre la vita del processo.
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // Media3: unico motore audio di Lyra. Tutti i moduli Media3 devono
    // rimanere sulla stessa versione.
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")

    implementation(project(":innertube"))

    // Google Identity Services: sheet di sistema per autorizzare youtube.readonly.
    implementation("com.google.android.gms:play-services-auth:22.0.0")

    // Copertine: Coil carica e mette in cache le thumbnail YouTube in Compose.
    implementation("io.coil-kt.coil3:coil-compose:3.6.2")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
