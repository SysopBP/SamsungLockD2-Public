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
        versionCode = 16
        versionName = "0.5.9-guardian-unlockfix"
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

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.credentials:credentials:1.7.0-alpha03")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
