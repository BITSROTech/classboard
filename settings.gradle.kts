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
    }
}

rootProject.name = "classboard"
include(":app")
include(":core")
include(":enjine")
include(":features")
include("core:ui-kit")
include("core:net")
include("core:proto")
include("core:sync")
include("engine:drawing")
include("features:board")
include("features:lobby")
include(":overlay")
