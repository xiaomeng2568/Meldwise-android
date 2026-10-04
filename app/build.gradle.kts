plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
dependencyLocking { lockAllConfigurations() }
// Package only public legal files, never the repository or local signing configuration.
val prepareLegalAssets by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY_NOTICES.md"))
    into(layout.buildDirectory.dir("generated/legalAssets"))
}
// Optional local signing. CI without these user-level properties builds unsigned Release APKs.
val releaseSigningInputs = listOf(
    "MELDWISE_RELEASE_STORE_FILE",
    "MELDWISE_RELEASE_STORE_PASSWORD",
    "MELDWISE_RELEASE_KEY_ALIAS",
    "MELDWISE_RELEASE_KEY_PASSWORD",
).associateWith { providers.gradleProperty(it) }
val hasReleaseSigning = releaseSigningInputs.values.all { it.isPresent && it.get().isNotBlank() }
android {
    namespace = "io.github.xiaomeng2568.meldwise"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.xiaomeng2568.meldwise"
        minSdk = 26
        targetSdk = 35
        versionCode = 217
        versionName = "0.2.0-alpha.4"
        manifestPlaceholders["appLabel"] = "Meldwise"
        resValue("string", "public_alpha_version", "0.2.0 Alpha 4")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/legalAssets"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        if (hasReleaseSigning) {
            create("meldwiseRelease") {
                storeFile = file(releaseSigningInputs.getValue("MELDWISE_RELEASE_STORE_FILE").get())
                storePassword = releaseSigningInputs.getValue("MELDWISE_RELEASE_STORE_PASSWORD").get()
                keyAlias = releaseSigningInputs.getValue("MELDWISE_RELEASE_KEY_ALIAS").get()
                keyPassword = releaseSigningInputs.getValue("MELDWISE_RELEASE_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        getByName("debug") {
            // Separate engineering installation preserves the accepted Sprint 1 app/data.
            applicationIdSuffix = ".sprint2"
            resValue("string", "sprint2_app_name", "Meldwise · UI Preview")
            manifestPlaceholders["appLabel"] = "@string/sprint2_app_name"
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("meldwiseRelease")
        }
    }
    testOptions { unitTests.all { it.systemProperty("projectRoot", rootDir.absolutePath) } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.named("preBuild").configure { dependsOn(prepareLegalAssets) }

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
    implementation(libs.orcex.android)
    implementation(libs.orcex.font)
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
