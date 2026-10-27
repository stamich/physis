package physis.lab

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

import physis.core.live.Command
import physis.core.run.{Disc, Intervention, Op}
import physis.view.Frames

class LabAnimationsSuite extends munit.FunSuite:

  private def withDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("physis-lab-animation")
    try body(dir)
    finally
      val stream = Files.walk(dir)
      try
        stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach { f =>
          val _ = Files.deleteIfExists(f)
        }
      finally stream.close()

  /** A paused session of `model` that took `steps` steps and had a stroke and a change. */
  private def session(model: LabModel, stroke: Intervention, change: (String, Double), steps: Int) =
    val s = model.open(seed = 5L, startPaused = true)
    val live = s.live
    live.start()
    def take(n: Int): Unit = for _ <- 1 to n do live.send(Command.StepOnce)
    take(steps / 4)
    live.send(Command.Intervene(stroke))
    take(steps / 4)
    live.send(Command.SetParams(Map(change)))
    take(steps - 2 * (steps / 4))
    val deadline = System.currentTimeMillis() + 60_000
    while live.manifest().steps.forall(_ < steps.toLong) do
      assert(System.currentTimeMillis() < deadline, "steps not taken")
      Thread.sleep(10)
    s

  private val cases = Seq(
    (
      Catalog.fluid,
      Intervention.Brush("dye", Disc(40.0, 40.0, 8.0), Op.Assign, 1.0),
      "viscosity" -> 0.05
    ),
    (
      Catalog.wave,
      Intervention.Brush("wall", Disc(100.0, 100.0, 6.0), Op.Assign, 1.0),
      "damping" -> 0.01
    ),
    (
      Catalog.convection,
      Intervention.Brush("heat", Disc(40.0, 20.0, 6.0), Op.Assign, 0.5),
      "rayleigh" -> 5000.0
    ),
    (
      Catalog.shallow,
      Intervention.Brush("land", Disc(60.0, 40.0, 6.0), Op.Assign, 1.0),
      "friction" -> 0.00001
    ),
    (
      Catalog.grayScott,
      Intervention.Brush("v", Disc(60.0, 60.0, 6.0), Op.Assign, 0.5),
      "F" -> 0.03
    )
  )

  private def cli(args: String*): (Int, String) =
    val err = new ByteArrayOutputStream()
    val code = physis.cli.Cli.execute(
      args.toList,
      new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, err.toString(StandardCharsets.UTF_8))

  for (model, stroke, change) <- cases do
    test(s"${model.id}: the Lab's animation equals the file physis-cli replay --animate writes") {
      withDir { dir =>
        val s = session(model, stroke, change, 20)
        try
          val manifest = s.live.manifest()
          assertEquals(manifest.steps, Some(20L))
          val lab = dir.resolve("lab.png")
          val options = AnimationOptions(every = 5, fps = 8, scale = 1, loops = 2)
          val seen = Vector.newBuilder[(Int, Int)]
          val report = LabAnimations
            .save(s, manifest, Catalog.registry, lab, options, (d, t) => seen += ((d, t)))
            .fold(e => fail(e), identity)
          assertEquals(report.frames, 5)
          assertEquals(report.steps, 20L)
          assertEquals(seen.result().last, (5, 5))
          assertEquals(Some(report.digest), manifest.digest)
          // the manifest written next to it replays to the same pictures
          val saved = report.manifest.getOrElse(fail("no manifest written"))
          assertEquals(saved, LabAnimations.manifestPath(lab))
          val (code, err) =
            cli(
              "replay",
              saved.toString,
              "--animate",
              dir.resolve("cli.png").toString,
              "--every",
              "5",
              "--fps",
              "8",
              "--loops",
              "2"
            )
          assertEquals(code, 0, err)
          assert(!err.contains("DIFFERS"), err)
          assert(
            java.util.Arrays.equals(
              Files.readAllBytes(lab),
              Files.readAllBytes(dir.resolve("cli.png"))
            ),
            "the Lab's file differs from the CLI's"
          )
          assert(ImageIO.read(lab.toFile) != null, "ImageIO cannot read the first frame")
          assertEquals(report.bytes, Files.size(lab))
        finally s.live.stop()
      }
    }

  test("a cancelled export leaves no file and no manifest") {
    withDir { dir =>
      val (model, stroke, change) = cases.head
      val s = session(model, stroke, change, 20)
      try
        val file = dir.resolve("x.png")
        var stop = false
        val result = LabAnimations.save(
          s,
          s.live.manifest(),
          Catalog.registry,
          file,
          AnimationOptions(every = 2),
          (done, _) => if done == 3 then stop = true,
          () => stop
        )
        assertEquals(result, Left(Frames.Cancelled))
        assert(!Files.exists(file) && !Files.exists(LabAnimations.manifestPath(file)))
      finally s.live.stop()
    }
  }

  test("a session at step 0, a model without an image and bad options are refused") {
    withDir { dir =>
      val fresh = Catalog.fluid.open(seed = 1L, startPaused = true)
      val none = LabAnimations
        .save(
          fresh,
          fresh.live.manifest(),
          Catalog.registry,
          dir.resolve("a.png"),
          AnimationOptions(5)
        )
      assert(none.left.exists(_.contains("nothing has run yet")), none.toString)
      val ode = Catalog.lorenz.open(seed = 1L, startPaused = true)
      val noImage = LabAnimations
        .save(ode, ode.live.manifest(), Catalog.registry, dir.resolve("b.png"), AnimationOptions(5))
      assert(noImage.left.exists(_.contains("no image")), noImage.toString)
      val s = session(cases.head._1, cases.head._2, cases.head._3, 8)
      try
        val bad = LabAnimations
          .save(s, s.live.manifest(), Catalog.registry, dir.resolve("c.png"), AnimationOptions(0))
        assert(bad.isLeft && !Files.exists(dir.resolve("c.png")))
        assertEquals(
          LabAnimations.manifestPath(Path.of("/tmp/a/run.png")),
          Path.of("/tmp/a/run.json")
        )
        assertEquals(LabAnimations.manifestPath(Path.of("noext")), Path.of("noext.json"))
        assertEquals(LabAnimations.frameCount(20, AnimationOptions(5)), 5)
      finally s.live.stop()
    }
  }
