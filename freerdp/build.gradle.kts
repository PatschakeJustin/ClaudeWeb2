// FreeRDP's Android core (Java/JNI, MPL 2.0 / Apache 2.0) – embedded RDP client.
// The native libraries (libfreerdp3, libwinpr3, OpenSSL) are built by scripts/build-freerdp-native.sh
// into src/main/jniLibs. Without them the module still builds; the app then reports RDP as unavailable.
plugins {
    id("com.android.library")
}

val jniLibsDir = file("src/main/jniLibs")
val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").filter { File(jniLibsDir, "$it/libfreerdp3.so").exists() }

android {
    namespace = "com.freerdp.freerdpcore"
    compileSdk = 36
    System.getenv("ANDROID_NDK_LATEST_HOME")?.let { ndkPath = it; ndkVersion = File(it).name }

    defaultConfig {
        minSdk = 24
        if (abis.isNotEmpty()) {
            ndk { abiFilters += abis }
            externalNativeBuild { cmake { arguments += "-DWITH_CLIENT_CHANNELS=ON" } }
        }
    }
    if (abis.isNotEmpty()) {
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging {
        jniLibs {
            pickFirsts += listOf("libfreerdp3.so", "libfreerdp-client3.so", "libwinpr3.so").flatMap { lib ->
                listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").map { "lib/$it/$lib" }
            }
        }
    }
    // The prebuilt include/, cmake/ and static files next to the .so files must not end up in the APK.
    sourceSets["main"].jniLibs.setSrcDirs(listOf("build/jniLibs-clean"))
}

// Copy only the shared libraries into the directory the APK takes its native libraries from.
val cleanJniLibs by tasks.registering(Copy::class) {
    from(jniLibsDir) { include("*/*.so") }
    into(layout.buildDirectory.dir("jniLibs-clean"))
}
tasks.named("preBuild") { dependsOn(cleanJniLibs) }

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.collection:collection:1.4.0")
}
