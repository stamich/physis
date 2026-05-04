plugins {
    id("physis.scala-conventions")
}

description = "Physis reference model: Rayleigh-Benard convection, a fluid heated from below, on a staggered grid."

dependencies {
    api(project(":fields"))
    api(project(":view"))
    testImplementation(project(":verify"))
}
