package org.simplemodeling.textus.ai.runtime

import java.util.Locale

import org.goldenport.cncf.spi.ai.runner.AiGenerateResponse

/*
 * A sanitized, application-owned projection of one completed AI execution.
 *
 * This is intentionally an evidence contract rather than an execution log:
 * it never retains prompt text, model output, provider payloads, credentials,
 * endpoints, account identifiers, or provider request/response identifiers.
 * Applications persist the returned facts behind their own artifact reference
 * and hand only that reference to Corpus and Experiment.
 *
 * @since   Jul. 23, 2026
 * @version Jul. 23, 2026
 * @author  ASAMI, Tomoharu
 */
final case class AiExecutionObservation(
  identity: Map[String, String],
  measurements: Map[String, AiObservationMeasurement],
  providerStandardTools: AiProviderStandardToolObservation,
  cncfEvidence: AiCncfEvidenceObservation,
  assessment: AiObservationAssessment,
  references: AiObservationReferences,
  limitations: Vector[String]
) {
  // Stable, scalar facts suitable for a sanitized metric artifact.
  def safeFacts: Map[String, String] =
    identity.map { case (key, value) => s"ai.observation.identity.$key" -> value } ++
      measurements.toVector.flatMap { case (key, measurement) =>
        measurement.safeFacts(s"ai.observation.measurement.$key")
      }.toMap ++
      providerStandardTools.safeFacts ++
      cncfEvidence.safeFacts ++
      assessment.safeFacts ++
      references.safeFacts ++
      Option.when(limitations.nonEmpty)(
        "ai.observation.limitations" -> limitations.mkString(",")
      )
}

enum AiObservationMeasurementState(val id: String) {
  case Reported extends AiObservationMeasurementState("reported")
  case Estimated extends AiObservationMeasurementState("estimated")
  case Unavailable extends AiObservationMeasurementState("unavailable")
  case NotApplicable extends AiObservationMeasurementState("not-applicable")
}

final case class AiObservationMeasurement(
  state: AiObservationMeasurementState,
  value: Option[Long] = None,
  basis: Option[String] = None
) {
  require(
    (state == AiObservationMeasurementState.Reported || state == AiObservationMeasurementState.Estimated) == value.nonEmpty,
    s"Measurement state ${state.id} requires a value exactly when it is reported or estimated"
  )

  def safeFacts(prefix: String): Vector[(String, String)] =
    Vector(
      Some(s"$prefix.state" -> state.id),
      value.map(v => s"$prefix.value" -> v.toString),
      basis.map(v => s"$prefix.basis" -> v)
    ).flatten
}

enum AiObservationValidity(val id: String) {
  case Valid extends AiObservationValidity("valid")
  case Invalid extends AiObservationValidity("invalid")
  case Unavailable extends AiObservationValidity("unavailable")
  case NotApplicable extends AiObservationValidity("not-applicable")
}

enum AiObservationOutcome(val id: String) {
  case Accepted extends AiObservationOutcome("accepted")
  case Rejected extends AiObservationOutcome("rejected")
  case Failed extends AiObservationOutcome("failed")
  case NotApplicable extends AiObservationOutcome("not-applicable")
}

final case class AiObservationAssessment(
  outcome: AiObservationOutcome,
  schemaValidity: AiObservationValidity,
  evidenceValidity: AiObservationValidity,
  failureClassification: Option[String] = None,
  reasonCodes: Vector[String] = Vector.empty
) {
  def safeFacts: Map[String, String] =
    Vector(
      "ai.observation.assessment.outcome" -> outcome.id,
      "ai.observation.assessment.schema_validity" -> schemaValidity.id,
      "ai.observation.assessment.evidence_validity" -> evidenceValidity.id
    ).toMap ++
      failureClassification.map(v => "ai.observation.assessment.failure_classification" -> v) ++
      Option.when(reasonCodes.nonEmpty)(
        "ai.observation.assessment.reason_codes" -> reasonCodes.mkString(",")
      )
}

final case class AiProviderStandardToolObservation(
  requested: Vector[String],
  admitted: Vector[String],
  admissionState: AiObservationMeasurementState,
  calls: Map[String, AiObservationMeasurement],
  returnedContext: Map[String, AiObservationMeasurement],
  chargeBasis: Map[String, AiObservationMeasurementState]
) {
  def safeFacts: Map[String, String] =
    Option.when(requested.nonEmpty)("ai.observation.provider_tools.requested" -> requested.mkString(",")).toMap ++
      Option.when(admitted.nonEmpty)("ai.observation.provider_tools.admitted" -> admitted.mkString(",")).toMap ++
      Map("ai.observation.provider_tools.admission.state" -> admissionState.id) ++
      calls.toVector.flatMap { case (tool, measurement) =>
        measurement.safeFacts(s"ai.observation.provider_tools.$tool.calls")
      }.toMap ++
      returnedContext.toVector.flatMap { case (tool, measurement) =>
        measurement.safeFacts(s"ai.observation.provider_tools.$tool.returned_context")
      }.toMap ++
      chargeBasis.map { case (tool, state) =>
        s"ai.observation.provider_tools.$tool.charge_basis.state" -> state.id
      }
}

final case class AiCncfEvidenceObservation(
  sources: Vector[String],
  operationCalls: AiObservationMeasurement,
  mcpCalls: AiObservationMeasurement
) {
  def safeFacts: Map[String, String] =
    Option.when(sources.nonEmpty)("ai.observation.cncf_evidence.sources" -> sources.mkString(",")).toMap ++
      operationCalls.safeFacts("ai.observation.cncf_evidence.operation_calls").toMap ++
      mcpCalls.safeFacts("ai.observation.cncf_evidence.mcp_calls").toMap
}

final case class AiObservationReferences(
  executionPlan: String,
  promptContract: String,
  strategy: String,
  executionEvidence: String,
  acceptanceEvidence: String,
  metric: String
) {
  require(
    Vector(executionPlan, promptContract, strategy, executionEvidence, acceptanceEvidence, metric)
      .forall(AiObservationReferences.isSafe),
    "AI observation references must be sanitized artifact URIs"
  )

  def safeFacts: Map[String, String] = Map(
    "ai.observation.reference.execution_plan" -> executionPlan,
    "ai.observation.reference.prompt_contract" -> promptContract,
    "ai.observation.reference.strategy" -> strategy,
    "ai.observation.reference.execution_evidence" -> executionEvidence,
    "ai.observation.reference.acceptance_evidence" -> acceptanceEvidence,
    "ai.observation.reference.metric" -> metric
  )
}

object AiObservationReferences {
  // References identify retained artifacts; public Web schemes, query strings,
  // and fragments are excluded so they cannot carry a payload or private URL.
  def isSafe(value: String): Boolean =
    Option(value).exists { v =>
      v == v.trim &&
        !v.startsWith("http://") &&
        !v.startsWith("https://") &&
        v.matches("[a-z][a-z0-9+.-]*://[a-z0-9][a-z0-9._/-]{0,511}")
    }
}

final case class AiExecutionObservationContext(
  cncfEvidenceSources: Vector[String],
  assessment: AiObservationAssessment,
  references: AiObservationReferences
)

object AiExecutionObservation {
  // This response metadata is a safe, generic AiRunner handoff. Applications
  // add their assessment, evidence identities, and artifact references later.
  val rateScheduleSnapshotMetadataKey = "ai.observation.identity.rate_schedule_snapshot"

  def runtimeFacts(response: AiGenerateResponse): Map[String, String] = {
    val runtime = _runtime(response)
    runtime.identity.map { case (key, value) => s"ai.observation.identity.$key" -> value } ++
      runtime.measurements.toVector.flatMap { case (key, measurement) =>
        measurement.safeFacts(s"ai.observation.measurement.$key")
      }.toMap ++
      runtime.providerStandardTools.safeFacts ++
      runtime.cncfEvidence.safeFacts ++
      Option.when(runtime.limitations.nonEmpty)(
        "ai.observation.limitations" -> runtime.limitations.mkString(",")
      )
  }

  def from(
    response: AiGenerateResponse,
    context: AiExecutionObservationContext
  ): AiExecutionObservation = {
    val runtime = _runtime(response)
    AiExecutionObservation(
      identity = runtime.identity,
      measurements = runtime.measurements,
      providerStandardTools = runtime.providerStandardTools,
      cncfEvidence = runtime.cncfEvidence.copy(sources = _ids(context.cncfEvidenceSources)),
      assessment = _sanitize_assessment(context.assessment),
      references = context.references,
      limitations = runtime.limitations
    )
  }

  private final case class _Runtime(
    identity: Map[String, String],
    measurements: Map[String, AiObservationMeasurement],
    providerStandardTools: AiProviderStandardToolObservation,
    cncfEvidence: AiCncfEvidenceObservation,
    limitations: Vector[String]
  )

  private def _runtime(response: AiGenerateResponse): _Runtime = {
    val metadata = response.metadata
    val provider = _value(metadata, AiExecutionFacts.PROVIDER)
    val identity = Vector(
      "provider" -> provider,
      "mode" -> _value(metadata, AiExecutionFacts.MODE),
      "engine" -> _value(metadata, AiExecutionFacts.ENGINE),
      "model" -> response.model.flatMap(_safe_id).orElse(_value(metadata, AiExecutionFacts.MODEL)),
      "purpose" -> _value(metadata, AiExecutionFacts.PURPOSE),
      "application_purpose" -> _value(metadata, AiExecutionFacts.POLICY_APPLICATION_PURPOSE),
      "runtime_profile" -> _value(metadata, AiExecutionFacts.POLICY_RUNTIME_PROFILE),
      "execution_class" -> _value(metadata, AiExecutionFacts.POLICY_EFFECTIVE_EXECUTION_CLASS)
        .orElse(_value(metadata, AiExecutionFacts.EXECUTION_CLASS)),
      "location" -> _value(metadata, AiExecutionFacts.LOCATION),
      "operational_strategy" -> _value(metadata, AiExecutionFacts.OPERATIONAL_STRATEGY),
      "policy_snapshot" -> _value(metadata, AiExecutionFacts.POLICY_SNAPSHOT_ID),
      "rate_schedule_snapshot" -> _value(metadata, rateScheduleSnapshotMetadataKey),
      "input_digest" -> _value(metadata, AiExecutionFacts.INPUT_DIGEST),
      "output_digest" -> _value(metadata, AiExecutionFacts.OUTPUT_DIGEST)
    ).collect { case (key, Some(value)) => key -> value }.toMap
    val local = identity.get("location").contains("local")
    val managedcli = provider.exists(_managed_cli_providers.contains)
    val measurements = Map(
      "elapsed_millis" -> _measurement(metadata, AiExecutionFacts.DURATION_MILLIS),
      "provider_calls" -> _provider_calls(metadata),
      "input_tokens" -> _usage_measurement(metadata, AiExecutionFacts.INPUT_TOKENS),
      "cached_input_tokens" -> _usage_measurement(metadata, AiExecutionFacts.CACHED_INPUT_TOKENS),
      "output_tokens" -> _usage_measurement(metadata, AiExecutionFacts.OUTPUT_TOKENS),
      "reasoning_tokens" -> _usage_measurement(metadata, AiExecutionFacts.REASONING_TOKENS),
      "total_tokens" -> _usage_measurement(metadata, AiExecutionFacts.TOTAL_TOKENS),
      "monetary_cost_microunits" -> _cost_measurement(metadata, local, managedcli)
    )
    _Runtime(
      identity = identity,
      measurements = measurements,
      providerStandardTools = _provider_standard_tools(metadata, provider),
      cncfEvidence = AiCncfEvidenceObservation(
        sources = Vector.empty,
        operationCalls = _cncf_measurement(metadata, "operation_calls"),
        mcpCalls = _cncf_measurement(metadata, "mcp_calls")
      ),
      limitations = _ids(metadata.get(AiExecutionFacts.LIMITATION_CODES).toVector.flatMap(_.split(",")))
    )
  }

  private val _managed_cli_providers = Set("codex-cli", "antigravity-cli", "claude-code")

  private def _provider_standard_tools(
    metadata: Map[String, String],
    provider: Option[String]
  ): AiProviderStandardToolObservation = {
    val summary = _summary(metadata)
    val mappings = provider.toVector.flatMap {
      case "google" => Vector("web_search" -> "google_search", "url_context" -> "url_context")
      case "openai" => Vector("web_search" -> "web_search")
      case _ => Vector.empty
    }
    val calls = mappings.map { case (logical, wire) =>
      logical -> _summary_measurement(summary, s"${wire}_calls")
    }.toMap
    val returnedcontext = mappings.map { case (logical, wire) =>
      val label = if (wire == "google_search") "google_search_results" else s"${wire}_citations"
      logical -> _summary_measurement(summary, label)
    }.toMap
    val requested = _ids(_value(metadata, AiExecutionFacts.TOOLS).toVector.flatMap(_.split(",")))
    val admitted = _ids(_value(metadata, AiExecutionFacts.ENABLED_TOOLS).toVector.flatMap(_.split(",")))
    AiProviderStandardToolObservation(
      requested = requested,
      admitted = admitted,
      admissionState = if (admitted.nonEmpty) AiObservationMeasurementState.Reported
        else if (requested.nonEmpty) AiObservationMeasurementState.Unavailable
        else AiObservationMeasurementState.NotApplicable,
      calls = calls,
      returnedContext = returnedcontext,
      // Provider adapters do not currently expose a priced tool ledger.
      // Preserve that absence explicitly instead of assigning a zero charge.
      chargeBasis = requested.map(_ -> AiObservationMeasurementState.Unavailable).toMap
    )
  }

  private def _provider_calls(metadata: Map[String, String]): AiObservationMeasurement =
    _value(metadata, AiExecutionFacts.ATTEMPT_LINEAGE) match {
      case Some(value) => AiObservationMeasurement(AiObservationMeasurementState.Reported, Some(value.split(",").count(_.trim.nonEmpty)), Some("attempt-lineage"))
      case None => AiObservationMeasurement(AiObservationMeasurementState.Unavailable)
    }

  private def _cost_measurement(
    metadata: Map[String, String],
    local: Boolean,
    managedcli: Boolean
  ): AiObservationMeasurement =
    _value(metadata, AiExecutionFacts.COST_MICROUNITS) match {
      case Some(value) if _value(metadata, AiExecutionFacts.COST_BASIS).contains("measured") =>
        AiObservationMeasurement(AiObservationMeasurementState.Reported, value.toLongOption, Some("rate-schedule-observed-usage"))
      case Some(value) if _value(metadata, AiExecutionFacts.COST_BASIS).contains("admission") =>
        AiObservationMeasurement(AiObservationMeasurementState.Estimated, value.toLongOption, Some("admission-upper-bound"))
      case _ if local => AiObservationMeasurement(AiObservationMeasurementState.NotApplicable, basis = Some("local-api-cost"))
      case _ if managedcli => AiObservationMeasurement(AiObservationMeasurementState.Unavailable, basis = Some("subscription-or-unknown-cli-billing"))
      case _ => AiObservationMeasurement(AiObservationMeasurementState.Unavailable)
    }

  private def _cncf_measurement(metadata: Map[String, String], suffix: String): AiObservationMeasurement = {
    val provider = _value(metadata, AiExecutionFacts.FINAL_PROVIDER)
      .orElse(_value(metadata, AiExecutionFacts.PROVIDER))
    provider.flatMap(value => _numeric(metadata.get(s"${value.toLowerCase(Locale.ROOT)}.$suffix"))) match {
      case Some(value) => AiObservationMeasurement(AiObservationMeasurementState.Reported, Some(value))
      case None => AiObservationMeasurement(AiObservationMeasurementState.Unavailable)
    }
  }

  private def _usage_measurement(metadata: Map[String, String], key: String): AiObservationMeasurement =
    _numeric(metadata.get(key)) match {
      case Some(value) =>
        val state = metadata.get(s"${key}_source").map(_.trim.toLowerCase(Locale.ROOT)) match {
          case Some("reported") => AiObservationMeasurementState.Reported
          case Some("estimated") => AiObservationMeasurementState.Estimated
          case _ => AiObservationMeasurementState.Unavailable
        }
        if (state == AiObservationMeasurementState.Unavailable)
          AiObservationMeasurement(state)
        else
          AiObservationMeasurement(state, Some(value))
      case None => AiObservationMeasurement(AiObservationMeasurementState.Unavailable)
    }

  private def _measurement(metadata: Map[String, String], key: String): AiObservationMeasurement =
    _numeric(metadata.get(key)) match {
      case Some(value) => AiObservationMeasurement(AiObservationMeasurementState.Reported, Some(value))
      case None => AiObservationMeasurement(AiObservationMeasurementState.Unavailable)
    }

  private def _summary_measurement(summary: Map[String, Long], key: String): AiObservationMeasurement =
    summary.get(key) match {
      case Some(value) => AiObservationMeasurement(AiObservationMeasurementState.Reported, Some(value))
      case None => AiObservationMeasurement(AiObservationMeasurementState.Unavailable)
    }

  private def _summary(metadata: Map[String, String]): Map[String, Long] =
    metadata.get(AiExecutionFacts.TOOL_RESULT_SUMMARY).toVector.flatMap(_.split(";")).flatMap { entry =>
      entry.split("=", 2).toVector match {
        case Vector(key, value) => _numeric(Some(value)).map(key.trim -> _)
        case _ => None
      }
    }.toMap

  private def _sanitize_assessment(value: AiObservationAssessment): AiObservationAssessment =
    value.copy(
      failureClassification = value.failureClassification.flatMap(_safe_id),
      reasonCodes = _ids(value.reasonCodes)
    )

  private def _value(metadata: Map[String, String], key: String): Option[String] =
    metadata.get(key).flatMap(_safe_id)

  private def _numeric(value: Option[String]): Option[Long] =
    value.flatMap(_.trim.toLongOption).filter(_ >= 0)

  private def _ids(values: Iterable[String]): Vector[String] =
    values.flatMap(_safe_id).toVector.distinct.sorted

  private def _safe_id(value: String): Option[String] = {
    val normalized = Option(value).fold("")(_.trim.toLowerCase(Locale.ROOT))
    Option.when(normalized.matches("[a-z0-9][a-z0-9._:/-]{0,255}"))(normalized)
  }
}
