plugins {
    alias(libs.plugins.android.library)
}

// The subapp runtime shared by App² and standalone apps built from one subapp: workspace storage,
// the JavaScript bridge, WebView setup, host channels, AI, notifications, grants and updates.
// The embedding app supplies its screens and identity through com.wynh.appapp.host.RuntimeHost.
android {
    namespace = "com.wynh.appapp.runtime"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Same versions as :app, so Material's older transitive versions are never resolved.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime)

    testImplementation(libs.junit)
}
