package physis.core.run

/**
 * Minimal JSON tree, writer and parser for run manifests.
 *
 * Deliberately small: manifests are flat documents, and keeping the core free of dependencies
 * is worth more than a general-purpose library here. Numbers keep their source text so that
 * longs (seeds) and doubles both round-trip exactly.
 */
private[run] enum Json:
  case JNull
  case JBool(value: Boolean)
  case JNum(raw: String)
  case JStr(value: String)
  case JArr(items: Vector[Json])
  case JObj(fields: Vector[(String, Json)])

private[run] object Json:

  def num(value: Double): Json =
    require(!value.isNaN && !value.isInfinite, s"JSON cannot represent $value")
    JNum(java.lang.Double.toString(value))

  def num(value: Long): Json = JNum(value.toString)

  /** Renders with two-space indentation. */
  def render(json: Json): String =
    val sb = new java.lang.StringBuilder
    write(json, sb, 0)
    sb.toString

  def parse(text: String): Either[String, Json] =
    val parser = new Parser(text)
    try
      val value = parser.value()
      parser.skipWhitespace()
      if parser.atEnd then Right(value) else Left(s"unexpected trailing content at offset ${parser.offset}")
    catch case e: ParseError => Left(e.getMessage)

  extension (json: Json)
    def field(name: String): Either[String, Json] = json match
      case JObj(fields) => fields.collectFirst { case (`name`, v) => v }.toRight(s"missing field '$name'")
      case _ => Left(s"expected an object with field '$name'")

    def asString: Either[String, String] = json match
      case JStr(v) => Right(v)
      case other => Left(s"expected a string, got ${kind(other)}")

    def asDouble: Either[String, Double] = json match
      case JNum(raw) => raw.toDoubleOption.toRight(s"invalid number '$raw'")
      case other => Left(s"expected a number, got ${kind(other)}")

    def asLong: Either[String, Long] = json match
      case JNum(raw) => raw.toLongOption.toRight(s"expected an integer, got '$raw'")
      case other => Left(s"expected an integer, got ${kind(other)}")

    def asObject: Either[String, Vector[(String, Json)]] = json match
      case JObj(fields) => Right(fields)
      case other => Left(s"expected an object, got ${kind(other)}")

  private def kind(json: Json): String = json match
    case JNull => "null"
    case JBool(_) => "a boolean"
    case JNum(_) => "a number"
    case JStr(_) => "a string"
    case JArr(_) => "an array"
    case JObj(_) => "an object"

  private def write(json: Json, sb: java.lang.StringBuilder, indent: Int): Unit =
    json match
      case JNull => sb.append("null")
      case JBool(v) => sb.append(if v then "true" else "false")
      case JNum(raw) => sb.append(raw)
      case JStr(v) => quote(v, sb)
      case JArr(items) =>
        if items.isEmpty then sb.append("[]")
        else
          sb.append("[\n")
          items.zipWithIndex.foreach { (item, i) =>
            pad(sb, indent + 1)
            write(item, sb, indent + 1)
            sb.append(if i < items.size - 1 then ",\n" else "\n")
          }
          pad(sb, indent)
          sb.append(']')
      case JObj(fields) =>
        if fields.isEmpty then sb.append("{}")
        else
          sb.append("{\n")
          fields.zipWithIndex.foreach { case ((name, value), i) =>
            pad(sb, indent + 1)
            quote(name, sb)
            sb.append(": ")
            write(value, sb, indent + 1)
            sb.append(if i < fields.size - 1 then ",\n" else "\n")
          }
          pad(sb, indent)
          sb.append('}')
    ()

  private def pad(sb: java.lang.StringBuilder, indent: Int): Unit =
    var i = 0
    while i < indent do
      sb.append("  ")
      i += 1

  private def quote(s: String, sb: java.lang.StringBuilder): Unit =
    sb.append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      c match
        case '"' => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case _ if c < ' ' => sb.append(f"\\u${c.toInt}%04x")
        case _ => sb.append(c)
      i += 1
    sb.append('"')
    ()

  private final class ParseError(message: String) extends RuntimeException(message)

  private final class Parser(text: String):
    private var pos = 0

    def offset: Int = pos
    def atEnd: Boolean = pos >= text.length

    private def fail(message: String): Nothing = throw new ParseError(s"$message at offset $pos")

    def skipWhitespace(): Unit =
      while pos < text.length && " \t\r\n".indexOf(text.charAt(pos).toInt) >= 0 do pos += 1

    private def peek: Char =
      if pos >= text.length then fail("unexpected end of input")
      text.charAt(pos)

    private def expect(c: Char): Unit =
      skipWhitespace()
      if peek != c then fail(s"expected '$c'")
      pos += 1

    def value(): Json =
      skipWhitespace()
      peek match
        case '{' => obj()
        case '[' => arr()
        case '"' => JStr(str())
        case 't' => literal("true", JBool(true))
        case 'f' => literal("false", JBool(false))
        case 'n' => literal("null", JNull)
        case c if c == '-' || (c >= '0' && c <= '9') => number()
        case c => fail(s"unexpected character '$c'")

    private def literal(word: String, result: Json): Json =
      if !text.startsWith(word, pos) then fail(s"expected '$word'")
      pos += word.length
      result

    private def obj(): Json =
      pos += 1
      val fields = Vector.newBuilder[(String, Json)]
      skipWhitespace()
      if peek == '}' then pos += 1
      else
        var more = true
        while more do
          skipWhitespace()
          if peek != '"' then fail("expected a field name")
          val name = str()
          expect(':')
          val v = value()
          fields.addOne(name -> v)
          skipWhitespace()
          if peek == ',' then pos += 1
          else
            expect('}')
            more = false
      JObj(fields.result())

    private def arr(): Json =
      pos += 1
      val items = Vector.newBuilder[Json]
      skipWhitespace()
      if peek == ']' then pos += 1
      else
        var more = true
        while more do
          items.addOne(value())
          skipWhitespace()
          if peek == ',' then pos += 1
          else
            expect(']')
            more = false
      JArr(items.result())

    private def str(): String =
      pos += 1 // opening quote
      val sb = new java.lang.StringBuilder
      var closed = false
      while !closed do
        val c = peek
        pos += 1
        if c == '"' then closed = true
        else if c == '\\' then
          val e = peek
          pos += 1
          e match
            case '"' => sb.append('"')
            case '\\' => sb.append('\\')
            case '/' => sb.append('/')
            case 'b' => sb.append('\b')
            case 'f' => sb.append('\f')
            case 'n' => sb.append('\n')
            case 'r' => sb.append('\r')
            case 't' => sb.append('\t')
            case 'u' =>
              if pos + 4 > text.length then fail("truncated unicode escape")
              val hex = text.substring(pos, pos + 4)
              val code =
                try Integer.parseInt(hex, 16)
                catch case _: NumberFormatException => fail(s"invalid unicode escape '$hex'")
              sb.append(code.toChar)
              pos += 4
            case other => fail(s"invalid escape '\\$other'")
          ()
        else
          sb.append(c)
          ()
      sb.toString

    private def number(): Json =
      val start = pos
      while pos < text.length && "+-0123456789.eE".indexOf(text.charAt(pos).toInt) >= 0 do pos += 1
      val raw = text.substring(start, pos)
      if raw.toDoubleOption.isEmpty then fail(s"invalid number '$raw'")
      JNum(raw)
