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
        // Tesseract OCR for scripts ML Kit can't read (Thai, Cyrillic, Arabic, …)
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.adaptech-cz.*") } }
    }
}
rootProject.name = "HomeTranslate"
include(":app")
