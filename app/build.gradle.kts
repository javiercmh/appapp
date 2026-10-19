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

val devStoreFile = providers.gradleProperty("APPAPP_DEV_STORE_FILE").orNull
val devStorePassword = providers.gradleProperty("APPAPP_DEV_STORE_PASSWORD").orNull
val devKeyAlias = providers.gradleProperty("APPAPP_DEV_KEY_ALIAS").orNull
val devKeyPassword = providers.gradleProperty("APPAPP_DEV_KEY_PASSWORD").orNull
val hasDevSigning = listOf(devStoreFile, devStorePassword, devKeyAlias, devKeyPassword)
    .all { !it.isNullOrBlank() }
val devVersionCode = providers.gradleProperty("APPAPP_DEV_VERSION_CODE").orElse("1").map {
    it.toInt().also { code -> require(code in 1..2100000000) { "Invalid Dev version code" } }
}

android {
    namespace = "com.wynh.appapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wynh.appapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "1.6.1"
        manifestPlaceholders["appLinkScheme"] = "appapp"
        buildConfigField("String", "APP_LINK_SCHEME", "\"appapp\"")

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

    if (hasDevSigning) {
        signingConfigs {
            create("dev") {
                storeFile = file(checkNotNull(devStoreFile))
                storePassword = checkNotNull(devStorePassword)
                keyAlias = checkNotNull(devKeyAlias)
                keyPassword = checkNotNull(devKeyPassword)
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
    // A release-like, independently installed preview. Local previews use debug signing;
    // publishing requires the persistent Dev key through assembleSignedDev.
    buildTypes.create("dev") {
        initWith(buildTypes.getByName("release"))
        applicationIdSuffix = ".dev"
        versionNameSuffix = "-dev.${devVersionCode.get()}"
        manifestPlaceholders["appLinkScheme"] = "appapp-dev"
        buildConfigField("String", "APP_LINK_SCHEME", "\"appapp-dev\"")
        signingConfig = signingConfigs.getByName(if (hasDevSigning) "dev" else "debug")
        matchingFallbacks += listOf("release")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
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

// Never publish a Dev APK signed with a machine's disposable debug key.
val verifyDevSigning = tasks.register("verifyDevSigning") {
    doLast {
        check(hasDevSigning) {
            "Dev publishing requires APPAPP_DEV_STORE_FILE, APPAPP_DEV_STORE_PASSWORD, " +
                "APPAPP_DEV_KEY_ALIAS and APPAPP_DEV_KEY_PASSWORD. See docs/DEV_DISTRIBUTION.md."
        }
    }
}
tasks.matching { it.name == "assembleDev" }.configureEach { mustRunAfter(verifyDevSigning) }
tasks.register("assembleSignedDev") {
    group = "build"
    description = "Builds App² Dev with the persistent Dev publishing key."
    dependsOn(verifyDevSigning, "assembleDev")
}

androidComponents {
    onVariants(selector().withBuildType("dev")) { variant ->
        variant.outputs.forEach { it.versionCode.set(devVersionCode) }
    }
}
