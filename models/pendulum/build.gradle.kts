plugins {
    id("physis.scala-conventions")
}

description = "Physis reference model: pendulum."

dependencies {
    api(project(":ode"))
    api(project(":view"))
    testImplementation(project(":verify"))
}
