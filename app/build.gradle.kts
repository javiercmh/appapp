plugins {
    alias(libs.plugins.android.application)
}

val releaseStoreFile = providers.gradleProperty("APPAPP_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("APPAPP_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("APPAPP_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("APPAPP_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.wynh.appapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wynh.appapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "1.6.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(checkNotNull(releaseStoreFile))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
}

// Plain assembleRelease stays buildable without credentials (F-Droid builds and signs itself).
// Publishers use assembleSignedRelease, which refuses to run without the release key.
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doFirst {
        check(hasReleaseSigning) {
            "Release signing is not configured. Set APPAPP_RELEASE_STORE_FILE, " +
                "APPAPP_RELEASE_STORE_PASSWORD, APPAPP_RELEASE_KEY_ALIAS, and " +
                "APPAPP_RELEASE_KEY_PASSWORD in your user-level Gradle properties."
        }
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { mustRunAfter(verifyReleaseSigning) }
tasks.register("assembleSignedRelease") {
    group = "build"
    description = "Builds the release APK signed with the publisher key."
    dependsOn(verifyReleaseSigning, "assembleRelease")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
