package physis.core.live

import physis.core.Stepping
import physis.core.run.{Disc, Intervention, ModelRegistry, Manifest, Op, Physis}
import physis.core.run.Intervention.Brush
import physis.core.testing.{DecayParams, DecayState, PokableDecay}

class LiveInterventionSuite extends munit.FunSuite:
  private given StateExport[DecayState] with
    def size(state: DecayState): Int = 1
    def copyInto(state: DecayState, out: Array[Double]): Unit = out(0) = state.y

  private val builder =
    Physis
      .model(PokableDecay)
      .withParams(DecayParams(k = 0.5, y0 = 1.0))
      .stepping(Stepping.Fixed(0.001))

  private val registry = ModelRegistry.of(PokableDecay)

  private def dab(value: Double): Intervention =
    Brush("y", Disc(0, 0, 1), Op.Add, value)

  private def awaitStep(live: LiveRun[?, ?], step: Long): Unit =
    val deadline = System.currentTimeMillis() + 3000
    var reached = false
    while !reached do
      live.latest().foreach(s => reached = s.step >= step)
      if !reached then
        assert(System.currentTimeMillis() < deadline, s"step $step not reached")
        Thread.sleep(2)

  private def replayDigest(m: Manifest): Long =
    val parsed = Manifest.fromJson(Manifest.toJson(m)).fold(e => fail(e), identity)
    val replay = Manifest.replay(parsed, registry).fold(e => fail(e), identity)
    val _ = replay.execute()
    replay.run.digest

  test("strokes wait for the next step while paused and keep their order") {
    val live = new LiveRun(builder, summon[StateExport[DecayState]], startPaused = true)
    live.start()
    try
      for _ <- 1 to 5 do live.send(Command.StepOnce)
      live.send(Command.Intervene(dab(1.0)))
      live.send(Command.Intervene(dab(2.0)))
      awaitStep(live, 5)
      val before = live.manifest()
      assertEquals(before.interventions, Vector.empty)
      live.send(Command.StepOnce)
      awaitStep(live, 6)
      val m = live.manifest()
      assertEquals(m.interventions.map(_.step), Vector(5L, 5L))
      assertEquals(m.interventions.map(_.intervention), Vector(dab(1.0), dab(2.0)))
      assertEquals(live.latest().map(_.interventions).getOrElse(2), 2)
      assertEquals(replayDigest(m), m.digest.get)
    finally live.stop()
  }

  test("strokes sent from another thread across pause, steps and a restart replay exactly") {
    val live = new LiveRun(builder, summon[StateExport[DecayState]], startPaused = true)
    live.start()
    try
      val sender = new Thread(() =>
        for i <- 1 to 40 do
          live.send(Command.Intervene(dab(0.01 * i)))
          if i % 5 == 0 then live.send(Command.StepOnce)
      )
      sender.start()
      sender.join()
      live.send(Command.SetParams(Map("k" -> 2.0)))
      live.send(Command.Intervene(dab(0.5)))
      live.send(Command.StepOnce)
      awaitStep(live, 9)
      val m = live.manifest()
      assertEquals(m.interventions.size, 41)
      assertEquals(m.interventions.map(_.step).sorted, m.interventions.map(_.step))
      assertEquals(replayDigest(m), m.digest.get)

      live.send(Command.Restart(Map.empty, None))
      live.send(Command.StepOnce)
      val cleared = live.manifest()
      assertEquals(cleared.interventions, Vector.empty)
      assertEquals(cleared.events, Vector.empty)
      live.send(Command.Intervene(dab(3.0)))
      live.send(Command.Resume)
      awaitStep(live, 20)
      live.send(Command.Pause)
      val after = live.manifest()
      assertEquals(after.interventions.size, 1)
      assertEquals(replayDigest(after), after.digest.get)
    finally live.stop()
  }

  test("reset clears the log of strokes") {
    val live = new LiveRun(builder, summon[StateExport[DecayState]], startPaused = true)
    live.start()
    try
      live.send(Command.Intervene(dab(1.0)))
      live.send(Command.StepOnce)
      awaitStep(live, 1)
      assertEquals(live.manifest().interventions.size, 1)
      live.send(Command.Reset)
      // a manifest request may overtake the command, so look until the reset has happened
      val deadline = System.currentTimeMillis() + 5000
      while live.manifest().interventions.nonEmpty && System.currentTimeMillis() < deadline do
        Thread.sleep(5)
      assertEquals(live.manifest().interventions, Vector.empty)
    finally live.stop()
  }

  test("a refused stroke reaches the snapshot and is not recorded") {
    val live = new LiveRun(builder, summon[StateExport[DecayState]], startPaused = true)
    live.start()
    try
      live.send(Command.Intervene(Brush("nope", Disc(0, 0, 1), Op.Assign, 1.0)))
      live.send(Command.StepOnce)
      var message = ""
      var reached = false
      val deadline = System.currentTimeMillis() + 3000
      while !reached && System.currentTimeMillis() < deadline do
        live.latest().foreach { s =>
          if s.error.nonEmpty then message = s.error
          reached = s.step >= 1
        }
        Thread.sleep(2)
      assert(message.contains("cannot take"), message)
      assertEquals(live.manifest().interventions, Vector.empty)
    finally live.stop()
  }
