plugins {
    id("physis.scala-conventions")
    application
}

description = "Physis command line: run models, sweep parameters, export frames and replay manifests."

dependencies {
    implementation(project(":models:lorenz"))
    implementation(project(":models:pendulum"))
    implementation(project(":models:grayscott"))
    implementation(project(":models:boids"))
    implementation(project(":models:wave"))
    implementation(project(":models:physarum"))
    implementation(project(":models:fluid"))
}

application {
    applicationName = "physis-cli"
    mainClass.set("physis.cli.Main")
}
