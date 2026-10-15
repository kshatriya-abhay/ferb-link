plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.abhay.ferblink"
    // As Ferb: latest AndroidX needs API 37 to compile; min 33 and target 36 don't change.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.abhay.ferblink"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material.components)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}

// The laptop harness (LaptopHarness, `mise run companion:harness`) is a model evaluation, not a
// test: it runs only with -Pharness.in=… (and friends) and is left out of other test runs.
// -Pcontract.write=true rewrites the contract fixtures (`mise run companion:contract-write`).
tasks.withType<Test>().configureEach {
    listOf("harness.in", "harness.out", "harness.url", "harness.key", "harness.model", "harness.limit", "harness.dry", "harness.jev-key", "harness.jev-url", "harness.jev-ledger", "contract.write").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    systemProperty("contract.dir", rootProject.file("../contract/v1").path)
    if (!providers.gradleProperty("harness.in").isPresent) filter.excludeTestsMatching("dev.abhay.ferblink.LaptopHarness")
}
