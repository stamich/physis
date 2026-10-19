package physis.core.sweep

import java.nio.file.Files

import physis.core.{Probe, Stepping}
import physis.core.run.{Manifest, ModelRegistry}
import physis.core.testing.{Decay, DecayParams, DecayState}

class SweepOutputSuite extends munit.FunSuite:
  private val until = 0.5
  private val setup = SweepSetup[DecayParams, DecayState](
    Decay,
    _ =>
      Vector(Probe[DecayState]("y")((s, _) => s.y), Probe[DecayState]("twice")((s, _) => 2 * s.y)),
    Stepping.Fixed(0.01),
    until = until,
    sampleEvery = until
  )
  private val grid = ParamGrid
    .parse(Seq("k=0,1", "seed=1..2"), DecayParams.schema)
    .fold(e => fail(e), identity)

  /** Four points; the one with k = 1 and seed 2 fails with a message that needs quoting. */
  private val results =
    Sweep.run(setup, grid, 2) { (run, result) =>
      if run.params.k == 1.0 && run.seed == 2L then
        throw new IllegalStateException("k \"1\", really")
      result.finalDigest
    }

  test("the table has one row per point in grid order and quotes what needs it") {
    val lines = SweepOutput.csv(grid, results).split("\n").toVector
    assertEquals(lines.size, 5)
    assertEquals(lines.head, "index,k,seed,ok,time,steps,rejected,digest,y,twice,error")
    assert(lines(1).startsWith("0,0.0,1,true,0.5"), lines(1))
    assert(lines(1).contains(",50,0,"), lines(1))
    assert(lines(1).endsWith(",1.0,2.0,"), lines(1))
    assert(lines(3).startsWith("2,1.0,1,true,"), lines(3))
    assertEquals(lines(4), "3,1.0,2,false,,,,,,,\"k \"\"1\"\", really\"")
    val digest = results.head.outcome.fold(e => fail(e), _.digest)
    assert(lines(1).contains(f",$digest%016x,"), lines(1))
  }

  test("JSON Lines has one object per line, with numbers JSON can hold") {
    val lines = SweepOutput.jsonLines(results).split("\n").toVector
    assertEquals(lines.size, 4)
    assert(lines(0).startsWith("{\"index\":0,\"params\":{\"k\":0.0},\"seed\":1,\"ok\":true,"))
    assert(lines(0).contains("\"probes\":{\"y\":1.0,\"twice\":2.0}"), lines(0))
    assertEquals(
      lines(3),
      "{\"index\":3,\"params\":{\"k\":1.0},\"seed\":2,\"ok\":false,\"error\":\"k \\\"1\\\", really\"}"
    )
    assert(lines.forall(l => l.startsWith("{") && l.endsWith("}")))
  }

  test("a manifest is written for every finished run, and it replays to the same digest") {
    val withManifests =
      Sweep.run(setup, grid, 2)(SweepOutput.manifestJson[DecayParams, DecayState](until))
    val dir = Files.createTempDirectory("physis-sweep")
    val files = SweepOutput.writeManifests(dir, withManifests).fold(e => fail(e), identity)
    assertEquals(files.size, 4)
    assertEquals(files.head.getFileName.toString, "point-0000.json")
    val registry = ModelRegistry.of(Decay)
    for (file, r) <- files.zip(withManifests) do
      val manifest = Manifest.fromJson(Files.readString(file)).fold(e => fail(e), identity)
      val replay = Manifest.replay(manifest, registry).fold(e => fail(e), identity).execute()
      assertEquals(replay.finalDigest, r.outcome.fold(e => fail(e), _.digest))
      assertEquals(manifest.seed, r.point.seed.get)
      Files.delete(file)
    Files.delete(dir)
  }

  test("manifests of failed runs are not written") {
    val strings = results.map(r => PointResult(r.point, r.outcome.map(o => o.copy(extra = "{}"))))
    val dir = Files.createTempDirectory("physis-sweep-failed")
    val files = SweepOutput.writeManifests(dir, strings).fold(e => fail(e), identity)
    assertEquals(files.size, 3)
    files.foreach(Files.delete)
    Files.delete(dir)
  }
