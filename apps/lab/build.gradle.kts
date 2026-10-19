plugins {
    id("physis.javafx-conventions")
    application
}

description = "Physis Lab: desktop application for running models live."

dependencies {
    implementation(project(":fx"))
    implementation(project(":models:lorenz"))
    implementation(project(":models:pendulum"))
    implementation(project(":models:grayscott"))
    implementation(project(":models:boids"))
    implementation(project(":models:physarum"))
    implementation(project(":models:wave"))
}

application {
    mainClass.set("physis.lab.Launcher")
}
