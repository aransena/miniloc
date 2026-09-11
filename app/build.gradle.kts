plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFilePath = providers.environmentVariable("MINILOC_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("MINILOC_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MINILOC_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MINILOC_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.miniloc.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.miniloc.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.2.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Verify release signing environment variables are configured."
    doLast {
        if (!hasReleaseSigning) {
            throw GradleException(
                "Release signing is not configured. Set MINILOC_RELEASE_STORE_FILE, " +
                    "MINILOC_RELEASE_STORE_PASSWORD, MINILOC_RELEASE_KEY_ALIAS, and " +
                    "MINILOC_RELEASE_KEY_PASSWORD.",
            )
        }
    }
}

tasks.register("packageSignedRelease") {
    group = "build"
    description = "Build a signed release APK. Requires MINILOC release signing environment variables."
    dependsOn("verifyReleaseSigning", "assembleRelease")
}

afterEvaluate {
    tasks.named("assembleRelease") {
        mustRunAfter("verifyReleaseSigning")
    }
}
