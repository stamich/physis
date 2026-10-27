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

include(":core", ":ode", ":verify", ":view", ":fx", ":fields")
project(":core").projectDir = file("modules/core")
project(":ode").projectDir = file("modules/ode")
project(":verify").projectDir = file("modules/verify")
project(":view").projectDir = file("modules/view")
project(":fx").projectDir = file("modules/fx")
project(":fields").projectDir = file("modules/fields")

include(":models:lorenz", ":models:pendulum", ":models:grayscott", ":models:boids", ":models:wave", ":models:physarum", ":models:fluid", ":models:convection", ":models:shallow")
include(":apps:cli")
include(":examples")
include(":apps:lab")
