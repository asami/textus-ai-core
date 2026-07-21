package org.simplemodeling.textus.ai.runtime

import java.util.Locale
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.cncf.spi.ai.runner.AiRunnerRequirement

private[textus] enum AiUsageSource(val id: String) {
  case Reported extends AiUsageSource("reported")
  case Estimated extends AiUsageSource("estimated")
}

private[textus] final case class AiUsageFact(value: Long, source: AiUsageSource) {
  require(value >= 0, "AI usage values must be non-negative")
}

private[textus] final case class AiUsageFacts(
  inputTokens: Option[AiUsageFact] = None,
  cachedInputTokens: Option[AiUsageFact] = None,
  outputTokens: Option[AiUsageFact] = None,
  reasoningTokens: Option[AiUsageFact] = None,
  totalTokens: Option[AiUsageFact] = None
) {
  def hasValues: Boolean = Vector(
    inputTokens,
    cachedInputTokens,
    outputTokens,
    reasoningTokens,
    totalTokens
  ).flatten.nonEmpty

  // Provider-reported values take precedence over an admission estimate.
  def withFallback(fallback: AiUsageFacts): AiUsageFacts =
    AiUsageFacts(
      inputTokens.orElse(fallback.inputTokens),
      cachedInputTokens.orElse(fallback.cachedInputTokens),
      outputTokens.orElse(fallback.outputTokens),
      reasoningTokens.orElse(fallback.reasoningTokens),
      totalTokens.orElse(fallback.totalTokens)
    )
}

private[textus] object AiUsageFacts {
  val empty: AiUsageFacts = AiUsageFacts()
}

private[textus] final case class AiAccountingFacts(
  policySnapshotId: Option[String] = None,
  rateScheduleId: Option[String] = None,
  providerRequestId: Option[String] = None,
  costMicrounits: Option[Long] = None,
  costBasis: Option[String] = None,
  limitations: Vector[String] = Vector.empty
) {
  def responseMetadata: Map[String, String] =
    Vector(
      AiExecutionFacts.POLICY_SNAPSHOT_ID -> policySnapshotId,
      AiExecutionFacts.PROVIDER_REQUEST_ID -> providerRequestId,
      AiExecutionFacts.LIMITATION_CODES -> Option.when(limitations.nonEmpty)(
        limitations.map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty).distinct.sorted.mkString(",")
      )
    ).collect {
      case (key, Some(value)) if value.trim.nonEmpty => key -> value.trim
    }.toMap

  def calltreeMetadata: Map[String, String] =
    responseMetadata ++ Vector(
      AiExecutionFacts.RATE_SCHEDULE_ID -> rateScheduleId,
      AiExecutionFacts.COST_MICROUNITS -> costMicrounits.map(_.toString),
      AiExecutionFacts.COST_BASIS -> costBasis
    ).collect {
      case (key, Some(value)) if value.trim.nonEmpty => key -> value.trim
    }.toMap
}

private[textus] object AiAccountingFacts {
  val empty: AiAccountingFacts = AiAccountingFacts()
}

/*
 * Provider-neutral execution metadata normalization for Textus AI responses.
 *
 * @since   Jul. 16, 2026
 * @version Jul. 21, 2026
 * @author  ASAMI, Tomoharu
 */
private[textus] object AiExecutionFacts {
  val PROVIDER = "ai.execution.provider"
  val MODE = "ai.execution.mode"
  val ENGINE = "ai.execution.engine"
  val MODEL = "ai.execution.model"
  val PURPOSE = "ai.execution.purpose"
  val EXECUTION_CLASS = "ai.execution.execution_class"
  val LOCATION = "ai.execution.location"
  val TOOLS = "ai.execution.tools"
  val ENABLED_TOOLS = "ai.execution.enabled_tools"
  val MCP_SERVER_SET = "ai.execution.mcp_server_set"
  val TOOL_RESULT_SUMMARY = "ai.execution.tool_result_summary"
  val NORMALIZATION_MODE = "ai.execution.normalization_mode"
  val RESPONSE_ID = "ai.execution.response_id"
  val FINISH_REASON = "ai.execution.finish_reason"
  val OPERATIONAL_STRATEGY = "ai.execution.strategy"
  val ATTEMPT_LINEAGE = "ai.execution.attempt_lineage"
  val REPAIR_COUNT = "ai.execution.repair_count"
  val ESCALATION_REASON = "ai.execution.escalation_reason"
  val FINAL_PROVIDER = "ai.execution.final_provider"
  val DURATION_MILLIS = "ai.execution.duration_millis"
  val STRATEGY_MAX_REPAIRS = "ai.policy.strategy_max_repairs"
  val STRATEGY_MAX_PROVIDER_ATTEMPTS = "ai.policy.strategy_max_provider_attempts"
  val INPUT_TOKENS = "ai.usage.input_tokens"
  val INPUT_TOKENS_SOURCE = "ai.usage.input_tokens_source"
  val CACHED_INPUT_TOKENS = "ai.usage.cached_input_tokens"
  val CACHED_INPUT_TOKENS_SOURCE = "ai.usage.cached_input_tokens_source"
  val OUTPUT_TOKENS = "ai.usage.output_tokens"
  val OUTPUT_TOKENS_SOURCE = "ai.usage.output_tokens_source"
  val REASONING_TOKENS = "ai.usage.reasoning_tokens"
  val REASONING_TOKENS_SOURCE = "ai.usage.reasoning_tokens_source"
  val TOTAL_TOKENS = "ai.usage.total_tokens"
  val TOTAL_TOKENS_SOURCE = "ai.usage.total_tokens_source"
  val INPUT_PAYLOAD_BYTES = "ai.usage.input_payload_bytes"
  val INPUT_ENVELOPE_TOKENS = "ai.usage.input_envelope_tokens"
  val INPUT_DIGEST = "ai.execution.input_digest"
  val OUTPUT_DIGEST = "ai.execution.output_digest"
  val LIMITATION_CODES = "ai.limitation.codes"
  val POLICY_SNAPSHOT_ID = "ai.accounting.policy_snapshot_id"
  val RATE_SCHEDULE_ID = "ai.accounting.rate_schedule_id"
  val PROVIDER_REQUEST_ID = "ai.accounting.provider_request_id"
  val COST_MICROUNITS = "ai.accounting.cost_microunits"
  val COST_BASIS = "ai.accounting.cost_basis"
  val POLICY_MAX_OUTPUT_TOKENS = "ai.policy.max_output_tokens"
  val POLICY_MAX_INPUT_TOKENS = "ai.policy.max_input_tokens"
  val POLICY_INPUT_BUDGET_BASIS = "ai.policy.input_budget_basis"
  val POLICY_MAX_COST_MICROUNITS = "ai.policy.max_cost_microunits"
  val POLICY_MAX_REASONING_TOKENS = "ai.policy.max_reasoning_tokens"
  val POLICY_TIMEOUT_SECONDS = "ai.policy.timeout_seconds"
  val POLICY_RECORD_RETRY_LIMIT = "ai.policy.record_retry_limit"
  val POLICY_MAX_CONCURRENT = "ai.policy.max_concurrent"
  val POLICY_OUTPUT_SCHEMA_ID = "ai.policy.output_schema_id"
  val POLICY_PROMPT_CONTRACT_ID = "ai.policy.prompt_contract_id"
  val POLICY_APPLICATION_PURPOSE = "ai.policy.application_purpose"
  val POLICY_EFFECTIVE_STANDARD_PURPOSE = "ai.policy.effective_standard_purpose"
  val POLICY_RUNTIME_PROFILE = "ai.policy.runtime_profile"
  val POLICY_EFFECTIVE_EXECUTION_CLASS = "ai.policy.effective_execution_class"
  val POLICY_REASONING_LEVEL = "ai.policy.reasoning_level"

  def normalize(
    selection: SpiSelection,
    requirement: AiRunnerRequirement,
    responsemodel: Option[String],
    providermetadata: Map[String, String],
    normalizationmode: Option[String] = None,
    policymetadata: Map[String, String] = Map.empty,
    estimatedusage: AiUsageFacts = AiUsageFacts.empty,
    accountingfacts: AiAccountingFacts = AiAccountingFacts.empty
  ): Map[String, String] = {
    val usage = _provider_usage(selection, providermetadata).withFallback(estimatedusage)
    val values = Vector(
      PROVIDER -> selection.provider,
      MODE -> selection.mode,
      ENGINE -> selection.engine,
      MODEL -> responsemodel,
      PURPOSE -> requirement.purpose,
      EXECUTION_CLASS -> requirement.executionClass.map(_.id),
      LOCATION -> _location(selection.mode),
      TOOLS -> _tools(requirement),
      TOOL_RESULT_SUMMARY -> _tool_result_summary(selection, providermetadata),
      NORMALIZATION_MODE -> normalizationmode,
      RESPONSE_ID -> _provider_value(selection, providermetadata, "response_id"),
      FINISH_REASON -> _provider_value(selection, providermetadata, "finish_reason"),
      PROVIDER_REQUEST_ID -> accountingfacts.providerRequestId.orElse(
        _provider_value(selection, providermetadata, "request_id")
      )
    )
    policymetadata ++ accountingfacts.responseMetadata ++ _provider_metadata(providermetadata) ++
      _usage_metadata(usage) ++ _values(values)
  }

  def policySnapshotId(metadata: Map[String, String]): Option[String] = {
    val facts = metadata.toVector
      .filter { case (key, value) =>
        (key.startsWith("ai.policy.") || key == ENABLED_TOOLS) && value.trim.nonEmpty
      }
      .sortBy(_._1)
    Option.when(facts.nonEmpty)(digest(facts.map { case (key, value) => s"$key=$value" }.mkString("\n")))
  }

  def digest(value: String): String = {
    val bytes = MessageDigest.getInstance("SHA-256")
      .digest(Option(value).getOrElse("").getBytes(StandardCharsets.UTF_8))
    s"sha256:${bytes.map("%02x".format(_)).mkString}"
  }

  def digestMetadata(input: String, output: String): Map[String, String] =
    Map(INPUT_DIGEST -> digest(input), OUTPUT_DIGEST -> digest(output))

  def calltreeMetadata(
    metadata: Map[String, String],
    accountingfacts: AiAccountingFacts = AiAccountingFacts.empty
  ): Map[String, String] =
    (metadata ++ accountingfacts.calltreeMetadata).collect {
      case (key, value) if _is_normalized_key(key) && value.trim.nonEmpty =>
        s"response_metadata.$key" -> value.trim
    }

  def lifecycleLimitations(
    metadata: Map[String, String],
    accountingfacts: AiAccountingFacts = AiAccountingFacts.empty
  ): Map[String, String] = {
    val existing = metadata.get(LIMITATION_CODES).toVector.flatMap(_.split(","))
    val concurrency = Option.when(!metadata.contains(POLICY_MAX_CONCURRENT))("concurrency_not_enforced").toVector
    val outputverification = Option.when(
      metadata.contains(POLICY_MAX_OUTPUT_TOKENS) && !metadata.contains(OUTPUT_TOKENS)
    )("output_limit_not_verified").toVector
    val reasoningverification = Option.when(
      metadata.contains(POLICY_MAX_REASONING_TOKENS) && !metadata.contains(REASONING_TOKENS)
    )("reasoning_limit_not_verified").toVector
    val usage = Option.when(!_has_usage(metadata))("usage_unavailable").toVector
    val pricing = Option.when(
      !metadata.contains(RATE_SCHEDULE_ID) && accountingfacts.rateScheduleId.isEmpty
    )("rate_schedule_unavailable").toVector
    val estimatedinput = Option.when(metadata.get(INPUT_TOKENS_SOURCE).contains(AiUsageSource.Estimated.id))(
      "input_token_estimated"
    ).toVector
    val codes = (existing ++ Vector("cancellation_not_propagated") ++ concurrency ++
      outputverification ++ reasoningverification ++ usage ++ pricing ++ estimatedinput)
      .map(_.trim.toLowerCase(Locale.ROOT))
      .filter(_.nonEmpty)
      .distinct
      .sorted
    metadata.updated(LIMITATION_CODES, codes.mkString(","))
  }

  def validateMaxOutputTokens(
    selection: SpiSelection,
    maximum: Option[Int],
    metadata: Map[String, String]
  ): org.goldenport.Consequence[Unit] =
    maximum match {
      case Some(limit) =>
        _output_token_count(selection, metadata) match {
          case Some(actual) if actual > limit =>
            org.goldenport.Consequence.operationIllegal(
              _provider_name(selection),
              s"AI provider output tokens exceeded maximum: limit=$limit actual=$actual"
            )
          case _ =>
            org.goldenport.Consequence.unit
        }
      case None =>
        org.goldenport.Consequence.unit
    }

  def validateMaxReasoningTokens(
    selection: SpiSelection,
    maximum: Option[Int],
    metadata: Map[String, String]
  ): org.goldenport.Consequence[Unit] =
    maximum match {
      case Some(limit) =>
        _reasoning_token_count(selection, metadata) match {
          case Some(actual) if actual > limit =>
            org.goldenport.Consequence.operationIllegal(
              _provider_name(selection),
              s"AI provider reasoning tokens exceeded maximum: limit=$limit actual=$actual"
            )
          case _ =>
            org.goldenport.Consequence.unit
        }
      case None =>
        org.goldenport.Consequence.unit
    }

  private def _provider_metadata(
    metadata: Map[String, String]
  ): Map[String, String] =
    metadata.collect {
      case (key, value) if _is_safe_provider_key(key) && value.trim.nonEmpty => key -> value.trim
    }

  private def _is_normalized_key(key: String): Boolean =
      key.startsWith("ai.execution.") ||
      key.startsWith("ai.policy.") ||
      key.startsWith("ai.usage.") ||
      key.startsWith("ai.accounting.") ||
      key.startsWith("ai.limitation.")

  private def _is_safe_provider_key(key: String): Boolean =
    key match {
      case "google.response_id" | "google.finish_reason" |
          "google.usage.input_tokens" | "google.usage.output_tokens" | "google.usage.total_tokens" |
          "google.usage.cached_input_tokens" | "google.usage.reasoning_tokens" |
          "google.request_id" |
          "google.google_search_calls" | "google.google_search_results" |
          "google.url_context_calls" | "google.url_citations" => true
      case "openai.response_id" | "openai.finish_reason" |
          "openai.usage.input_tokens" | "openai.usage.output_tokens" | "openai.usage.total_tokens" |
          "openai.usage.cached_input_tokens" | "openai.usage.reasoning_tokens" |
          "openai.request_id" |
          "openai.web_search_calls" => true
      case "anthropic.response_id" | "anthropic.finish_reason" |
          "anthropic.usage.input_tokens" | "anthropic.usage.output_tokens" |
          "anthropic.usage.total_tokens" | "anthropic.usage.cached_input_tokens" => true
      case "gemma.finish_reason" |
          "gemma.usage.input_tokens" | "gemma.usage.output_tokens" | "gemma.usage.total_tokens" |
          "gemma.mcp_calls" | "gemma.mcp_turns" | "gemma.mcp_catalog_digest" => true
      case "codex.finish_reason" => true
      case "claude.finish_reason" | "claude.session_id" |
          "claude.duration_ms" | "claude.num_turns" | "claude.profile" => true
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

  private def _output_token_count(
    selection: SpiSelection,
    metadata: Map[String, String]
  ): Option[Int] =
    metadata.get(s"${_provider_name(selection)}.usage.output_tokens")
      .flatMap(_.trim.toIntOption)
      .filter(_ >= 0)

  private def _reasoning_token_count(
    selection: SpiSelection,
    metadata: Map[String, String]
  ): Option[Int] =
    _usage_value(selection, metadata, "usage.reasoning_tokens")
      .flatMap(_.trim.toIntOption)
      .filter(_ >= 0)

  private def _provider_name(selection: SpiSelection): String =
    selection.provider
      .map(_.trim.toLowerCase(Locale.ROOT))
      .filter(_.nonEmpty)
      .map {
        case "codex-cli" => "codex"
        case value => value
      }
      .getOrElse("gemma")

  private def _usage_value(
    selection: SpiSelection,
    metadata: Map[String, String],
    suffix: String
  ): Option[String] =
    _provider_value(selection, metadata, suffix)
      .flatMap(_.toLongOption)
      .filter(_ >= 0)
      .map(_.toString)

  private def _provider_usage(
    selection: SpiSelection,
    metadata: Map[String, String]
  ): AiUsageFacts =
    AiUsageFacts(
      inputTokens = _usage_fact(selection, metadata, "usage.input_tokens"),
      cachedInputTokens = _usage_fact(selection, metadata, "usage.cached_input_tokens"),
      outputTokens = _usage_fact(selection, metadata, "usage.output_tokens"),
      reasoningTokens = _usage_fact(selection, metadata, "usage.reasoning_tokens"),
      totalTokens = _usage_fact(selection, metadata, "usage.total_tokens")
    )

  private def _usage_fact(
    selection: SpiSelection,
    metadata: Map[String, String],
    suffix: String
  ): Option[AiUsageFact] =
    _usage_value(selection, metadata, suffix).flatMap(_.toLongOption).map { value =>
      AiUsageFact(value, AiUsageSource.Reported)
    }

  private def _usage_metadata(usage: AiUsageFacts): Map[String, String] =
    Vector(
      INPUT_TOKENS -> usage.inputTokens,
      CACHED_INPUT_TOKENS -> usage.cachedInputTokens,
      OUTPUT_TOKENS -> usage.outputTokens,
      REASONING_TOKENS -> usage.reasoningTokens,
      TOTAL_TOKENS -> usage.totalTokens
    ).flatMap { case (key, fact) =>
      fact.toVector.flatMap(value => Vector(
        key -> value.value.toString,
        s"${key}_source" -> value.source.id
      ))
    }.toMap

  private def _has_usage(metadata: Map[String, String]): Boolean =
    Vector(
      INPUT_TOKENS,
      CACHED_INPUT_TOKENS,
      OUTPUT_TOKENS,
      REASONING_TOKENS,
      TOTAL_TOKENS
    ).exists(metadata.contains)

  private def _tool_result_summary(
    selection: SpiSelection,
    metadata: Map[String, String]
  ): Option[String] = {
    val provider = selection.provider.map(_.trim.toLowerCase(Locale.ROOT)).getOrElse("")
    val labels = provider match {
      case "google" => Vector(
        "google_search_calls",
        "google_search_results",
        "url_context_calls",
        "url_citations"
      )
      case "openai" => Vector("web_search_calls")
      case "gemma" => Vector("mcp_calls", "mcp_turns")
      case _ => Vector.empty
    }
    labels.flatMap { label =>
      metadata.get(s"$provider.$label")
        .flatMap(_.trim.toLongOption)
        .filter(_ >= 0)
        .map(value => s"$label=$value")
    }.mkString(";") match {
      case "" => None
      case value => Some(value)
    }
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
