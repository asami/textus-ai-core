package org.simplemodeling.textus.ai.runtime

import org.goldenport.Consequence
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.ai.runner.*
import org.goldenport.record.Record

/*
 * Deterministic CNCF AI runner used only by CAR Review executable specs.
 *
 * @since   Jul. 16, 2026
 * @version Jul. 16, 2026
 * @author  ASAMI, Tomoharu
 */
private[runtime] final class CarReviewAiRunnerFixture extends AiRunner {
  import CarReviewAiRunnerFixture.*

  private var _retry_then_success_remaining = 1

  def generate(req: AiGenerateRequest)(using ExecutionContext): Consequence[AiGenerateResponse] =
    _scenario(req.properties) match {
      case Success | RetryThenSuccess => Consequence.success(AiGenerateResponse("fixture:review-ready", Some(model), _metadata))
      case Unknown => Consequence.success(AiGenerateResponse("fixture:review-unknown", Some(model), _limited_metadata))
      case scenario => _failure(scenario)
    }

  def generateRecord(req: AiRecordRequest)(using ExecutionContext): Consequence[AiRecordResponse] =
    _scenario(req.properties) match {
      case Success => Consequence.success(AiRecordResponse(candidate, Some(model), _record_metadata(req)))
      case Unknown => Consequence.success(AiRecordResponse(candidate, Some(model), _limited_record_metadata(req)))
      case RetryThenSuccess if _retry_then_success_remaining > 0 =>
        _retry_then_success_remaining -= 1
        Consequence.serviceUnavailable("fixture timeout before retry")
      case RetryThenSuccess => Consequence.success(AiRecordResponse(candidate, Some(model), _record_metadata(req) ++ Map("ai.execution.retry_count" -> "1")))
      case scenario => _failure(scenario)
    }

  def chat(req: AiChatRequest)(using ExecutionContext): Consequence[AiChatResponse] =
    _scenario(req.properties) match {
      case Success | RetryThenSuccess => Consequence.success(AiChatResponse(AiMessage("assistant", "fixture:review-ready"), Some(model), _metadata))
      case Unknown => Consequence.success(AiChatResponse(AiMessage("assistant", "fixture:review-unknown"), Some(model), _limited_metadata))
      case scenario => _failure(scenario)
    }

  private def _scenario(properties: Vector[org.goldenport.protocol.Property]): Scenario =
    properties.find(_.name == scenarioProperty).map(x => String.valueOf(x.value).trim).collect {
      case "unknown" => Unknown
      case "malformed" => Malformed
      case "empty" => Empty
      case "unavailable" => Unavailable
      case "quota" => Quota
      case "timeout" => Timeout
      case "cancelled" => Cancelled
      case "retry-then-success" => RetryThenSuccess
    }.getOrElse(Success)

  private def _failure[A](scenario: Scenario): Consequence[A] = scenario match {
    case Malformed => Consequence.argumentInvalid("fixture malformed structured review output")
    case Empty => Consequence.argumentInvalid("fixture empty structured review output")
    case Unavailable => Consequence.serviceUnavailable("fixture provider unavailable")
    case Quota => Consequence.serviceUnavailable("fixture provider quota exhausted")
    case Timeout => Consequence.serviceUnavailable("fixture provider timeout")
    case Cancelled => Consequence.serviceUnavailable("fixture cancellation is not propagated")
    case _ => Consequence.serviceUnavailable("fixture unexpected scenario")
  }
}

private[runtime] object CarReviewAiRunnerFixture {
  val model = "car-review-fixture-v1"
  val scenarioProperty = "ai.fixture.scenario"

  val candidate: Record = Record.dataAuto(
    "findings" -> Vector(Record.dataAuto(
      "rule_id" -> "documentation.clarity",
      "severity" -> "medium",
      "message" -> "Describe the operation input and output."
    ))
  )

  val limitations: Map[String, String] = Map(
    "ai.limitation.codes" -> "provider_identity_unavailable,usage_unavailable"
  )

  private val _metadata = Map(
    "ai.execution.provider" -> "fixture",
    "ai.execution.mode" -> "deterministic",
    "ai.execution.engine" -> "car-review",
    "ai.execution.model" -> model,
    "ai.usage.request_count" -> "1"
  )

  private val _limited_metadata = Map(
    "ai.execution.mode" -> "deterministic",
    "ai.execution.engine" -> "car-review"
  ) ++ limitations

  private def _record_metadata(req: AiRecordRequest): Map[String, String] =
    _metadata ++ _record_provenance(req)

  private def _limited_record_metadata(req: AiRecordRequest): Map[String, String] =
    _limited_metadata ++ _record_provenance(req)

  private def _record_provenance(req: AiRecordRequest): Map[String, String] =
    req.requirement.purpose.map(value => "ai.execution.purpose" -> value).toMap ++
      Map(
        "ai.execution.input_digest" -> AiExecutionFacts.digest(req.prompt),
        "ai.execution.output_digest" -> AiExecutionFacts.digest(candidate.toJsonString)
      )

  private sealed trait Scenario
  private case object Success extends Scenario
  private case object Unknown extends Scenario
  private case object Malformed extends Scenario
  private case object Empty extends Scenario
  private case object Unavailable extends Scenario
  private case object Quota extends Scenario
  private case object Timeout extends Scenario
  private case object Cancelled extends Scenario
  private case object RetryThenSuccess extends Scenario
}
