// Shared setup for every Physis module: Scala 3 LTS on a JDK 22 toolchain,
// strict compiler flags, munit tests, a `slowTest` suite and scalafmt.

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
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
    // Gradle already passes -deprecation and -unchecked (scalaCompileOptions defaults);
    // repeating them is a warning, which -Werror turns into an error. Scala 3 reads UTF-8.
    scalaCompileOptions.additionalParameters = listOf(
        "-release", javaRelease.toString(),
        "-feature",
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

// UI-free check: the engine, views and models must never pull JavaFX or ScalaFX.
// physis.javafx-conventions disables this task for the UI modules.
// Resolution results (component ids) only; no artifact is built or downloaded for the check.
val runtimeRoot = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
val checkUiFree by tasks.registering {
    description = "Fails if JavaFX or ScalaFX is on the runtime classpath."
    group = "verification"
    val root = runtimeRoot
    val projectPath = path
    doLast {
        val seen = mutableSetOf<ResolvedComponentResult>()
        val queue = ArrayDeque(listOf(root.get()))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (seen.add(component)) {
                component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue.add(it.selected) }
            }
        }
        val ui = seen.map { it.id }
            .filterIsInstance<ModuleComponentIdentifier>()
            .map { "${it.group}:${it.module}:${it.version}" }
            .filter { it.startsWith("org.openjfx:") || it.startsWith("org.scalafx:") }
        if (ui.isNotEmpty()) {
            throw GradleException("$projectPath must stay UI-free but depends on: ${ui.joinToString()}")
        }
    }
}
tasks.named("check") { dependsOn(checkUiFree) }
