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
