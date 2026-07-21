package org.simplemodeling.textus.ai.runtime

import java.util.Locale
import org.goldenport.{Conclusion, Consequence}

/**
 * Runtime-owned strategy vocabulary for bounded AI candidate execution.
 *
 * @since   Jul. 21, 2026
 * @version Jul. 21, 2026
 */
private[textus] enum AiOperationalStrategyKind(val id: String) {
  case Structured extends AiOperationalStrategyKind("structured")
  case ToolGrounded extends AiOperationalStrategyKind("tool-grounded")
  case Decomposed extends AiOperationalStrategyKind("decomposed")
  case ValidatorRepair extends AiOperationalStrategyKind("validator-repair")
  case CandidateRanking extends AiOperationalStrategyKind("candidate-ranking")
}

private[textus] object AiOperationalStrategyKind {
  def parseC(value: String): Consequence[AiOperationalStrategyKind] = {
    val normalized = Option(value).getOrElse("").trim.toLowerCase(Locale.ROOT)
    AiOperationalStrategyKind.values.find(_.id == normalized)
      .map(Consequence.success)
      .getOrElse(Consequence.configurationInvalid(s"Unsupported AI operational strategy: $value"))
  }
}

private[textus] enum AiAttemptFailureClass(
  val id: String,
  val permitsCommercialEscalation: Boolean
) {
  case Availability extends AiAttemptFailureClass("availability", true)
  case Timeout extends AiAttemptFailureClass("timeout", true)
  case MalformedOutput extends AiAttemptFailureClass("malformed-output", true)
  case DomainValidation extends AiAttemptFailureClass("domain-validation", true)
  case Evidence extends AiAttemptFailureClass("evidence", true)
  case Ambiguity extends AiAttemptFailureClass("ambiguity", true)
  case Authorization extends AiAttemptFailureClass("authorization", false)
  case Capability extends AiAttemptFailureClass("capability", false)
  case Admission extends AiAttemptFailureClass("admission", false)
  case CredentialPolicy extends AiAttemptFailureClass("credential-policy", false)
  case Input extends AiAttemptFailureClass("input", false)
  case ResourceLimit extends AiAttemptFailureClass("resource-limit", false)
  case Unknown extends AiAttemptFailureClass("unknown", false)
}

private[textus] object AiAttemptFailureClass {
  def fromConclusion(conclusion: Conclusion): AiAttemptFailureClass = {
    val detail = Option(conclusion.display).getOrElse("").toLowerCase(Locale.ROOT)
    if (
      _has_cause_simple_name(conclusion, "SocketTimeoutException") ||
      _has_cause_simple_name(conclusion, "HttpTimeoutException")
    )
      Timeout
    else if (
      _has_cause_simple_name(conclusion, "ConnectException") ||
      _has_cause_simple_name(conclusion, "UnknownHostException") ||
      _has_cause_simple_name(conclusion, "SocketException")
    )
      Availability
    else if (_contains_any(detail, "empty output", "invalid json response"))
      MalformedOutput
    else if (_contains_any(
      detail,
      "maximum output tokens are not supported",
      "execution profile is not admitted",
      "does not support requested tools",
      "tools require an admitted runtime-profile binding",
      "cli version"
    ))
      Capability
    else if (_contains_any(detail, "output exceeded the configured limit"))
      ResourceLimit
    else if (_contains_any(detail, "authorization", "unauthorized", "forbidden"))
      Authorization
    else if (_contains_any(detail, "credential-policy", "credential policy"))
      CredentialPolicy
    else if (_contains_any(detail, "resource-limit", "resource limit"))
      ResourceLimit
    else if (_contains_any(detail, "timeout", "timed out", "deadline exceeded"))
      Timeout
    else if (_contains_any(
      detail,
      "unavailable",
      "connection refused",
      "could not connect",
      "failed to connect"
    ))
      Availability
    else if (_contains_any(detail, "capability"))
      Capability
    else if (_contains_any(detail, "admission", "rate limit", "quota"))
      Admission
    else conclusion.status.webCode.code match {
      case 401 | 403 => Authorization
      case 408 | 504 => Timeout
      case 429 => Admission
      case 413 => ResourceLimit
      case 502 | 503 => Availability
      case 400 | 404 | 405 | 409 | 415 | 422 => Input
      case _ => Unknown
    }
  }

  private def _contains_any(source: String, candidates: String*): Boolean =
    candidates.exists(source.contains)

  def safeReason(conclusion: Conclusion): String = {
    val detail = Option(conclusion.display).getOrElse("").toLowerCase(Locale.ROOT)
    if (detail.contains("maximum output tokens are not supported")) "output-token-policy"
    else if (detail.contains("execution profile is not admitted")) "execution-profile"
    else if (detail.contains("process execution admission is not configured")) "process-admission"
    else if (detail.contains("process execution driver is not configured")) "process-driver"
    else if (detail.contains("workarea") && detail.contains("unavailable")) "process-work-area"
    else if (detail.contains("cli version")) "cli-version"
    else if (detail.contains("cli exited unsuccessfully")) "cli-exit"
    else if (detail.contains("empty output")) "empty-output"
    else if (detail.contains("output exceeded the configured limit")) "output-limit"
    else if (detail.contains("timed out") || detail.contains("timeout")) "timeout"
    else if (detail.contains("unavailable")) "unavailable"
    else if (conclusion.getException.nonEmpty) "exception"
    else "status-only"
  }

  private def _has_cause_simple_name(conclusion: Conclusion, classname: String): Boolean =
    conclusion.getException.exists { exception =>
      Iterator.iterate(Option(exception))(_.flatMap(value => Option(value.getCause)))
        .takeWhile(_.nonEmpty)
        .flatten
        .exists(_.getClass.getSimpleName == classname)
    }

  def fromEscalationReason(value: String): AiAttemptFailureClass =
    Option(value).getOrElse("").trim.toLowerCase(Locale.ROOT) match {
      case "availability" => Availability
      case "timeout" => Timeout
      case "malformed-output" => MalformedOutput
      case "domain-validation" => DomainValidation
      case "insufficient-evidence" | "evidence" => Evidence
      case "ambiguity" => Ambiguity
      case "authorization" => Authorization
      case "capability" => Capability
      case "admission" => Admission
      case "credential-policy" => CredentialPolicy
      case "input" => Input
      case "resource-limit" => ResourceLimit
      case _ => Unknown
    }
}

private[textus] final case class AiOperationalStrategy(
  kind: AiOperationalStrategyKind,
  primary: AiRuntimeExecution,
  commercialFallback: Option[AiRuntimeExecution],
  commercialFallbackRateSchedule: Option[AiRateSchedule],
  maxRepairs: Int,
  maxProviderAttempts: Int,
  acceptanceOperation: Option[String]
) {
  require(maxRepairs >= 0 && maxRepairs <= 3, "AI strategy repair bound must be between zero and three")
  require(maxProviderAttempts >= 1 && maxProviderAttempts <= 2, "AI strategy provider bound must be one or two")

  def id: String = kind.id

  def fallbackFor(failure: AiAttemptFailureClass): Option[AiRuntimeExecution] =
    Option.when(maxProviderAttempts > 1 && failure.permitsCommercialEscalation)(commercialFallback).flatten
}

private[textus] final case class AiCandidateAcceptance(
  decision: String,
  diagnostics: String,
  allowedRepairPaths: String,
  escalationReason: Option[String]
)

private[textus] final case class AiAttemptFact(
  index: Int,
  provider: String,
  stage: String,
  outcome: String
) {
  def safeValue: String = s"$index:$provider:$stage:$outcome"
}

private[textus] final case class AiStrategyFacts(
  attempts: Vector[AiAttemptFact],
  repairCount: Int,
  escalationReason: Option[String],
  finalProvider: String,
  durationMillis: Long
) {
  def metadata: Map[String, String] =
    Vector(
      AiExecutionFacts.ATTEMPT_LINEAGE -> Option.when(attempts.nonEmpty)(attempts.map(_.safeValue).mkString(",")),
      AiExecutionFacts.REPAIR_COUNT -> Some(repairCount.toString),
      AiExecutionFacts.ESCALATION_REASON -> escalationReason,
      AiExecutionFacts.FINAL_PROVIDER -> Option(finalProvider).filter(_.nonEmpty),
      AiExecutionFacts.DURATION_MILLIS -> Some(durationMillis.max(0).toString)
    ).collect {
      case (key, Some(value)) if value.trim.nonEmpty => key -> value.trim
    }.toMap
}
