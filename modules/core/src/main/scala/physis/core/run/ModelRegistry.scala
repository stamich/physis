package physis.core.run

import physis.core.Model

/** Models available for replay, by id. */
final class ModelRegistry private (models: Map[String, Model[?]]):
  def get(id: String): Option[Model[?]] = models.get(id)
  def ids: List[String] = models.keys.toList.sorted

object ModelRegistry:
  /** A registry of distinct model ids. */
  def of(models: Model[?]*): ModelRegistry =
    val ids = models.map(_.id)
    require(ids.distinct.size == ids.size, s"duplicate model ids: ${ids.mkString(", ")}")
    new ModelRegistry(models.map(m => m.id -> m).toMap)
