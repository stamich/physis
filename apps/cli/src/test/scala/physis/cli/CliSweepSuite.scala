package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class CliSweepSuite extends munit.FunSuite:
  private def go(args: String*): (Int, String, String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = Cli.execute(
      args.toList,
      new PrintStream(out, true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private val gs = Seq("--model", "gray-scott", "--param", "nx=32", "--param", "ny=32")

  private val grid = Seq("--sweep", "F=0.03,0.05", "--sweep", "k=0.06,0.065", "--until", "20")

  private def progressLines(err: String): Set[String] =
    err.linesIterator.filter(_.matches("# \\d+/\\d+")).toSet

  test("a sweep gives the same CSV on 1 and on 4 threads, and reports its progress") {
    val (c1, csv1, err1) = go(Seq("sweep", "--threads", "1") ++ gs ++ grid*)
    val (c4, csv4, err4) = go(Seq("sweep", "--threads", "4") ++ gs ++ grid*)
    assertEquals(c1, 0, err1)
    assertEquals(c4, 0, err4)
    assertEquals(csv4, csv1)
    assertEquals(csv1.linesIterator.size, 5) // header and four points
    assertEquals(progressLines(err1), Set("# 1/4", "# 2/4", "# 3/4", "# 4/4"))
    assertEquals(progressLines(err4), Set("# 1/4", "# 2/4", "# 3/4", "# 4/4"))
    assert(err1.contains("# sweep of gray-scott: 4 points on 1 threads, 0 failed"), err1)
  }

  test("--quiet leaves out the progress lines and keeps the summary") {
    val (code, _, err) = go(Seq("sweep", "--quiet") ++ gs ++ grid*)
    assertEquals(code, 0, err)
    assertEquals(progressLines(err), Set.empty[String])
    assert(err.contains("# sweep of gray-scott: 4 points"), err)
  }

  test("--dry-run prints the axes and the size of the grid and runs and writes nothing") {
    val file = Files.createTempFile("physis-cli-sweep", ".csv")
    val _ = Files.deleteIfExists(file)
    val (code, out, err) = go(Seq("sweep", "--dry-run", "--out", file.toString) ++ gs ++ grid*)
    assertEquals(code, 0, err)
    assertEquals(
      out.linesIterator.toVector,
      Vector(
        "# sweep of gray-scott: 4 points, nothing run",
        "# F: 0.03, 0.05",
        "# k: 0.06, 0.065"
      )
    )
    assert(!Files.exists(file), "a dry run wrote the CSV")
    assertEquals(progressLines(err), Set.empty[String])
  }

  private val refused = Seq(
    "--model",
    "physarum",
    "--param",
    "n=200",
    "--param",
    "nx=64",
    "--param",
    "ny=64",
    "--param",
    "stepSize=3",
    "--sweep",
    "sensorDist=5,1",
    "--until",
    "3"
  )

  test("a point the model refuses fails the sweep with exit code 1 after the others have run") {
    val (code, csv, err) = go(Seq("sweep", "--threads", "1") ++ refused*)
    assertEquals(code, 1, err)
    val rows = csv.linesIterator.toVector
    assertEquals(rows.size, 3)
    assert(rows(1).split(",")(2) == "true", rows(1)) // sensorDist 5 ran
    assert(rows(2).split(",")(2) == "false", rows(2)) // sensorDist 1 did not
    assert(err.contains("# point 1:"), err)
    assert(err.contains("1 failed"), err)
  }

  test("--fail-fast refuses the sweep before anything runs and names the point") {
    val file = Files.createTempFile("physis-cli-sweep", ".csv")
    val _ = Files.deleteIfExists(file)
    val (code, out, err) = go(Seq("sweep", "--fail-fast", "--out", file.toString) ++ refused*)
    assertEquals(code, 1)
    assertEquals(out, "")
    assert(err.contains("error: point 1 (sensorDist=1.0): "), err)
    assert(!err.contains("# sweep of"), err)
    assert(!Files.exists(file), "a refused sweep wrote output")
    // a grid in which every point is fine passes the check and runs
    val fine = Seq("sweep", "--fail-fast", "--sweep", "sensorDist=5,6") ++
      refused.take(10) ++ Seq("--until", "3")
    val (c2, csv2, e2) = go(fine*)
    assertEquals(c2, 0, e2)
    assertEquals(csv2.linesIterator.size, 3)
  }

  test("a manifest written by a sweep carries the digest of its row and replays to it") {
    val dir = Files.createTempDirectory("physis-cli-sweep")
    try
      val (code, csv, err) =
        go(Seq("sweep", "--manifests", dir.toString, "--seed", "3") ++ gs ++ grid*)
      assertEquals(code, 0, err)
      val files = Files.list(dir).toArray.map(_.asInstanceOf[Path]).sortBy(_.toString).toVector
      assertEquals(files.size, 4)
      val text = Files.readString(files.head)
      assert(text.matches("(?s).*\"digest\"\\s*:\\s*-?\\d+.*"), text)
      val digestAt = csv.linesIterator.next().split(",").indexOf("digest")
      for (file, k) <- files.zipWithIndex do
        val (rc, _, summary) = go("replay", file.toString)
        assertEquals(rc, 0, summary)
        val row = csv.linesIterator.toVector(k + 1).split(",")(digestAt)
        assert(summary.contains(s"final digest $row"), s"$summary / $row")
    finally
      val stream = Files.list(dir)
      try stream.forEach(f =>
          val _ = Files.deleteIfExists(f)
        )
      finally stream.close()
      val _ = Files.deleteIfExists(dir)
  }
