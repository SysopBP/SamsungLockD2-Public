plugins {
    id("com.android.application")
}

android {
    namespace = "app.d2lock"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.d2lock"
        minSdk = 31
        targetSdk = 37
        versionCode = 19
        versionName = "0.6.0-guardian-ui9-v19-743diag"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI supplies the original key explicitly; never rely on a runner's default debug key.
    System.getenv("D2_SIGNING_KEYSTORE")?.let { keystorePath ->
        signingConfigs.getByName("debug") {
            storeFile = file(keystorePath)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

configurations.configureEach {
    // SESL uses the AndroidX namespaces intentionally, so the stock equivalents
    // must not coexist in the same APK on this isolated preview branch.
    exclude(group = "androidx.core", module = "core")
    exclude(group = "androidx.core", module = "core-ktx")
    exclude(group = "androidx.customview", module = "customview")
    exclude(group = "androidx.drawerlayout", module = "drawerlayout")
    exclude(group = "androidx.viewpager", module = "viewpager")
    exclude(group = "androidx.appcompat", module = "appcompat")
    exclude(group = "androidx.fragment", module = "fragment")
    exclude(group = "androidx.fragment", module = "fragment-ktx")
}

dependencies {
    // SESL9 preview only. Keep production Guardian/auth surfaces untouched.
    // SESL_GITHUB_TOKEN is supplied by CI/local environment; repository credentials are configured in settings.gradle.kts.
    implementation("sesl.androidx.appcompat:appcompat:1.8.0+1.0.38-sesl9+rev1")
    // Isolated Material 3 comparison preview only; no production Guardian surface uses this yet.
    implementation("com.google.android.material:material:1.13.0")

    // Modern LSPosed/libxposed API is supplied by the framework at runtime.
    compileOnly("io.github.libxposed:api:102.0.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.credentials:credentials:1.7.0-alpha03")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
