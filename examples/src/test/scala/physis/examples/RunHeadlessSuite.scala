package physis.examples

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class RunHeadlessSuite extends munit.FunSuite:

  private def capture(args: String*): (Int, String, String) =
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = RunHeadless.parse(args.toList) match
      case Right(options) =>
        RunHeadless.run(
          options,
          new PrintStream(out, true, StandardCharsets.UTF_8),
          new PrintStream(err, true, StandardCharsets.UTF_8)
        )
      case Left(error) => fail(s"parse failed: $error")
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  test("prints Lorenz probes as CSV") {
    val (code, csv, summary) = capture("--model", "lorenz", "--until", "1", "--sample-every", "0.5")
    assertEquals(code, 0)
    val lines = csv.linesIterator.toList
    assertEquals(lines.head, "t,x,y,z")
    assertEquals(lines.tail.map(_.takeWhile(_ != ',')), List("0.0", "0.5", "1.0"))
    assert(summary.contains("final digest"), summary)
  }

  test("parameters are overridden by name") {
    val (code, csv, _) =
      capture(
        "--model",
        "double-pendulum",
        "--param",
        "theta1=0",
        "--param",
        "theta2=0",
        "--until",
        "0"
      )
    assertEquals(code, 0)
    assertEquals(csv.linesIterator.toList.head, "t,energy,energyDrift,x2,y2")
    assert(csv.linesIterator.toList(1).endsWith(",0.0,-2.0"), csv)
  }

  test("a replayed manifest prints the same CSV and digest") {
    val path = Files.createTempFile("physis-run", ".json")
    try
      val (code, csv, summary) = capture(
        "--model",
        "double-pendulum",
        "--adaptive",
        "1e-9",
        "--until",
        "2",
        "--seed",
        "4",
        "--manifest",
        path.toString
      )
      assertEquals(code, 0)
      val (replayCode, replayCsv, replaySummary) = capture("--replay", path.toString)
      assertEquals(replayCode, 0)
      assertEquals(replayCsv, csv)
      assertEquals(replaySummary, summary)
    finally Files.delete(path)
  }

  test("argument errors are reported") {
    assert(RunHeadless.parse(List("--until", "soon")).isLeft)
    assert(RunHeadless.parse(List("--param", "theta1")).isLeft)
    assert(RunHeadless.parse(List("--bogus")).isLeft)
    assertEquals(RunHeadless.parse(List("--help")), Left("help"))
  }

  test("unknown models and invalid parameters fail with exit code 1") {
    assertEquals(capture("--model", "nope")._1, 1)
    assertEquals(capture("--param", "rho=-1")._1, 1)
  }
