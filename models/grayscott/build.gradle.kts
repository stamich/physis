plugins {
    id("physis.scala-conventions")
}

description = "Physis reference model: Gray–Scott reaction–diffusion."

dependencies {
    api(project(":fields"))
    api(project(":view"))
    testImplementation(project(":verify"))
}
