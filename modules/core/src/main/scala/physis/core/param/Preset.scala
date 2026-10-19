package physis.core.param

/**
 * A named set of parameter values that belongs together (the "Mitosis" regime of Gray-Scott, the
 * "Flock" setting of Boids). Applying a preset is an ordinary parameter change, so it is
 * validated by the schema and recorded as one event in the manifest.
 *
 * @param name   short display name
 * @param values the parameters the preset sets; all others keep their current value
 * @param doc    one-line description
 */
final case class Preset(name: String, values: Map[String, Double], doc: String = "")
