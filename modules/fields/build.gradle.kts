plugins {
    id("physis.scala-conventions")
}

description = "Physis fields and agents: 2D grids with halos, boundaries, stencils, particle sets and neighbour search (no UI)."

dependencies {
    api(project(":core"))
    testImplementation(project(":verify"))
}
