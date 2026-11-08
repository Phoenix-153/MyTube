pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // GeckoView wird von Mozilla in einem eigenen Maven-Repository veröffentlicht
        maven("https://maven.mozilla.org/maven2/")
    }
}

rootProject.name = "MyTube"
include(":app")
