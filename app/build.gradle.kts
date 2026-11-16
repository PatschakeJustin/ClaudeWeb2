plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.devdeck"
    compileSdk = 36

    // Every CI build gets a higher version so the in-app updater can detect it.
    val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

    defaultConfig {
        applicationId = "app.devdeck"
        minSdk = 24 // Android 7.0
        targetSdk = 36
        versionCode = 100 + buildNumber
        versionName = "0.5.$buildNumber"
    }

    // One fixed key for all builds; otherwise Android refuses to install updates over the old version.
    // Override with the environment variables (e.g. from GitHub secrets) to use a private key.
    signingConfigs {
        create("humantize") {
            storeFile = file(System.getenv("HUMANTIZE_KEYSTORE") ?: "signing/humantize-release.jks")
            storePassword = System.getenv("HUMANTIZE_KEYSTORE_PASSWORD") ?: "humantize-devapp"
            keyAlias = System.getenv("HUMANTIZE_KEY_ALIAS") ?: "humantize"
            keyPassword = System.getenv("HUMANTIZE_KEY_PASSWORD") ?: "humantize-devapp"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("humantize")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("humantize")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Back-ports java.time, java.nio.file etc. used by the SSH and Claude libraries to Android 7.x.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/io.netty.versions.properties",
            )
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The Humantize Hub (hub/*.js) ships inside the app so the server wizard can install it.
val copyHub by tasks.registering(Copy::class) {
    from(rootProject.file("hub")) { include("server.js", "bot.js", "package.json") }
    into(layout.buildDirectory.dir("generated/hubassets/hub"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/hubassets").get().asFile)
tasks.named("preBuild") { dependsOn(copyHub) }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}

// Jackson 2.15+ checks `instanceof java.lang.BootstrapMethodError` while starting up; that class only exists
// from Android 8.0 on, so the app crashed immediately on Android 7. 2.14.3 is the newest version without that
// check and is supported by the Anthropic SDK (minimum 2.13.4).
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("com.fasterxml.jackson")) {
            useVersion("2.14.3")
            because("Android 7 compatibility (no java.lang.BootstrapMethodError)")
        }
    }
}
