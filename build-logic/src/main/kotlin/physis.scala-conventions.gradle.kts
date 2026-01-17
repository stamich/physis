// Shared setup for every Physis module: Scala 3 LTS on a JDK 22 toolchain,
// strict compiler flags, munit tests, a `slowTest` suite and scalafmt.

import org.gradle.api.tasks.scala.ScalaCompile

plugins {
    `java-library`
    scala
    id("com.diffplug.spotless")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = catalog.findLibrary(alias).get()

val javaRelease = 22

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaRelease))
    }
}

dependencies {
    api(lib("scala3-library"))
}

tasks.withType<ScalaCompile>().configureEach {
    scalaCompileOptions.additionalParameters = listOf(
        "-release", javaRelease.toString(),
        "-encoding", "UTF-8",
        "-deprecation",
        "-feature",
        "-unchecked",
        "-Werror",
        // Parameters are excluded on purpose: numerical interfaces are full of
        // implementations that legitimately ignore an argument (time, rng, ...).
        "-Wunused:imports,privates,locals,implicits",
        "-Wvalue-discard",
    )
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnit()
            dependencies {
                implementation(lib("munit"))
                implementation(lib("munit-scalacheck"))
            }
        }

        // Long-running validation (Lyapunov exponents, long integrations).
        // Run with `./gradlew slowTest`; CI runs it nightly.
        register<JvmTestSuite>("slowTest") {
            useJUnit()
            dependencies {
                implementation(project())
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.named("test"))
                }
            }
        }
    }
}

// slowTest sees everything the unit tests see (munit, test-only project dependencies).
configurations.matching { it.name == "slowTestImplementation" }.configureEach {
    extendsFrom(configurations.getByName("testImplementation"))
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

spotless {
    scala {
        target("src/**/*.scala")
        scalafmt(catalog.findVersion("scalafmt").get().requiredVersion)
            .configFile(rootProject.file(".scalafmt.conf"))
    }
}
