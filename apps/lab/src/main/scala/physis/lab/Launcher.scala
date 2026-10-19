package physis.lab

/**
 * Entry point of `./gradlew :apps:lab:run`. A plain main that does not extend
 * `javafx.application.Application`, so JavaFX can be loaded from the classpath.
 */
object Launcher:
  def main(args: Array[String]): Unit = PhysisLab.main(args)
