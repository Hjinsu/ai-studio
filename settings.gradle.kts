plugins {
    // Java 21 toolchain이 없으면 Gradle이 자동으로 내려받는다 (ADR-006, docs/16_SOURCES.md)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ai-studio"

include("backend")
