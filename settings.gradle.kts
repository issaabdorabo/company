pluginManagement {
  // Resolve Kotlin's serialization plugin directly from Maven Central.
  // This avoids relying on the Gradle Plugin Portal marker artifact, which
  // intermittently fails to resolve in GitHub Actions.
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
