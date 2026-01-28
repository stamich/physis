// JavaFX + ScalaFX for the UI modules (:fx, :apps:lab) only.
// "media" is needed too: ScalaFX loads javafx.scene.media classes when a Stage is created.
//
// JavaFX is added as plain Maven artifacts with the platform classifier instead of the
// OpenJFX Gradle plugin. JavaFX 24 is the newest line that supports JDK 22.

plugins {
    id("physis.scala-conventions")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javafxVersion = catalog.findVersion("javafx").get().requiredVersion

val javafxPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        os.contains("win") -> "win"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

dependencies {
    for (module in listOf("base", "graphics", "controls", "media")) {
        api("org.openjfx:javafx-$module:$javafxVersion:$javafxPlatform")
    }
    api(catalog.findLibrary("scalafx").get())
}

tasks.named("checkUiFree") { enabled = false }

// FX smoke tests need a display. CI runs them on Linux under xvfb with -PfxSmoke=true;
// elsewhere they are skipped unless the property is set.
val fxSmoke = providers.gradleProperty("fxSmoke").orElse("false")
tasks.withType<Test>().configureEach {
    systemProperty("physis.fx.smoke", fxSmoke.get())
    if (fxSmoke.get() == "true") {
        systemProperty("prism.order", "sw")
    }
}
