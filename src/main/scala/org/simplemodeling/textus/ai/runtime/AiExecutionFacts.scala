package org.simplemodeling.textus.ai.runtime

import java.util.Locale

import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.cncf.spi.ai.runner.AiRunnerRequirement

/*
 * Provider-neutral execution metadata normalization for Textus AI responses.
 *
 * @since   Jul. 16, 2026
 * @version Jul. 16, 2026
 * @author  ASAMI, Tomoharu
 */
private[textus] object AiExecutionFacts {
  val PROVIDER = "ai.execution.provider"
  val MODE = "ai.execution.mode"
  val ENGINE = "ai.execution.engine"
  val MODEL = "ai.execution.model"
  val PURPOSE = "ai.execution.purpose"
  val LOCATION = "ai.execution.location"
  val TOOLS = "ai.execution.tools"
  val NORMALIZATION_MODE = "ai.execution.normalization_mode"

  def normalize(
    selection: SpiSelection,
    requirement: AiRunnerRequirement,
    responsemodel: Option[String],
    providermetadata: Map[String, String],
    normalizationmode: Option[String] = None
  ): Map[String, String] = {
    val values = Vector(
      PROVIDER -> selection.provider,
      MODE -> selection.mode,
      ENGINE -> selection.engine,
      MODEL -> responsemodel,
      PURPOSE -> requirement.purpose,
      LOCATION -> _location(selection.mode),
      TOOLS -> _tools(requirement),
      NORMALIZATION_MODE -> normalizationmode
    )
    _provider_metadata(providermetadata) ++ _values(values)
  }

  private def _provider_metadata(
    metadata: Map[String, String]
  ): Map[String, String] =
    metadata.filterNot { case (key, _) =>
      key.startsWith("ai.execution.") ||
        key.startsWith("ai.usage.") ||
        key.startsWith("ai.limitation.")
    }

  private def _values(
    values: Vector[(String, Option[String])]
  ): Map[String, String] =
    values.collect {
      case (key, Some(value)) if value.trim.nonEmpty => key -> value.trim
    }.toMap

  private def _location(
    mode: Option[String]
  ): Option[String] =
    mode.map(_.trim.toLowerCase(Locale.ROOT)).collect {
      case "local" => "local"
      case "remote" => "remote"
    }

  private def _tools(
    requirement: AiRunnerRequirement
  ): Option[String] =
    requirement.tools
      .map(_.id.trim.toLowerCase(Locale.ROOT))
      .filter(_.nonEmpty)
      .distinct
      .sorted
      .mkString(",") match {
      case "" => None
      case value => Some(value)
    }
}
