package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.core.run.{Disc, Intervention, Manifest, Op, Physis}
import physis.models.physarum.Physarum

class CliReplaySuite extends munit.FunSuite:
  private def go(args: String*): (Int, String, String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = Cli.execute(
      args.toList,
      new PrintStream(out, true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private val small =
    Seq("--model", "physarum", "--param", "n=300", "--param", "nx=64", "--param", "ny=64")

  /** A session the way the Lab records one: a stroke after 5 steps, 15 steps in all. */
  private def painted(digestOffset: Long = 0L): Manifest =
    val run = Physis
      .model(Physarum)
      .stepping(Physarum.stepping)
      .overrides(Map("n" -> 300.0, "nx" -> 64.0, "ny" -> 64.0))
      .seed(2L)
      .build()
    run.runSteps(5)
    val stroke = Intervention.Brush("food", Disc(30.0, 30.0, 6.0), Op.Assign, 5.0)
    assert(run.intervene(stroke).isRight)
    val result = run.runSteps(10)
    run.manifest(15.0).copy(steps = Some(15L), digest = Some(result.finalDigest + digestOffset))

  private def withFile[A](text: String)(body: Path => A): A =
    val file = Files.createTempFile("physis-cli-replay", ".json")
    try
      Files.writeString(file, text, StandardCharsets.UTF_8)
      body(file)
    finally
      val _ = Files.deleteIfExists(file)

  test("a manifest written by run now records the digest, and replay reproduces the run") {
    withFile("") { file =>
      val (c1, csv, summary) =
        go(Seq("run", "--steps", "12", "--seed", "4", "--manifest", file.toString) ++ small*)
      assertEquals(c1, 0, summary)
      assert(Files.readString(file).matches("(?s).*\"digest\"\\s*:\\s*-?\\d+.*"))
      val (c2, replayCsv, replaySummary) = go("replay", file.toString)
      assertEquals(c2, 0, replaySummary)
      assertEquals(replayCsv, csv)
      assertEquals(replaySummary, summary) // nothing is added when the digest matches
    }
  }

  test("a session with a stroke replays to its recorded digest") {
    withFile(Manifest.toJson(painted())) { file =>
      val (code, _, summary) = go("replay", file.toString)
      assertEquals(code, 0, summary)
      assert(summary.contains("accepted 15"), summary)
    }
  }

  test("a changed digest is exit code 1 with both digests in the message") {
    val wrong = painted(digestOffset = 1L)
    withFile(Manifest.toJson(wrong)) { file =>
      val (code, csv, summary) = go("replay", file.toString)
      assertEquals(code, 1)
      assert(csv.nonEmpty, "the CSV is still printed")
      assert(summary.contains("# digest DIFFERS: the replay ended at "), summary)
      assert(summary.contains(f"the manifest recorded ${wrong.digest.get}%016x"), summary)
    }
  }

  test("replay with --frames writes the frames and checks the digest too") {
    val dir = Files.createTempDirectory("physis-cli-replay-frames")
    try
      withFile(Manifest.toJson(painted())) { file =>
        val (code, out, err) =
          go("replay", file.toString, "--frames", dir.toString, "--every", "5")
        assertEquals(code, 0, err)
        assertEquals(out.linesIterator.size, 4) // steps 0, 5, 10, 15
      }
      withFile(Manifest.toJson(painted(digestOffset = 7L))) { file =>
        val (code, _, err) = go("replay", file.toString, "--frames", dir.toString, "--every", "5")
        assertEquals(code, 1)
        assert(err.contains("# digest DIFFERS"), err)
      }
    finally
      val stream = Files.list(dir)
      try stream.forEach(f =>
          val _ = Files.deleteIfExists(f)
        )
      finally stream.close()
      val _ = Files.deleteIfExists(dir)
  }

  test("a missing, unreadable or foreign manifest is exit code 1 with a reason") {
    val (c1, _, e1) = go("replay", "/nonexistent/m.json")
    assertEquals(c1, 1)
    assert(e1.contains("cannot read"), e1)
    withFile("{ not json") { file =>
      val (c2, _, e2) = go("replay", file.toString)
      assertEquals(c2, 1)
      assert(e2.startsWith("error: "), e2)
    }
  }
