package physis.view

/** Lays pictures of equal size out in a grid, as a contact sheet of a sweep. */
object Montage:

  /**
   * One picture from `tiles`, row by row from the top left with `columns` to a row and `gap`
   * pixels between neighbours (and in the background colour). A `None` tile (a run that failed)
   * stays blank. All pictures must have the same size. The range of the result runs from the
   * smallest to the largest range of the tiles; each tile keeps its own colours.
   */
  def tile(
      tiles: Vector[Option[Frame]],
      columns: Int,
      gap: Int = 1,
      background: Int = 0xff202020
  ): Either[String, Frame] =
    if columns < 1 then Left(s"a montage needs at least one column, got $columns")
    else if gap < 0 then Left(s"the gap must not be negative, got $gap")
    else
      val present = tiles.flatten
      present.headOption match
        case None => Left("no picture to put in the montage")
        case Some(first) =>
          val w = first.width
          val h = first.height
          if present.exists(f => f.width != w || f.height != h) then
            Left("the pictures differ in size, so they cannot be tiled")
          else
            val cols = math.min(columns, tiles.size)
            val rows = (tiles.size + cols - 1) / cols
            val width = cols * w + (cols - 1) * gap
            val height = rows * h + (rows - 1) * gap
            val out = new Array[Int](width * height)
            java.util.Arrays.fill(out, background)
            for
              (tile, k) <- tiles.zipWithIndex
              f <- tile
            do
              val x0 = (k % cols) * (w + gap)
              val y0 = (k / cols) * (h + gap)
              var y = 0
              while y < h do
                System.arraycopy(f.pixels, y * w, out, (y0 + y) * width + x0, w)
                y += 1
            Right(new Frame(width, height, out, present.map(_.lo).min, present.map(_.hi).max))
