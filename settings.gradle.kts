pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle provision the JDK 22 toolchain when it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "physis"

include(":core", ":ode", ":verify")
project(":core").projectDir = file("modules/core")
project(":ode").projectDir = file("modules/ode")
project(":verify").projectDir = file("modules/verify")

include(":models:lorenz", ":models:pendulum")
include(":examples")
