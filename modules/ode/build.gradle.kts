plugins {
    id("physis.scala-conventions")
}

description = "Physis ODE support: systems, RK4, Dormand-Prince 5(4) and velocity Verlet."

dependencies {
    api(project(":core"))
    testImplementation(project(":verify"))
}
