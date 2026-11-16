plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("com.anthropic:anthropic-java:3.8.0")
    api("com.hierynomus:sshj:0.41.2")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.apache.sshd:sshd-core:2.15.0")
    testImplementation("org.apache.sshd:sshd-sftp:2.15.0")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

tasks.test {
    useJUnitPlatform()
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
