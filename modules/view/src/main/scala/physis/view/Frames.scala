package physis.view

import java.io.{BufferedOutputStream, IOException, OutputStream}
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
   * @param fps      frames per second of an animation (see [[animation]])
   * @param loops    how often an animation plays; 0 is for ever
   */
  final case class Options(
      every: Long,
      factor: Int = 1,
      colorBar: Boolean = true,
      compress: Boolean = false,
      prefix: String = "frame",
      fps: Int = 12,
      loops: Int = 0
  ):
    require(every >= 1, s"frames: 'every' must be at least 1, got $every")
    require(fps >= 1 && fps <= 65535, s"frames: fps must be 1 to 65535, got $fps")
    require(loops >= 0, s"frames: loops must not be negative, got $loops")
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
   * Runs `run` on to step `steps`, handing `emit` the picture before the first step and after
   * every `every`-th step. Returns why it had to stop, if it did.
   */
  private def eachFrame[P, S](
      run: Run[P, S],
      steps: Long,
      exporter: StateExport[S],
      view: View,
      options: Options,
      cancelled: () => Boolean = () => false
  )(emit: Frame => Unit): Option[String] =
    if imageOf(view).isEmpty then Some("the view has no image to export")
    else
      var failure: Option[String] = None
      def next(): Unit =
        frame(snapshot(run, exporter), view, options) match
          case None => failure = Some("the state has no slice for the image")
          case Some(f) => emit(f)
      next()
      while failure.isEmpty && run.stepCount < steps do
        if cancelled() then failure = Some(Cancelled)
        else
          run.step()
          if run.stepCount % options.every == 0 then next()
      if failure.isEmpty && cancelled() then Some(Cancelled) else failure

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
    val written = Vector.newBuilder[Path]
    var count = 0
    try
      Files.createDirectories(dir)
      eachFrame(run, steps, exporter, view, options) { f =>
        val path = dir.resolve("%s-%05d.png".format(options.prefix, count))
        Png.write(path, f.pixels, f.width, f.height, options.factor, options.compress)
        written += path
        count += 1
      }.toLeft(written.result())
    catch case e: IOException => Left(s"cannot write frames to $dir: ${e.getMessage}")

  /** The message of a run that was stopped by its cancel flag. */
  val Cancelled: String = "cancelled"

  /**
   * How many frames a run that is now at step `from` writes up to step `steps`: the one now,
   * and one at every multiple of `every` after it.
   */
  def frameCount(from: Long, steps: Long, every: Long): Int =
    require(every >= 1, s"frames: 'every' must be at least 1, got $every")
    if steps <= from then 1 else (1L + steps / every - from / every).toInt

  /**
   * The same frames as [[render]], as one animated PNG at `path` (see [[Apng]]): shown at
   * `options.fps` frames per second and played `options.loops` times. The frames are written one
   * at a time (see [[Apng.Writer]]), so memory holds one frame whatever the length. The bytes are
   * those of 0.6.0, which collected the frames first.
   *
   * `progress` is called with (frames written, frames in all) after every frame; when
   * `cancelled` turns true the run stops before its next step, the file is removed and the
   * result is `Left(Cancelled)`. A file is also removed when anything else goes wrong.
   * Returns the number of frames.
   */
  def animation[P, S](
      run: Run[P, S],
      steps: Long,
      exporter: StateExport[S],
      view: View,
      path: Path,
      options: Options,
      progress: (Int, Int) => Unit = (_, _) => (),
      cancelled: () => Boolean = () => false
  ): Either[String, Int] =
    val total = frameCount(run.stepCount, steps, options.every)
    var out: OutputStream = null
    var writer: Apng.Writer = null
    var written = 0
    var created = false
    def drop(): Unit =
      if out != null then
        try out.close()
        catch case _: IOException => ()
        out = null
      if created then
        try
          val _ = Files.deleteIfExists(path)
        catch case _: IOException => ()
    try
      val result = eachFrame(run, steps, exporter, view, options, cancelled) { f =>
        if writer == null then
          Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
          out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 16)
          created = true
          writer = new Apng.Writer(
            out,
            f.width * options.factor,
            f.height * options.factor,
            total,
            options.fps,
            options.loops,
            alpha = options.colorBar,
            compress = options.compress
          )
        writer.add(Png.scale(f.pixels, f.width, f.height, options.factor))
        written += 1
        progress(written, total)
      }
      result match
        case Some(why) =>
          drop()
          Left(why)
        case None =>
          if writer == null || written != total then
            drop()
            Left(s"wrote $written of $total frames")
          else
            writer.finish()
            out.close()
            Right(written)
    catch
      case e: IOException =>
        drop()
        Left(s"cannot write the animation to $path: ${e.getMessage}")
      case e: IllegalArgumentException =>
        drop()
        Left(s"cannot write the animation: ${e.getMessage}")
