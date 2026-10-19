plugins {
    id("physis.scala-conventions")
    application
}

description = "Physis examples: headless runs from the command line."

dependencies {
    implementation(project(":models:lorenz"))
    implementation(project(":models:pendulum"))
    implementation(project(":models:grayscott"))
    implementation(project(":models:boids"))
    implementation(project(":models:wave"))
    implementation(project(":models:physarum"))
}

application {
    mainClass.set("physis.examples.RunHeadless")
}
