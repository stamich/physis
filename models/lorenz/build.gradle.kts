plugins {
    id("physis.scala-conventions")
}

description = "Physis reference model: lorenz."

dependencies {
    api(project(":ode"))
    testImplementation(project(":verify"))
}
