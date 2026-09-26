pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        val seslUser = providers.gradleProperty("sesl.github.user").orNull ?: System.getenv("SESL_GITHUB_USER")
        val seslToken = providers.gradleProperty("sesl.github.token").orNull ?: System.getenv("SESL_GITHUB_TOKEN")
        if (!seslUser.isNullOrBlank() && !seslToken.isNullOrBlank()) {
            maven {
                name = "SESLAndroidX"
                url = uri("https://maven.pkg.github.com/tribalfs/sesl-androidx")
                credentials {
                    username = seslUser
                    password = seslToken
                }
            }
            maven {
                name = "SESLMaterial"
                url = uri("https://maven.pkg.github.com/tribalfs/sesl-material-components-android")
                credentials {
                    username = seslUser
                    password = seslToken
                }
            }
        }
    }
}
rootProject.name = "SamsungLock37"
include(":app")
