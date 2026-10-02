pluginManagement { includeBuild("build-logic") }

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "recsys"

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories {
    mavenCentral()
    maven("https://packages.confluent.io/maven/") {
      content { includeGroup("io.confluent") }
    }
  }
}

include(
  "libs:event-schema",
  "libs:common",
  "libs:feature-store",
  "libs:openai-client",
  "libs:vector-index",
  "libs:web-support",
  "services:ingestion-api",
  "services:catalog-service",
  "services:stream-processor",
  "services:embedding-worker",
  "services:recommendation-api",
  "tools:event-simulator",
)
