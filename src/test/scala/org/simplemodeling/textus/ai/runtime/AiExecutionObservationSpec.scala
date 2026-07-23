package org.simplemodeling.textus.ai.runtime

import org.goldenport.cncf.spi.ai.runner.AiGenerateResponse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class AiExecutionObservationSpec extends AnyWordSpec with Matchers {
  "AiExecutionObservation" should {
    "project only safe, normalized API execution evidence" in {
      val response = AiGenerateResponse(
        text = "This response must not be retained.",
        model = Some("gpt-5.6-terra"),
        metadata = Map(
          "ai.execution.provider" -> "openai",
          "ai.execution.mode" -> "remote",
          "ai.execution.engine" -> "responses",
          "ai.execution.purpose" -> "grounded-research",
          "ai.policy.application_purpose" -> "sanpomap-scenario-research",
          "ai.policy.runtime_profile" -> "commercial-thinking",
          "ai.policy.effective_execution_class" -> "advanced-thinking",
          "ai.execution.location" -> "remote",
          "ai.execution.strategy" -> "staged",
          "ai.execution.attempt_lineage" -> "1:openai:initial:success",
          "ai.execution.duration_millis" -> "1240",
          "ai.execution.tools" -> "web_search",
          "ai.execution.tool_result_summary" -> "web_search_calls=2",
          "ai.usage.input_tokens" -> "120",
          "ai.usage.input_tokens_source" -> "reported",
          "ai.usage.output_tokens" -> "80",
          "ai.usage.output_tokens_source" -> "reported",
          "ai.observation.identity.rate_schedule_snapshot" -> "sha256:openai-2026-07",
          "ai.accounting.cost_microunits" -> "340",
          "ai.accounting.cost_basis" -> "measured",
          "ai.execution.input_digest" -> "sha256:input",
          "ai.execution.output_digest" -> "sha256:output",
          "ai.limitation.codes" -> "usage_unavailable",
          "openai.request_id" -> "must-not-escape",
          "unsafe.raw_payload" -> "must-not-escape"
        )
      )

      val observation = AiExecutionObservation.from(response, _context)

      observation.identity("provider") shouldBe "openai"
      observation.measurements("provider_calls") shouldBe AiObservationMeasurement(
        AiObservationMeasurementState.Reported,
        Some(1),
        Some("attempt-lineage")
      )
      observation.measurements("monetary_cost_microunits") shouldBe AiObservationMeasurement(
        AiObservationMeasurementState.Reported,
        Some(340),
        Some("rate-schedule-observed-usage")
      )
      observation.providerStandardTools.requested shouldBe Vector("web_search")
      observation.providerStandardTools.admissionState shouldBe AiObservationMeasurementState.Unavailable
      observation.providerStandardTools.calls("web_search").value shouldBe Some(2)
      observation.providerStandardTools.chargeBasis("web_search") shouldBe AiObservationMeasurementState.Unavailable
      observation.cncfEvidence.sources shouldBe Vector("mcp:places.lookup", "operation:place-search")
      observation.safeFacts.values.mkString("\n") should not include response.text
      observation.safeFacts.values.mkString("\n") should not include "must-not-escape"
    }

    "publish a generic runtime handoff without application evidence fields" in {
      val response = AiGenerateResponse(
        "This response must not be retained.",
        metadata = Map(
          "ai.execution.provider" -> "gemma",
          "ai.execution.location" -> "local",
          "ai.execution.input_digest" -> "sha256:input",
          "ai.accounting.rate_schedule_id" -> "operator-private-rate-id"
        )
      )

      val facts = AiExecutionObservation.runtimeFacts(response)

      facts("ai.observation.identity.provider") shouldBe "gemma"
      facts("ai.observation.measurement.monetary_cost_microunits.state") shouldBe "not-applicable"
      facts.values.mkString("\n") should not include response.text
      facts.values.mkString("\n") should not include "operator-private-rate-id"
      facts.keys.exists(_.contains("reference")) shouldBe false
      facts.keys.exists(_.contains("assessment")) shouldBe false
    }

    "keep local and subscription CLI monetary cost distinct from zero" in {
      val local = AiExecutionObservation.from(
        AiGenerateResponse("unused", metadata = Map(
          "ai.execution.provider" -> "gemma",
          "ai.execution.location" -> "local"
        )),
        _context
      )
      val cli = AiExecutionObservation.from(
        AiGenerateResponse("unused", metadata = Map(
          "ai.execution.provider" -> "codex-cli",
          "ai.execution.location" -> "remote"
        )),
        _context
      )

      local.measurements("monetary_cost_microunits").state shouldBe AiObservationMeasurementState.NotApplicable
      local.measurements("monetary_cost_microunits").value shouldBe None
      cli.measurements("monetary_cost_microunits").state shouldBe AiObservationMeasurementState.Unavailable
      cli.measurements("monetary_cost_microunits").value shouldBe None
    }

    "represent admission cost as an estimate rather than a measured API cost" in {
      val observation = AiExecutionObservation.from(
        AiGenerateResponse("unused", metadata = Map(
          "ai.execution.provider" -> "openai",
          "ai.execution.location" -> "remote",
          "ai.accounting.cost_microunits" -> "900",
          "ai.accounting.cost_basis" -> "admission"
        )),
        _context
      )

      observation.measurements("monetary_cost_microunits") shouldBe AiObservationMeasurement(
        AiObservationMeasurementState.Estimated,
        Some(900),
        Some("admission-upper-bound")
      )
    }

    "reject references that could carry a payload or arbitrary URL" in {
      an[IllegalArgumentException] shouldBe thrownBy {
        AiObservationReferences(
          "textus-plan://safe",
          "textus-contract://safe",
          "textus-strategy://safe",
          "https://private.example/prompt",
          "sanpomap-evidence://safe",
          "sanpomap-metric://safe"
        )
      }
      AiObservationReferences.isSafe(" textus-plan://safe") shouldBe false
      AiObservationReferences.isSafe("https://private.example/safe") shouldBe false
    }
  }

  private def _context: AiExecutionObservationContext =
    AiExecutionObservationContext(
      cncfEvidenceSources = Vector("operation:place-search", "mcp:places.lookup"),
      assessment = AiObservationAssessment(
        AiObservationOutcome.Accepted,
        AiObservationValidity.Valid,
        AiObservationValidity.Valid,
        reasonCodes = Vector("schema-valid")
      ),
      references = AiObservationReferences(
        "textus-plan://sanpomap/scenario-research-v1",
        "textus-contract://sanpomap/scenario-research-v1",
        "textus-strategy://staged-question-list-v1",
        "textus-ai-evidence://run/case",
        "sanpomap-evidence://run/case",
        "sanpomap-metric://run/case"
      )
    )
}
