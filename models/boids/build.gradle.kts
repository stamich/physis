plugins {
    id("physis.scala-conventions")
}

description = "Physis reference model: Boids flocking."

dependencies {
    api(project(":fields"))
    api(project(":view"))
    testImplementation(project(":verify"))
}
