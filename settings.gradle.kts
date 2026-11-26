pluginManagement {
  // Resolve Kotlin Serialization from its canonical Maven Central implementation
  // rather than relying on the Gradle Plugin Portal marker artifact. This avoids
  // marker-resolution failures in restricted GitHub Actions runners while keeping
  // the serialization compiler plugin aligned with the Kotlin version catalog.
  resolutionStrategy {
    eachPlugin {
      if (requested.id.id == "org.jetbrains.kotlin.plugin.serialization") {
        useModule("org.jetbrains.kotlin:kotlin-serialization:${requested.version}")
      }
    }
  }

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

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}
 
rootProject.name = "مخزني الذكي"
