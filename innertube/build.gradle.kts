plugins {
    id("com.android.library")    
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.metrolist.innertube"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api("com.github.MetrolistGroup.innertubex:innertubex:0.6.0")

    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("io.ktor:ktor-client-encoding:3.5.2")

    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation("junit:junit:4.13.2")

    coreLibraryDesugaring(
        "com.android.tools:desugar_jdk_libs_nio:2.1.5"
    )
}