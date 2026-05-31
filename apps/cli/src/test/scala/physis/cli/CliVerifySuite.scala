package physis.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class CliVerifySuite extends munit.FunSuite:
  private def go(args: String*): (Int, String, String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = Cli.execute(
      args.toList,
      new PrintStream(out, true, StandardCharsets.UTF_8),
      new PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("physis-verify-test"),
    teardown = dir =>
      val stream = Files.walk(dir)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
      finally stream.close()
  )

  private val shallow =
    Seq("--model", "shallow", "--param", "nx=24", "--param", "ny=16", "--steps", "20")

  tmp.test("a dataset verifies against a replay of its own manifest") { dir =>
    val file = dir.resolve("run.nc").toString
    val (c, _, e) = go(("export" +: file +: shallow)*)
    assertEquals(c, 0, e)
    val (code, out, err) = go("verify", file)
    assertEquals(code, 0, err)
    assert(out.startsWith("verified: "), out)
  }

  tmp.test("single precision and a variable choice are replayed as written") { dir =>
    val file = dir.resolve("run.nc").toString
    val (c, _, e) = go(("export" +: file +: (shallow ++ Seq("--float", "--vars", "height")))*)
    assertEquals(c, 0, e)
    val (code, _, err) = go("verify", file)
    assertEquals(code, 0, err)
  }

  tmp.test("a changed value is found and named") { dir =>
    val file = dir.resolve("run.nc")
    assertEquals(go(("export" +: file.toString +: shallow)*)._1, 0)
    val bytes = Files.readAllBytes(file)
    bytes(bytes.length - 3) = (bytes(bytes.length - 3) ^ 0x40).toByte
    Files.write(file, bytes)
    val (code, _, err) = go("verify", file.toString)
    assertEquals(code, 1)
    assert(err.contains("DIFFERS") && err.contains("differs at"), err)
  }

  tmp.test("a file that cannot be checked is an error with a reason") { dir =>
    val junk = dir.resolve("junk.nc")
    Files.write(junk, Array.fill[Byte](64)(1))
    val (c1, _, e1) = go("verify", junk.toString)
    assertEquals(c1, 1)
    assert(e1.startsWith("error: "), e1)
    val (c2, _, e2) = go("verify", dir.resolve("none.nc").toString)
    assertEquals(c2, 1)
    assert(e2.startsWith("error: "), e2)
  }

  test("verify takes exactly one file") {
    assertEquals(go("verify")._1, 2)
    assertEquals(go("verify", "a.nc", "b.nc")._1, 2)
  }
