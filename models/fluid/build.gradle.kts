plugins {
    id("physis.scala-conventions")
}

description = "Physis reference model: incompressible flow on a staggered grid with a pressure projection."

dependencies {
    api(project(":fields"))
    api(project(":view"))
    testImplementation(project(":verify"))
}
