package org.simplemodeling.textus.ai.runtime

import java.util.Locale
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.cncf.spi.ai.runner.AiRunnerRequirement

/*
 * Provider-neutral execution metadata normalization for Textus AI responses.
 *
 * @since   Jul. 16, 2026
 * @version Jul. 17, 2026
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
  val RESPONSE_ID = "ai.execution.response_id"
  val FINISH_REASON = "ai.execution.finish_reason"
  val INPUT_TOKENS = "ai.usage.input_tokens"
  val OUTPUT_TOKENS = "ai.usage.output_tokens"
  val TOTAL_TOKENS = "ai.usage.total_tokens"
  val INPUT_DIGEST = "ai.execution.input_digest"
  val OUTPUT_DIGEST = "ai.execution.output_digest"
  val LIMITATION_CODES = "ai.limitation.codes"

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
      NORMALIZATION_MODE -> normalizationmode,
      RESPONSE_ID -> _provider_value(selection, providermetadata, "response_id"),
      FINISH_REASON -> _provider_value(selection, providermetadata, "finish_reason"),
      INPUT_TOKENS -> _usage_value(selection, providermetadata, "usage.input_tokens"),
      OUTPUT_TOKENS -> _usage_value(selection, providermetadata, "usage.output_tokens"),
      TOTAL_TOKENS -> _usage_value(selection, providermetadata, "usage.total_tokens")
    )
    _provider_metadata(providermetadata) ++ _values(values)
  }

  def digest(value: String): String = {
    val bytes = MessageDigest.getInstance("SHA-256")
      .digest(Option(value).getOrElse("").getBytes(StandardCharsets.UTF_8))
    s"sha256:${bytes.map("%02x".format(_)).mkString}"
  }

  def digestMetadata(input: String, output: String): Map[String, String] =
    Map(INPUT_DIGEST -> digest(input), OUTPUT_DIGEST -> digest(output))

  def calltreeMetadata(metadata: Map[String, String]): Map[String, String] =
    metadata.collect {
      case (key, value) if _is_normalized_key(key) && value.trim.nonEmpty =>
        s"response_metadata.$key" -> value.trim
    }

  def lifecycleLimitations(metadata: Map[String, String]): Map[String, String] = {
    val existing = metadata.get(LIMITATION_CODES).toVector.flatMap(_.split(","))
    val codes = (existing ++ Vector("cancellation_not_propagated", "concurrency_not_enforced"))
      .map(_.trim.toLowerCase(Locale.ROOT))
      .filter(_.nonEmpty)
      .distinct
      .sorted
    metadata.updated(LIMITATION_CODES, codes.mkString(","))
  }

  private def _provider_metadata(
    metadata: Map[String, String]
  ): Map[String, String] =
    metadata.collect {
      case (key, value) if _is_safe_provider_key(key) && value.trim.nonEmpty => key -> value.trim
    }

  private def _is_normalized_key(key: String): Boolean =
    key.startsWith("ai.execution.") ||
      key.startsWith("ai.usage.") ||
      key.startsWith("ai.limitation.")

  private def _is_safe_provider_key(key: String): Boolean =
    key match {
      case "google.response_id" | "google.finish_reason" |
          "google.usage.input_tokens" | "google.usage.output_tokens" | "google.usage.total_tokens" |
          "google.google_search_calls" | "google.google_search_results" |
          "google.url_context_calls" | "google.url_citations" => true
      case "openai.response_id" | "openai.finish_reason" |
          "openai.usage.input_tokens" | "openai.usage.output_tokens" | "openai.usage.total_tokens" |
          "openai.web_search_calls" => true
      case "gemma.finish_reason" |
          "gemma.usage.input_tokens" | "gemma.usage.output_tokens" | "gemma.usage.total_tokens" => true
      case "codex.finish_reason" => true
      case _ => false
    }

  private def _provider_value(
    selection: SpiSelection,
    metadata: Map[String, String],
    suffix: String
  ): Option[String] =
    selection.provider
      .map(_.trim.toLowerCase(Locale.ROOT))
      .filter(_.nonEmpty)
      .flatMap(provider => metadata.get(s"$provider.$suffix"))
      .map(_.trim)
      .filter(_.nonEmpty)

  private def _usage_value(
    selection: SpiSelection,
    metadata: Map[String, String],
    suffix: String
  ): Option[String] =
    _provider_value(selection, metadata, suffix)
      .flatMap(_.toLongOption)
      .filter(_ >= 0)
      .map(_.toString)

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
