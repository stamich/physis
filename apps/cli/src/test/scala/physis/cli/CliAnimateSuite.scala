package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import physis.core.run.{Intervention, Manifest}

class CliAnimateSuite extends munit.FunSuite:
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
    Seq("--model", "fluid", "--param", "nx=32", "--param", "ny=24", "--param", "tracers=0")

  private def int(b: Array[Byte], at: Int): Int =
    ((b(at) & 0xff) << 24) | ((b(at + 1) & 0xff) << 16) | ((b(at + 2) & 0xff) << 8) | (b(
      at + 3
    ) & 0xff)

  private def chunks(file: Array[Byte]): Vector[(String, Array[Byte])] =
    var at = 8
    val out = Vector.newBuilder[(String, Array[Byte])]
    while at < file.length do
      val n = int(file, at)
      out += ((new String(file, at + 4, 4, "US-ASCII"), file.slice(at + 8, at + 8 + n)))
      at += 12 + n
    out.result()

  private def withDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("physis-cli-animate")
    try body(dir)
    finally
      val stream = Files.walk(dir)
      try
        stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach { f =>
          val _ = Files.deleteIfExists(f)
        }
      finally stream.close()

  test("animate writes one animated PNG with the frames, the delay and the loops asked for") {
    withDir { dir =>
      val file = dir.resolve("run.png")
      val (code, out, err) = go(
        Seq(
          "animate",
          file.toString,
          "--steps",
          "20",
          "--every",
          "5",
          "--fps",
          "6",
          "--loops",
          "2"
        ) ++
          small*
      )
      assertEquals(code, 0, err)
      assertEquals(out.trim, file.toString)
      assert(err.contains("animation of fluid"), err)
      assert(err.contains("5 frames") && err.contains("6 fps"), err)
      val cs = chunks(Files.readAllBytes(file))
      val ac = cs.find(_._1 == "acTL").get._2
      assertEquals(int(ac, 0), 5)
      assertEquals(int(ac, 4), 2)
      val fc = cs.filter(_._1 == "fcTL").map(_._2)
      assertEquals(fc.size, 5)
      // delay numerator 1, denominator fps
      assertEquals(
        fc.map(d =>
          ((d(20) & 0xff) << 8 | (d(21) & 0xff), (d(22) & 0xff) << 8 | (d(23) & 0xff))
        ).distinct,
        Vector((1, 6))
      )
    }
  }

  test("the same command gives the same bytes; a preset paints its strokes into the manifest") {
    withDir { dir =>
      val a = dir.resolve("a.png")
      val b = dir.resolve("b.png")
      val m = dir.resolve("m.json")
      val args = Seq(
        "--model",
        "fluid",
        "--preset",
        "Cylinder wake",
        "--param",
        "tracers=0",
        "--steps",
        "6",
        "--every",
        "3"
      )
      assertEquals(go(Seq("animate", a.toString, "--manifest", m.toString) ++ args*)._1, 0)
      assertEquals(go(Seq("animate", b.toString) ++ args*)._1, 0)
      assert(java.util.Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b)))
      val manifest = Manifest.fromJson(Files.readString(m)).fold(e => fail(e), identity)
      assert(manifest.interventions.nonEmpty, "the preset's wall and dye are not recorded")
      assert(manifest.interventions.forall(_.step == 0L))
      assertEquals(manifest.steps, Some(6L))
      assert(manifest.digest.isDefined)
      // replay: the same picture, the same digest
      val c = dir.resolve("c.png")
      val (code, _, err) = go("replay", m.toString, "--animate", c.toString, "--every", "3")
      assertEquals(code, 0, err)
      assert(!err.contains("DIFFERS"), err)
      assert(java.util.Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(c)))
    }
  }

  test("strokes are recorded in the manifest and replay; extra strokes on a replay end the check") {
    withDir { dir =>
      val m = dir.resolve("m.json")
      val png = dir.resolve("s.png")
      val (code, _, err) = go(
        Seq(
          "animate",
          png.toString,
          "--steps",
          "12",
          "--every",
          "4",
          "--manifest",
          m.toString,
          "--stroke",
          "2:brush:dye:10:12:3:set:1",
          "--stroke",
          "5:pulse:u:8:12:2:1.5",
          "--stroke",
          "8:brush:wall:20:12:2:erase"
        ) ++ small*
      )
      assertEquals(code, 0, err)
      val manifest = Manifest.fromJson(Files.readString(m)).fold(e => fail(e), identity)
      assertEquals(manifest.interventions.map(_.step), Vector(2L, 5L, 8L))
      assert(manifest.interventions(1).intervention.isInstanceOf[Intervention.Pulse])
      val (c1, _, e1) = go("replay", m.toString, "--frames", dir.resolve("f").toString)
      assertEquals(c1, 0, e1)
      assert(!e1.contains("DIFFERS"), e1)
      val m2 = dir.resolve("m2.json")
      val (c2, _, e2) = go(
        "replay",
        m.toString,
        "--frames",
        dir.resolve("g").toString,
        "--stroke",
        "10:brush:dye:5:5:2:add:1",
        "--manifest",
        m2.toString
      )
      assertEquals(c2, 0, e2)
      val longer = Manifest.fromJson(Files.readString(m2)).fold(e => fail(e), identity)
      assertEquals(longer.interventions.size, 4)
    }
  }

  test("a wrong stroke, fps or loops is a usage error, exit code 2") {
    withDir { dir =>
      val png = dir.resolve("x.png").toString
      def code(extra: String*): Int =
        go(Seq("animate", png, "--steps", "4") ++ small ++ extra*)._1
      assertEquals(code("--stroke", "nonsense"), 2)
      assertEquals(code("--stroke", "x:brush:dye:1:1:2:set:1"), 2)
      assertEquals(code("--stroke", "-1:brush:dye:1:1:2:set:1"), 2)
      assertEquals(code("--stroke", "1:brush:dye:1:1:2:set"), 2)
      assertEquals(code("--stroke", "1:brush:dye:1:1:2:smear:1"), 2)
      assertEquals(code("--stroke", "1:brush:dye:1:1:-2:set:1"), 2)
      assertEquals(code("--stroke", "1:brush:nope:1:1:2:set:1"), 2)
      assertEquals(code("--stroke", "1:pulse:dye:1:1:0:1"), 2)
      assertEquals(code("--stroke", "1:disturb:1:1:2:0.5"), 2)
      assertEquals(code("--fps", "0"), 2)
      assertEquals(code("--fps", "70000"), 2)
      assertEquals(code("--loops", "-1"), 2)
      assertEquals(go("animate")._1, 2)
      assertEquals(go("animate", png, "--sweep", "nu=1,2")._1, 2)
      // a model that takes no strokes
      assertEquals(go("run", "--steps", "3", "--stroke", "1:brush:dye:1:1:2:set:1")._1, 2)
      val (_, _, err) = go(
        "animate",
        png,
        "--steps",
        "4",
        "--stroke",
        "1:brush:nope:1:1:2:set:1",
        "--model",
        "fluid"
      )
      assert(err.contains("stroke at step 1"), err)
      // nothing was written
      assert(!Files.exists(dir.resolve("x.png")))
    }
  }

  test("a model without an image cannot be animated, and animate has its own help") {
    assertEquals(go("animate", "x.png", "--model", "lorenz", "--steps", "10")._1, 1)
    val (code, text, _) = go("animate", "--help")
    assertEquals(code, 0)
    assert(text.contains("--fps") && text.contains("--stroke"), text)
    assert(go("--help")._2.contains("animate <file>"))
  }
