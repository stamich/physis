package physis.lab

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

import javafx.scene.Scene
import javafx.stage.Stage

/**
 * Renders the Lab for the documentation: writes build/screenshots/lab-<model>.png after the
 * model has run for a while. CI keeps the files as a workflow artifact.
 */
class LabScreenshotSuite extends LabSuite:
  private def save(scene: Scene, path: Path): Unit =
    val image = scene.snapshot(null)
    val w = image.getWidth.toInt
    val h = image.getHeight.toInt
    val out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val reader = image.getPixelReader
    for y <- 0 until h; x <- 0 until w do out.setRGB(x, y, reader.getArgb(x, y))
    Files.createDirectories(path.getParent)
    assert(ImageIO.write(out, "png", path.toFile), s"no PNG writer for $path")

  fxTest("screenshots of the Lab") {
    val window = LabFx.onFx(new LabWindow(Catalog.models))
    val (stage, scene) = LabFx.onFx {
      val scene = new Scene(window.root, 1280, 820)
      val stage = new Stage()
      stage.setScene(scene)
      stage.show()
      (stage, scene)
    }
    try
      val shots = Seq(
        Catalog.lorenz -> 15.0,
        Catalog.pendulum -> 8.0,
        Catalog.grayScott -> 4000.0, // time is measured in steps
        Catalog.boids -> 10.0,
        Catalog.wave -> 120.0 // time is measured in time units, 2 steps each
      )
      for (model, seconds) <- shots do
        LabFx.onFx(window.open(model.id))
        if model eq Catalog.grayScott then LabFx.onFx(window.applyPreset("Coral"))
        if model eq Catalog.wave then LabFx.onFx(window.applyPreset("Double slit"))
        LabFx.await(s"${model.id} reached time $seconds", timeoutMs = 60_000)(
          LabFx.onFx(window.loop.flatMap(_.snapshot).exists(_.time >= seconds))
        )
        val file = Path.of("build", "screenshots", s"lab-${model.id}.png")
        LabFx.onFx(save(scene, file))
        assert(Files.size(file) > 10_000, s"$file is too small")
    finally
      LabFx.onFx {
        window.close()
        stage.close()
      }
  }
