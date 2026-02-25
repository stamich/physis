plugins {
    id("physis.scala-conventions")
}

description = "Physis reference model: the damped wave equation with walls and absorbing edges."

dependencies {
    api(project(":fields"))
    api(project(":view"))
    testImplementation(project(":verify"))
}
