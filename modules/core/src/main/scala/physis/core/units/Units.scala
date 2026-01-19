package physis.core.units

/*
 * Physical units as opaque types over Double.
 *
 * They exist only at compile time: in bytecode every value is a plain double,
 * so they cost nothing. Use them in public APIs and parameters to catch unit
 * mix-ups; numerical inner loops work on raw doubles.
 */

/** A duration in seconds. */
opaque type Seconds = Double

object Seconds:
  inline def apply(value: Double): Seconds = value
  extension (s: Seconds) inline def toDouble: Double = s

/** A length in metres. */
opaque type Meters = Double

object Meters:
  inline def apply(value: Double): Meters = value
  extension (m: Meters) inline def toDouble: Double = m

/** A mass in kilograms. */
opaque type Kilograms = Double

object Kilograms:
  inline def apply(value: Double): Kilograms = value
  extension (k: Kilograms) inline def toDouble: Double = k

/** An acceleration in metres per second squared. */
opaque type MetersPerSecond2 = Double

object MetersPerSecond2:
  inline def apply(value: Double): MetersPerSecond2 = value
  extension (a: MetersPerSecond2) inline def toDouble: Double = a

/** An angle in radians. */
opaque type Radians = Double

object Radians:
  inline def apply(value: Double): Radians = value

  /** Converts an angle given in degrees. */
  inline def fromDegrees(degrees: Double): Radians = math.toRadians(degrees)

  extension (r: Radians)
    inline def toDouble: Double = r
    inline def toDegrees: Double = math.toDegrees(r)

/** Literal-style constructors: `0.01.s`, `2.0.m`, `1.5.kg`, `9.81.mps2`, `120.0.deg`, `0.5.rad`. */
extension (value: Double)
  inline def s: Seconds = Seconds(value)
  inline def m: Meters = Meters(value)
  inline def kg: Kilograms = Kilograms(value)
  inline def mps2: MetersPerSecond2 = MetersPerSecond2(value)
  inline def rad: Radians = Radians(value)
  inline def deg: Radians = Radians.fromDegrees(value)
