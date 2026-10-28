package physis.netcdf

/** The types of a classic NetCDF file that Physis writes: text, 32-bit integers, floats and doubles. */
enum NcType(val code: Int, val size: Int):
  /** Characters; only attributes use them. */
  case Text extends NcType(2, 1)

  /** 32-bit integers, big endian. */
  case Int32 extends NcType(4, 4)

  /** 32-bit floats, big endian. */
  case Float32 extends NcType(5, 4)

  /** 64-bit floats, big endian. */
  case Float64 extends NcType(6, 8)

/** The value of an attribute: a text, or a list of numbers. */
enum AttrValue:
  case Text(value: String)
  case Ints(values: Vector[Int])
  case Doubles(values: Vector[Double])

/** A named attribute of a file or of a variable. */
final case class Attr(name: String, value: AttrValue)

object Attr:
  def text(name: String, value: String): Attr = Attr(name, AttrValue.Text(value))
  def int(name: String, value: Int): Attr = Attr(name, AttrValue.Ints(Vector(value)))
  def double(name: String, value: Double): Attr = Attr(name, AttrValue.Doubles(Vector(value)))

/**
 * A dimension. `size = None` is the record (unlimited) dimension, along which a file grows; in
 * Physis it is always time.
 */
final case class Dim(name: String, size: Option[Int]):
  def isRecord: Boolean = size.isEmpty

object Dim:
  def fixed(name: String, size: Int): Dim = Dim(name, Some(size))
  def record(name: String): Dim = Dim(name, None)

/**
 * A variable: its dimensions by name, outermost first (the record dimension, if it has one, must
 * be the first), its type and its attributes.
 */
final case class Var(
    name: String,
    dims: Vector[String],
    tpe: NcType,
    attrs: Vector[Attr] = Vector.empty
):
  /** The attribute called `key`, if the variable has it. */
  def attr(key: String): Option[AttrValue] = attrs.find(_.name == key).map(_.value)

/**
 * The structure of a file: dimensions, global attributes and variables, in the order they are
 * written. [[validate]] checks what the format requires.
 */
final case class Schema(
    dims: Vector[Dim],
    globals: Vector[Attr],
    vars: Vector[Var]
):
  /** The record dimension, if there is one. */
  def recordDim: Option[Dim] = dims.find(_.isRecord)

  def dim(name: String): Option[Dim] = dims.find(_.name == name)

  def variable(name: String): Option[Var] = vars.find(_.name == name)

  /** Whether `v` grows along the record dimension. */
  def isRecord(v: Var): Boolean = v.dims.headOption.exists(d => dim(d).exists(_.isRecord))

  /** Variables with a record dimension, in file order. */
  def recordVars: Vector[Var] = vars.filter(isRecord)

  /** Variables without one, in file order. */
  def fixedVars: Vector[Var] = vars.filterNot(isRecord)

  /** The number of values of `v` in one record, or in all of the file if it is not a record variable. */
  def valuesPerRecord(v: Var): Int =
    val inner = if isRecord(v) then v.dims.drop(1) else v.dims
    inner.map(d => dim(d).flatMap(_.size).getOrElse(1)).product

  /** `Right(this)` if the structure can be written as a classic NetCDF file. */
  def validate: Either[String, Schema] =
    val names = dims.map(_.name)
    val varNames = vars.map(_.name)
    val problems =
      (names ++ varNames).filterNot(Schema.validName).map(n => s"bad name '$n'") ++
        names.diff(names.distinct).distinct.map(n => s"dimension '$n' twice") ++
        varNames.diff(varNames.distinct).distinct.map(n => s"variable '$n' twice") ++
        (if dims.count(_.isRecord) > 1 then Vector("more than one record dimension")
         else Vector.empty) ++
        dims.collect { case Dim(n, Some(s)) if s < 1 => s"dimension '$n' has size $s" } ++
        vars.flatMap(variableProblems) ++
        Schema.attrProblems("file", globals)
    problems.headOption.toLeft(this)

  private def variableProblems(v: Var): Vector[String] =
    v.dims.filter(d => dim(d).isEmpty).map(d => s"'${v.name}': no dimension '$d'") ++
      (if v.dims.drop(1).exists(d => dim(d).exists(_.isRecord)) then
         Vector(s"'${v.name}': the record dimension must come first")
       else Vector.empty) ++
      (if v.dims.distinct.size != v.dims.size then Vector(s"'${v.name}': a dimension twice")
       else Vector.empty) ++
      (if v.tpe == NcType.Text then Vector(s"'${v.name}': variables are numbers, not text")
       else Vector.empty) ++
      Schema.attrProblems(v.name, v.attrs)

object Schema:
  /** NetCDF names: a letter or underscore, then letters, digits and `_ . @ + -`. */
  def validName(name: String): Boolean =
    name.nonEmpty && name.length <= 255 &&
      (name.head.isLetter && name.head < 128 || name.head == '_') &&
      name.forall(c => c < 128 && (c.isLetterOrDigit || "_.@+-".contains(c)))

  private def attrProblems(owner: String, attrs: Vector[Attr]): Vector[String] =
    val names = attrs.map(_.name)
    names.diff(names.distinct).distinct.map(n => s"$owner: attribute '$n' twice") ++
      attrs.flatMap { a =>
        (if validName(a.name) then Vector.empty
         else Vector(s"$owner: bad attribute name '${a.name}'")) ++
          (a.value match
            case AttrValue.Ints(v) if v.isEmpty => Vector(s"$owner: '${a.name}' is empty")
            case AttrValue.Doubles(v) if v.isEmpty => Vector(s"$owner: '${a.name}' is empty")
            case _ => Vector.empty)
      }
