plugins {
    id("physis.scala-conventions")
    application
}

description = "Physis examples: headless runs from the command line."

dependencies {
    implementation(project(":models:lorenz"))
    implementation(project(":models:pendulum"))
}

application {
    mainClass.set("physis.examples.RunHeadless")
}
