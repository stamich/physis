plugins {
    id("physis.scala-conventions")
}

description = "Physis verification helpers: convergence order, conservation, determinism, allocation."

dependencies {
    api(project(":core"))
}
