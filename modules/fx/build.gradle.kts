plugins {
    id("physis.javafx-conventions")
}

description = "Physis FX: JavaFX/ScalaFX renderers, render loop and generated parameter panel."

dependencies {
    api(project(":core"))
    api(project(":view"))
    testImplementation(project(":models:grayscott"))
}
