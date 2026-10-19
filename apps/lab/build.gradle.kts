plugins {
    id("physis.javafx-conventions")
    application
}

description = "Physis Lab: desktop application for running models live."

dependencies {
    implementation(project(":fx"))
    implementation(project(":models:lorenz"))
    implementation(project(":models:pendulum"))
}

application {
    mainClass.set("physis.lab.Launcher")
}
