val githubPackagesUser: String? = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
val githubPackagesToken: String? = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        maven(url = "https://artifact.bytedance.com/repository/pangle/")
        maven(url = "https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea")
        maven(url = "https://maven.pkg.github.com/dbv0610/AdsApplication") {
            credentials {
                username = githubPackagesUser
                password = githubPackagesToken
            }
        }
    }
}


rootProject.name = "AdsApplication"
include(":app")
include(":adlib")
include(":gma-lib")
