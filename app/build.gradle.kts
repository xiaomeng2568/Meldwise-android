plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
dependencyLocking { lockAllConfigurations() }
android {
    namespace = "io.github.xiaomeng2568.meldwise"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.xiaomeng2568.meldwise"
        minSdk = 26
        targetSdk = 35
        versionCode = 200
        versionName = "0.3-p2-sprint2-deepseek"
        manifestPlaceholders["appLabel"] = "Meldwise"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        getByName("debug") {
            // Separate engineering installation preserves the accepted Sprint 1 app/data.
            applicationIdSuffix = ".sprint2"
            resValue("string", "sprint2_app_name", "Meldwise · Sprint 2")
            manifestPlaceholders["appLabel"] = "@string/sprint2_app_name"
        }
        getByName("release") { isMinifyEnabled = false }
    }
    testOptions { unitTests.all { it.systemProperty("projectRoot", rootDir.absolutePath) } }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jose4j)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.android.junit)
    androidTestImplementation(libs.android.runner)
    androidTestImplementation(libs.jsr305)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    debugImplementation(libs.compose.test.manifest)
}
