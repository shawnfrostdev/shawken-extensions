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
    }
}

rootProject.name = "shawken-extensions"

// Auto-include all extension modules
rootDir.listFiles()?.forEach { dir ->
    if (dir.isDirectory && File(dir, "build.gradle.kts").exists()) {
        include(":${dir.name}")
    }
}
