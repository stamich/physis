package physis.core.run

import scala.collection.immutable.VectorMap

import physis.core.{BuildInfo, Model, Stepping}

/**
 * Everything needed to reproduce a run: framework and model versions, parameters, stepping,
 * seed, parameter changes and duration. Written next to every output; [[Manifest.replay]]
 * re-creates the run.
 *
 * JSON format 2 adds `events`, `steps` and `digest`; format-1 manifests (Physis 0.1) still read,
 * with no events.
 *
 * @param physis       framework version that produced the run
 * @param model        model id, resolved through a [[ModelRegistry]]
 * @param modelVersion model version; replay refuses a different one
 * @param params       named parameter values, in schema order
 * @param stepping     stepping policy
 * @param seed         run seed
 * @param until        end time of the run
 * @param sampleEvery  probe sampling interval
 * @param git          source revision of the framework build, when known
 * @param events       parameter changes made while the run was in progress
 * @param steps        the exact number of whole steps taken; replay then takes the same steps
 *                     instead of running to `until` with sample landing. Written by live
 *                     sessions; needed for a bit-exact replay whenever parameters changed
 *                     between sample times, since landing on other times changes step sizes
 * @param digest       final state digest recorded when the manifest was written, if known
 */
final case class Manifest(
    physis: String,
    model: String,
    modelVersion: String,
    params: VectorMap[String, Double],
    stepping: Stepping,
    seed: Long,
    until: Double,
    sampleEvery: Double,
    git: Option[String],
    events: Vector[ParamEvent] = Vector.empty,
    steps: Option[Long] = None,
    digest: Option[Long] = None
)

/** A run re-created from a manifest, ready to execute to the manifest's end. */
final case class Replay(run: Run[?, ?], until: Double, steps: Option[Long] = None):
  def execute(): RunResult = steps match
    case Some(count) => run.runSteps(count)
    case None => run.runUntil(until)

object Manifest:

  /** Manifest describing `run` executed until `until`. */
  def of(run: Run[?, ?], until: Double): Manifest = describe(run.asInstanceOf[Run[Any, Any]], until)

  private def describe[P, S](run: Run[P, S], until: Double): Manifest =
    Manifest(
      physis = BuildInfo.version,
      model = run.model.id,
      modelVersion = run.model.version,
      params = run.model.params.toMap(run.initialParams),
      stepping = run.stepping,
      seed = run.seed,
      until = until,
      sampleEvery = run.sampleEvery,
      git = BuildInfo.git,
      events = run.events
    )

  /** Version of the JSON layout written by [[toJson]]. */
  val Format = 2

  def toJson(m: Manifest): String =
    Json.render(
      Json.JObj(
        Vector(
          "format" -> Json.num(Format.toLong),
          "physis" -> Json.JStr(m.physis),
          "model" -> Json.JStr(m.model),
          "modelVersion" -> Json.JStr(m.modelVersion),
          "params" -> Json.JObj(m.params.toVector.map((k, v) => k -> Json.num(v))),
          "stepping" -> steppingToJson(m.stepping),
          "seed" -> Json.num(m.seed),
          "until" -> Json.num(m.until),
          "sampleEvery" -> Json.num(m.sampleEvery),
          "git" -> m.git.fold(Json.JNull)(Json.JStr(_)),
          "events" -> Json.JArr(m.events.map(eventToJson)),
          "steps" -> m.steps.fold(Json.JNull)(Json.num(_)),
          "digest" -> m.digest.fold(Json.JNull)(Json.num(_))
        )
      )
    ) + "\n"

  def fromJson(text: String): Either[String, Manifest] =
    for
      root <- Json.parse(text)
      physis <- root.field("physis").flatMap(_.asString)
      model <- root.field("model").flatMap(_.asString)
      modelVersion <- root.field("modelVersion").flatMap(_.asString)
      params <- root.field("params").flatMap(paramsFromJson)
      stepping <- root.field("stepping").flatMap(steppingFromJson)
      seed <- root.field("seed").flatMap(_.asLong)
      until <- root.field("until").flatMap(_.asDouble)
      sampleEvery <- root.field("sampleEvery").flatMap(_.asDouble)
      git <- root.field("git").flatMap(optionalString)
      format <- root.optionalField("format").flatMap(optionalLong)
      _ <- checkFormat(format)
      events <- root.optionalField("events").flatMap(eventsFromJson)
      steps <- root.optionalField("steps").flatMap(optionalLong)
      digest <- root.optionalField("digest").flatMap(optionalLong)
    yield Manifest(
      physis,
      model,
      modelVersion,
      params,
      stepping,
      seed,
      until,
      sampleEvery,
      git,
      events,
      steps,
      digest
    )

  /**
   * Re-creates the run described by `manifest`. Fails if the model is unknown, its version
   * differs, or the parameters or stepping are not valid for it.
   */
  def replay(manifest: Manifest, registry: ModelRegistry): Either[String, Replay] =
    registry.get(manifest.model) match
      case None => Left(s"unknown model '${manifest.model}'")
      case Some(model) if model.version != manifest.modelVersion =>
        Left(
          s"model '${manifest.model}' is version ${model.version}, manifest needs ${manifest.modelVersion}"
        )
      case Some(model) => rebuild(model.asInstanceOf[Model[Any]], manifest)

  private def rebuild(model: Model[Any], m: Manifest): Either[String, Replay] =
    model.params.fromValues(m.params).flatMap { p =>
      try
        val run = Physis
          .model(model)
          .withParams(p)
          .stepping(m.stepping)
          .seed(m.seed)
          .sampleEvery(m.sampleEvery)
          .events(m.events)
          .build()
        Right(Replay(run, m.until, m.steps))
      catch case e: IllegalArgumentException => Left(e.getMessage)
    }

  private def steppingToJson(s: Stepping): Json = s match
    case Stepping.Fixed(dt) =>
      Json.JObj(Vector("kind" -> Json.JStr("fixed"), "dt" -> Json.num(dt)))
    case Stepping.Adaptive(rtol, atol, initialDt, maxDt) =>
      Json.JObj(
        Vector(
          "kind" -> Json.JStr("adaptive"),
          "rtol" -> Json.num(rtol),
          "atol" -> Json.num(atol),
          "initialDt" -> Json.num(initialDt),
          "maxDt" -> Json.num(maxDt)
        )
      )

  private def steppingFromJson(json: Json): Either[String, Stepping] =
    json.field("kind").flatMap(_.asString).flatMap {
      case "fixed" => json.field("dt").flatMap(_.asDouble).map(Stepping.Fixed(_))
      case "adaptive" =>
        for
          rtol <- json.field("rtol").flatMap(_.asDouble)
          atol <- json.field("atol").flatMap(_.asDouble)
          initialDt <- json.field("initialDt").flatMap(_.asDouble)
          maxDt <- json.field("maxDt").flatMap(_.asDouble)
        yield Stepping.Adaptive(rtol, atol, initialDt, maxDt)
      case other => Left(s"unknown stepping kind '$other'")
    }

  private def paramsFromJson(json: Json): Either[String, VectorMap[String, Double]] =
    json.asObject.flatMap { fields =>
      fields.foldLeft[Either[String, VectorMap[String, Double]]](Right(VectorMap.empty)) {
        case (acc, (name, value)) =>
          acc.flatMap(m => value.asDouble.map(d => m.updated(name, d)))
      }
    }

  private def checkFormat(format: Option[Long]): Either[String, Unit] =
    format match
      case Some(f) if f < 1 || f > Format => Left(s"unsupported manifest format $f")
      case _ => Right(())

  private def eventToJson(e: ParamEvent): Json =
    Json.JObj(
      Vector(
        "step" -> Json.num(e.step),
        "values" -> Json.JObj(e.values.toVector.map((k, v) => k -> Json.num(v)))
      )
    )

  private def eventsFromJson(json: Option[Json]): Either[String, Vector[ParamEvent]] =
    json match
      case None | Some(Json.JNull) => Right(Vector.empty)
      case Some(array) =>
        array.asArray.flatMap { items =>
          items.foldLeft[Either[String, Vector[ParamEvent]]](Right(Vector.empty)) { (acc, item) =>
            for
              events <- acc
              step <- item.field("step").flatMap(_.asLong)
              values <- item.field("values").flatMap(paramsFromJson)
            yield events :+ ParamEvent(step, values)
          }
        }

  private def optionalLong(json: Option[Json]): Either[String, Option[Long]] = json match
    case None | Some(Json.JNull) => Right(None)
    case Some(value) => value.asLong.map(Some(_))

  private def optionalString(json: Json): Either[String, Option[String]] = json match
    case Json.JNull => Right(None)
    case other => other.asString.map(Some(_))
