plugins {
    id("physis.scala-conventions")
}

description = "Physis reference model: shallow water on a rotating plane, with gravity waves, geostrophic balance and Rossby waves, on a staggered grid."

dependencies {
    api(project(":fields"))
    api(project(":view"))
    testImplementation(project(":verify"))
}
