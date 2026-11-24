pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only used for NewPipeExtractor (GPL-3.0), which is published exclusively on JitPack.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.TeamNewPipe.*") }
        }
    }
}

rootProject.name = "MusicFlow"

include(":app")
include(":core:model")
include(":core:common")
include(":core:network")
include(":core:database")
include(":core:designsystem")
include(":data:providers")
include(":data:repositories")
include(":playback")
include(":feature:home")
include(":feature:explore")
include(":feature:search")
include(":feature:library")
include(":feature:player")
include(":feature:settings")
