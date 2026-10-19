package physis.view

import java.io.IOException
import java.nio.file.{Files, Path}

import physis.core.live.{Snapshot, StateExport}
import physis.core.run.Run

/**
 * Turns a run into a numbered sequence of PNG files: the first image of a view (a heatmap, with
 * agent dots when the view has them) every so many steps, without any toolkit. A run rebuilt
 * from a manifest takes its parameter changes and strokes at the steps they were made, so the
 * frames show a painted session as it went; the same manifest gives the same bytes every time.
 */
object Frames:

  /**
   * @param every    steps between two frames; the first frame is the state before any step
   * @param factor   pixels per cell in each direction, 1 to 16
   * @param colorBar whether the colour scale is drawn at the right of each frame
   * @param compress whether the PNG data is deflated (smaller files) or stored (see [[Png]])
   * @param prefix   start of the file names: `prefix-00000.png`, `prefix-00001.png`, ...
   */
  final case class Options(
      every: Long,
      factor: Int = 1,
      colorBar: Boolean = true,
      compress: Boolean = false,
      prefix: String = "frame"
  ):
    require(every >= 1, s"frames: 'every' must be at least 1, got $every")
    require(factor >= 1 && factor <= 16, s"frames: factor must be 1 to 16, got $factor")
    require(prefix.nonEmpty && !prefix.exists(c => c == '/' || c == '\\'), "frames: bad prefix")

  /** The first image of `view`, searching layouts depth first: a heatmap and its dots, if any. */
  def imageOf(view: View): Option[(View.Heatmap, Option[Dots])] = view match
    case h: View.Heatmap => Some((h, None))
    case View.Layers(base, dots) => Some((base, Some(dots)))
    case View.Layout(views, _) => views.iterator.flatMap(v => imageOf(v)).nextOption()
    case _ => None

  /** The state of `run` as a snapshot, the way a live run would export it. */
  def snapshot[S](run: Run[?, S], exporter: StateExport[S]): Snapshot =
    val s = new Snapshot(exporter.size(run.state), 0)
    exporter.copyInto(run.state, s.state)
    s.slices = exporter.layout(run.state)
    s.step = run.stepCount
    s.time = run.time
    s

  /** The picture of the first image of `view` in `s`, with the colour scale if asked for. */
  def frame(s: Snapshot, view: View, options: Options): Option[Frame] =
    imageOf(view).flatMap { (heatmap, dots) =>
      FrameImage.heatmap(s, heatmap, dots).map { f =>
        if options.colorBar then FrameImage.withColorBar(f, heatmap.colormap) else f
      }
    }

  /**
   * Runs `run` on to step `steps` and writes a frame into `dir` (made if needed) before the
   * first step and after every `every`-th step, numbered from 0. Returns the files in order.
   * Frames at a step `steps` is not a multiple of `every` away are not written.
   */
  def render[P, S](
      run: Run[P, S],
      steps: Long,
      exporter: StateExport[S],
      view: View,
      dir: Path,
      options: Options
  ): Either[String, Vector[Path]] =
    if imageOf(view).isEmpty then Left("the view has no image to export")
    else
      val written = Vector.newBuilder[Path]
      var count = 0
      var failure: Option[String] = None
      def write(): Unit =
        frame(snapshot(run, exporter), view, options) match
          case None => failure = Some("the state has no slice for the image")
          case Some(f) =>
            val path = dir.resolve("%s-%05d.png".format(options.prefix, count))
            Png.write(path, f.pixels, f.width, f.height, options.factor, options.compress)
            written += path
            count += 1
      try
        Files.createDirectories(dir)
        write()
        while failure.isEmpty && run.stepCount < steps do
          run.step()
          if run.stepCount % options.every == 0 then write()
        failure.toLeft(written.result())
      catch case e: IOException => Left(s"cannot write frames to $dir: ${e.getMessage}")
