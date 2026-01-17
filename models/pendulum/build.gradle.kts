plugins {
    id("physis.scala-conventions")
}

description = "Physis reference model: pendulum."

dependencies {
    api(project(":ode"))
    testImplementation(project(":verify"))
}
