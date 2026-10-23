package physis.lab

import java.nio.file.Files

import physis.core.live.{Command, Snapshot}

class LabEstimateSuite extends munit.FunSuite:

  /** A paused session after `steps` steps, with a snapshot of its state (the buffer hands each one out once). */
  private def stepped(model: LabModel, steps: Int): (LabSession, Snapshot) =
    val s = model.open(seed = 2L, startPaused = true)
    s.live.start()
    for _ <- 1 to steps do s.live.send(Command.StepOnce)
    val deadline = System.currentTimeMillis() + 60_000
    var snapshot: Option[Snapshot] = None
    while s.live.manifest().steps.forall(_ < steps.toLong) || snapshot.forall(_.step < steps) do
      assert(System.currentTimeMillis() < deadline, "steps not taken")
      s.live.latest().foreach(x => snapshot = Some(x))
      Thread.sleep(10)
    (s, snapshot.get)

  test("the estimate of a file is its size to the byte, and its frames are exact") {
    for (model, colorBar, scale) <- Seq((Catalog.fluid, true, 1), (Catalog.wave, false, 2)) do
      val (s, shot) = stepped(model, 12)
      try
        val options = AnimationOptions(every = 4, scale = scale, colorBar = colorBar)
        val estimate =
          LabAnimations.estimate(s, shot, 12L, 0.0, options).fold(e => fail(e), identity)
        val file = Files.createTempFile("physis-estimate", ".png")
        try
          val report = LabAnimations
            .save(s, s.live.manifest(), Catalog.registry, file, options.copy(writeManifest = false))
            .fold(e => fail(e), identity)
          assertEquals(estimate.frames, report.frames)
          assertEquals(estimate.bytes, report.bytes, s"${model.id}: ${estimate.text}")
          assertEquals(estimate.seconds, None)
        finally
          val _ = Files.deleteIfExists(file)
      finally s.live.stop()
  }

  test("the time is the steps over the measured rate, and the text reads as a sentence") {
    val (s, shot) = stepped(Catalog.fluid, 4)
    try
      val e = LabAnimations.estimate(s, shot, 300L, 4.0, AnimationOptions(every = 3)).toOption.get
      assertEquals(e.seconds, Some(75.0))
      assert(e.text.contains("101 frames") && e.text.contains("about 75 s"), e.text)
      val slow = e.copy(seconds = Some(600.0))
      assert(slow.text.contains("about 10 min"), slow.text)
      assert(e.copy(seconds = None).text.contains("not known"))
    finally s.live.stop()
  }

  test("more than 600 frames is refused with the every that fits") {
    assertEquals(LabAnimations.frameCount(1200L, AnimationOptions(every = 1)), 1201)
    val message = LabAnimations.check(1200L, AnimationOptions(every = 1)).get
    assert(message.contains("1201 frames") && message.contains("600"), message)
    assertEquals(LabAnimations.minEvery(1200L), 3L)
    assert(LabAnimations.check(1200L, AnimationOptions(every = 3)).isEmpty)
    for steps <- Seq(1L, 2L, 599L, 600L, 601L, 5000L, 123456L) do
      val every = LabAnimations.minEvery(steps)
      assert(
        LabAnimations.frameCount(steps, AnimationOptions(every)) <= LabAnimations.MaxFrames,
        s"$steps"
      )
    val (s, _) = stepped(Catalog.fluid, 4)
    try
      val refused = LabAnimations.save(
        s,
        s.live.manifest().copy(steps = Some(1200L)),
        Catalog.registry,
        Files.createTempFile("physis-cap", ".png"),
        AnimationOptions(every = 1)
      )
      assert(refused.left.exists(_.contains("the most is 600")), refused.toString)
    finally s.live.stop()
  }

  test("every field model suggests a length that fits, whatever the session length") {
    for m <- Seq(Catalog.grayScott, Catalog.wave, Catalog.physarum, Catalog.fluid) do
      for steps <- Seq(1L, 2L, 3L, 10L, 99L, 800L, 5000L, 100000L) do
        val o = LabAnimations.suggest(steps, m.animationDefaults, cells = 256)
        val frames = LabAnimations.frameCount(steps, o)
        assert(o.every >= 1L && o.fps >= 1, s"${m.id} $steps: $o")
        assert(frames <= LabAnimations.MaxFrames, s"${m.id} $steps: $frames frames")
        if steps >= 2L then assert(frames >= 2, s"${m.id} $steps: one frame")
        assertEquals(LabAnimations.check(steps, o), None, s"${m.id} $steps")
  }

  test("the fluid suggests 20 pictures a second every 4 steps: 800 steps are 10 seconds") {
    val o = LabAnimations.suggest(800L, Catalog.fluid.animationDefaults, cells = 128)
    assertEquals((o.every, o.fps, o.scale), (4L, 20, 4))
    assertEquals(LabAnimations.frameCount(800L, o) / o.fps, 10)
    assertEquals(LabAnimations.suggest(10L, Catalog.grayScott.animationDefaults, 256).every, 5L)
    assertEquals(LabAnimations.scaleFor(512), 1)
    assertEquals(LabAnimations.scaleFor(64), 8)
    assertEquals(LabAnimations.scaleFor(1000), 1)
  }
