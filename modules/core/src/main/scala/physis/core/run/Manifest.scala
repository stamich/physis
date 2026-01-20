package physis.core.run

import scala.collection.immutable.VectorMap

import physis.core.{BuildInfo, Model, Stepping}

/**
 * Everything needed to reproduce a run: framework and model versions, parameters, stepping,
 * seed and duration. Written next to every output; [[Manifest.replay]] re-creates the run.
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
    git: Option[String]
)

/** A run re-created from a manifest, ready to execute to the manifest's end time. */
final case class Replay(run: Run[?, ?], until: Double):
  def execute(): RunResult = run.runUntil(until)

object Manifest:

  /** Manifest describing `run` executed until `until`. */
  def of(run: Run[?, ?], until: Double): Manifest = describe(run.asInstanceOf[Run[Any, Any]], until)

  private def describe[P, S](run: Run[P, S], until: Double): Manifest =
    Manifest(
      physis = BuildInfo.version,
      model = run.model.id,
      modelVersion = run.model.version,
      params = run.model.params.toMap(run.params),
      stepping = run.stepping,
      seed = run.seed,
      until = until,
      sampleEvery = run.sampleEvery,
      git = BuildInfo.git
    )

  def toJson(m: Manifest): String =
    Json.render(
      Json.JObj(
        Vector(
          "physis" -> Json.JStr(m.physis),
          "model" -> Json.JStr(m.model),
          "modelVersion" -> Json.JStr(m.modelVersion),
          "params" -> Json.JObj(m.params.toVector.map((k, v) => k -> Json.num(v))),
          "stepping" -> steppingToJson(m.stepping),
          "seed" -> Json.num(m.seed),
          "until" -> Json.num(m.until),
          "sampleEvery" -> Json.num(m.sampleEvery),
          "git" -> m.git.fold(Json.JNull)(Json.JStr(_))
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
    yield Manifest(physis, model, modelVersion, params, stepping, seed, until, sampleEvery, git)

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
          .build()
        Right(Replay(run, m.until))
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

  private def optionalString(json: Json): Either[String, Option[String]] = json match
    case Json.JNull => Right(None)
    case other => other.asString.map(Some(_))
