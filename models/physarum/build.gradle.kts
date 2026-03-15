plugins {
    id("physis.scala-conventions")
}

description = "Physis reference model: Physarum, agents that follow and lay a trail."

dependencies {
    api(project(":fields"))
    api(project(":view"))
    testImplementation(project(":verify"))
}
