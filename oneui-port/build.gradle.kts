plugins {
 id("com.android.library")
 id("org.jetbrains.kotlin.plugin.serialization")
}
android {
 namespace = "io.github.soclear.oneuix.common"
 compileSdk = 37
 defaultConfig {
  minSdk = 31
  buildConfigField("String", "MODULE_APPLICATION_ID", "\"app.d2lock\"")
 }
 buildFeatures { buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { freeCompilerArgs.add("-Xcontext-parameters") } }
dependencies {
 compileOnly("io.github.libxposed:api:102.0.0")
 compileOnly(project(":oneui-stub"))
 implementation("org.luckypray:dexkit:2.2.0")
 implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
