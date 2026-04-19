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

  private val wake = Seq(
    "--model",
    "fluid",
    "--preset",
    "Cylinder wake",
    "--param",
    "nx=96",
    "--param",
    "ny=36",
    "--param",
    "tracers=0",
    "--sweep",
    "viscosity=0.384,0.192,0.096",
    "--until",
    "8"
  )

  private def removeAll(dir: Path): Unit =
    val stream = Files.walk(dir)
    try
      stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach { f =>
        val _ = Files.deleteIfExists(f)
      }
    finally stream.close()

  test("a Reynolds scan of the cylinder wake gives the same CSV and montage on 1 and 4 threads") {
    val dir = Files.createTempDirectory("physis-cli-wake")
    try
      def scan(threads: Int): (String, Array[Byte], Path) =
        val png = dir.resolve(s"re-$threads.png")
        val manifests = dir.resolve(s"m-$threads")
        val (code, csv, err) = go(
          Seq(
            "montage",
            png.toString,
            "--threads",
            threads.toString,
            "--montage-columns",
            "3",
            "--manifests",
            manifests.toString
          ) ++ wake*
        )
        assertEquals(code, 0, err)
        assert(err.contains("3 pictures"), err)
        (csv, Files.readAllBytes(png), manifests)
      val (csv1, png1, m1) = scan(1)
      val (csv4, png4, _) = scan(4)
      assertEquals(csv4, csv1)
      assert(java.util.Arrays.equals(png1, png4), "the montage differs between 1 and 4 threads")
      val lines = csv1.linesIterator.toVector
      assertEquals(lines.size, 4)
      val header = lines.head.split(",")
      val ke = header.indexOf("kinetic_energy")
      assert(ke > 0, lines.head)
      val energies = lines.tail.map(_.split(",")(ke))
      assertEquals(energies.distinct.size, 3, "the viscosity changed nothing")
      // the cylinder is in every tile: flat grey cells
      val image = javax.imageio.ImageIO.read(dir.resolve("re-1.png").toFile)
      assert(image.getWidth >= 3 * 96, image.getWidth.toString)
      var grey = 0
      for y <- 0 until image.getHeight; x <- 0 until image.getWidth do
        if image.getRGB(x, y) == physis.view.HeatmapRaster.OverlayGrey then grey += 1
      assert(grey >= 3 * 30, s"$grey grey pixels for three cylinders")
      // a point's manifest holds the strokes of the preset and replays to its digest
      val first = Files.list(m1).toArray.map(_.asInstanceOf[Path]).minBy(_.toString)
      val (rc, _, summary) = go("replay", first.toString)
      assertEquals(rc, 0, summary)
      assert(!summary.contains("DIFFERS"), summary)
      assert(Files.readString(first).contains("wall"), "the cylinder is not in the manifest")
    finally removeAll(dir)
  }

  test("a sweep of the grid size cannot keep what a preset painted") {
    val (code, _, err) = go(
      "sweep",
      "--model",
      "fluid",
      "--preset",
      "Cylinder wake",
      "--sweep",
      "nx=64,96",
      "--until",
      "1"
    )
    assertEquals(code, 1)
    assert(err.contains("cannot keep what the preset painted"), err)
  }
