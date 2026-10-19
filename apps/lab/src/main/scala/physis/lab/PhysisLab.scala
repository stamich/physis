package physis.lab

import scalafx.application.JFXApp3

/** Physis Lab: run a model live, change its parameters, save and replay sessions. */
object PhysisLab extends JFXApp3:
  override def start(): Unit =
    val window = new LabWindow(Catalog.models)
    val scene = new javafx.scene.Scene(window.root, 1280, 820)
    window.installShortcuts(scene)
    stage = new JFXApp3.PrimaryStage()
    stage.title = "Physis Lab"
    stage.scene = new scalafx.scene.Scene(scene)
    stage.delegate.setOnCloseRequest(_ => window.close())
    window.open(Catalog.models.head.id)
