package org.simplemodeling.textus.ai.runtime

import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.cncf.spi.ai.runner.{AiExecutionClass, AiRunnerRequirement, AiTool}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 * @version Jul. 22, 2026
 * @author  ASAMI, Tomoharu
 */
final class AiExecutionFactsSpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen {

  "AiExecutionFacts" should {
    "reserve normalized namespaces while retaining only allowlisted provider facts" in {
      Given("a selected Google runtime with spoofed normalized and raw provider metadata")
      val selection = SpiSelection(
        provider = Some("google"),
        mode = Some("remote"),
        engine = Some("gemini")
      )
      val requirement = AiRunnerRequirement(
        purpose = Some("car-review.documentation-clarity"),
        tools = Vector(AiTool.WebSearch, AiTool.UrlContext, AiTool.WebSearch),
        executionClass = Some(AiExecutionClass.StandardConsideration)
      )

      When("Textus AI normalizes the provider response metadata")
      val metadata = AiExecutionFacts.normalize(
        selection,
        requirement,
        Some("gemini-2.5-pro"),
        Map(
          AiExecutionFacts.PROVIDER -> "spoofed-provider",
          "google.response_id" -> "safe-response-id",
          "google.raw_response" -> "prompt and response payload"
        )
      )

      Then("the effective selection wins and only the safe provider fact remains available")
      metadata(AiExecutionFacts.PROVIDER) shouldBe "google"
      metadata(AiExecutionFacts.MODEL) shouldBe "gemini-2.5-pro"
      metadata(AiExecutionFacts.EXECUTION_CLASS) shouldBe "standard-consideration"
      metadata(AiExecutionFacts.TOOLS) shouldBe "url_context,web_search"
      metadata("google.response_id") shouldBe "safe-response-id"
      metadata(AiExecutionFacts.RESPONSE_ID) shouldBe "safe-response-id"
      metadata should not contain "google.raw_response"
    }

    "normalize provider response identity finish reason and usage without accepting reserved values" in {
      Given("a selected OpenAI runtime with provider-reported completion facts")
      val selection = SpiSelection(provider = Some("openai"))
      val providerMetadata = Map(
        AiExecutionFacts.FINISH_REASON -> "spoofed",
        "openai.response_id" -> "resp_123",
        "openai.finish_reason" -> "stop",
        "openai.usage.input_tokens" -> "41",
        "openai.usage.output_tokens" -> "17",
        "openai.usage.total_tokens" -> "58"
      )

      When("Textus AI normalizes the provider metadata")
      val metadata = AiExecutionFacts.normalize(
        selection,
        AiRunnerRequirement(),
        None,
        providerMetadata
      )

      Then("only the selected provider supplies the normalized response facts")
      metadata(AiExecutionFacts.RESPONSE_ID) shouldBe "resp_123"
      metadata(AiExecutionFacts.FINISH_REASON) shouldBe "stop"
      metadata(AiExecutionFacts.INPUT_TOKENS) shouldBe "41"
      metadata(AiExecutionFacts.INPUT_TOKENS_SOURCE) shouldBe "reported"
      metadata(AiExecutionFacts.OUTPUT_TOKENS) shouldBe "17"
      metadata(AiExecutionFacts.OUTPUT_TOKENS_SOURCE) shouldBe "reported"
      metadata(AiExecutionFacts.TOTAL_TOKENS) shouldBe "58"
      metadata(AiExecutionFacts.TOTAL_TOKENS_SOURCE) shouldBe "reported"
    }

    "normalize bounded provider tool results into a provider-neutral summary" in {
      Given("a Google response with safe tool counters and an unsafe raw tool value")
      val selection = SpiSelection(provider = Some("google"))
      val providerMetadata = Map(
        "google.google_search_calls" -> "2",
        "google.google_search_results" -> "5",
        "google.url_context_calls" -> "1",
        "google.url_citations" -> "4",
        "google.raw_tool_result" -> "https://secret.example/path"
      )

      When("Textus AI normalizes provider metadata")
      val metadata = AiExecutionFacts.normalize(
        selection,
        AiRunnerRequirement(tools = Vector(AiTool.WebSearch, AiTool.UrlContext)),
        None,
        providerMetadata
      )

      Then("only the safe numeric tool summary and allowlisted counters are retained")
      metadata(AiExecutionFacts.TOOL_RESULT_SUMMARY) shouldBe
        "google_search_calls=2;google_search_results=5;url_context_calls=1;url_citations=4"
      metadata("google.google_search_calls") shouldBe "2"
      metadata should not contain "google.raw_tool_result"
    }

    "retain Anthropic continuation summaries without provider payloads" in {
      Given("an Anthropic tool-loop response with safe counters and an unsafe result")
      val selection = SpiSelection(provider = Some("anthropic"))

      When("Textus AI normalizes the provider metadata")
      val metadata = AiExecutionFacts.normalize(
        selection,
        AiRunnerRequirement(),
        None,
        Map(
          "anthropic.tool_calls" -> "2",
          "anthropic.tool_turns" -> "2",
          "anthropic.mcp_calls" -> "1",
          "anthropic.operation_calls" -> "1",
          "anthropic.raw_tool_result" -> "sensitive result"
        )
      )

      Then("only safe continuation facts remain available")
      metadata("anthropic.tool_calls") shouldBe "2"
      metadata("anthropic.tool_turns") shouldBe "2"
      metadata("anthropic.mcp_calls") shouldBe "1"
      metadata("anthropic.operation_calls") shouldBe "1"
      metadata should not contain "anthropic.raw_tool_result"
    }

    "omit malformed or negative provider usage values" in {
      Given("a selected provider response with invalid usage values")
      val selection = SpiSelection(provider = Some("gemma"))

      When("Textus AI normalizes the provider metadata")
      val metadata = AiExecutionFacts.normalize(
        selection,
        AiRunnerRequirement(),
        None,
        Map(
          "gemma.usage.input_tokens" -> "-1",
          "gemma.usage.output_tokens" -> "unknown",
          "gemma.usage.total_tokens" -> " 12 "
        )
      )

      Then("only non-negative decimal usage facts are published")
      metadata should not contain AiExecutionFacts.INPUT_TOKENS
      metadata should not contain AiExecutionFacts.OUTPUT_TOKENS
      metadata(AiExecutionFacts.TOTAL_TOKENS) shouldBe "12"
      metadata(AiExecutionFacts.TOTAL_TOKENS_SOURCE) shouldBe "reported"
    }

    "prefer a provider-reported usage fact while retaining explicit estimates for missing fields" in {
      Given("a provider response with partial usage and a Textus admission estimate")
      val selection = SpiSelection(provider = Some("openai"))
      val estimated = AiUsageFacts(
        inputTokens = Some(AiUsageFact(40, AiUsageSource.Estimated)),
        cachedInputTokens = Some(AiUsageFact(5, AiUsageSource.Estimated)),
        outputTokens = Some(AiUsageFact(20, AiUsageSource.Estimated)),
        totalTokens = Some(AiUsageFact(60, AiUsageSource.Estimated))
      )

      When("Textus AI merges reported and estimated usage")
      val metadata = AiExecutionFacts.normalize(
        selection,
        AiRunnerRequirement(),
        None,
        Map("openai.usage.input_tokens" -> "41", "openai.usage.reasoning_tokens" -> "7"),
        estimatedusage = estimated
      )

      Then("reported values win only for their fields and the remaining estimates retain their source")
      metadata(AiExecutionFacts.INPUT_TOKENS) shouldBe "41"
      metadata(AiExecutionFacts.INPUT_TOKENS_SOURCE) shouldBe "reported"
      metadata(AiExecutionFacts.REASONING_TOKENS) shouldBe "7"
      metadata(AiExecutionFacts.REASONING_TOKENS_SOURCE) shouldBe "reported"
      metadata(AiExecutionFacts.CACHED_INPUT_TOKENS) shouldBe "5"
      metadata(AiExecutionFacts.CACHED_INPUT_TOKENS_SOURCE) shouldBe "estimated"
      metadata(AiExecutionFacts.OUTPUT_TOKENS) shouldBe "20"
      metadata(AiExecutionFacts.OUTPUT_TOKENS_SOURCE) shouldBe "estimated"
      metadata(AiExecutionFacts.TOTAL_TOKENS) shouldBe "60"
      metadata(AiExecutionFacts.TOTAL_TOKENS_SOURCE) shouldBe "estimated"
    }

    "record unsupported lifecycle boundaries as stable limitation codes" in {
      Given("normalized metadata with a provider-specific limitation")
      val metadata = Map(AiExecutionFacts.LIMITATION_CODES -> "usage_unavailable")

      When("Textus AI adds its lifecycle limitations")
      val normalized = AiExecutionFacts.lifecycleLimitations(metadata)

      Then("the limitations are de-duplicated and sorted")
      normalized(AiExecutionFacts.LIMITATION_CODES) shouldBe
        "cancellation_not_propagated,concurrency_not_enforced,rate_schedule_unavailable,usage_unavailable"
    }

    "record an unverified output limit when a bounded provider response omits usage" in {
      Given("a bounded policy response without an output-token measurement")
      val metadata = Map(AiExecutionFacts.POLICY_MAX_OUTPUT_TOKENS -> "120")

      When("Textus AI adds lifecycle limitations")
      val normalized = AiExecutionFacts.lifecycleLimitations(metadata)

      Then("the response does not claim an independently verified maximum")
      normalized(AiExecutionFacts.LIMITATION_CODES) shouldBe
        "cancellation_not_propagated,concurrency_not_enforced,output_limit_not_verified,rate_schedule_unavailable,usage_unavailable"
    }

    "reject provider reasoning usage above the configured maximum" in {
      Given("a Google response with provider-reported reasoning usage")
      val selection = SpiSelection(provider = Some("google"))

      When("the provider reports reasoning usage above the effective policy")
      val result = AiExecutionFacts.validateMaxReasoningTokens(
        selection,
        Some(3),
        Map("google.usage.reasoning_tokens" -> "4")
      )

      Then("the completed provider response is rejected structurally")
      result.isFaillure shouldBe true
      result.toString should include ("reasoning tokens exceeded maximum: limit=3 actual=4")
    }

    "record an unverified reasoning limit when a bounded response omits reasoning usage" in {
      Given("a bounded policy response without a reasoning-token measurement")
      val metadata = Map(AiExecutionFacts.POLICY_MAX_REASONING_TOKENS -> "120")

      When("Textus AI adds lifecycle limitations")
      val normalized = AiExecutionFacts.lifecycleLimitations(metadata)

      Then("the response does not claim an independently verified reasoning maximum")
      normalized(AiExecutionFacts.LIMITATION_CODES) shouldBe
        "cancellation_not_propagated,concurrency_not_enforced,rate_schedule_unavailable,reasoning_limit_not_verified,usage_unavailable"
    }

    "keep a rate schedule identifier CallTree-only while publishing safe accounting identities" in {
      Given("opaque policy and rate identities supplied by accounting policy")
      val accounting = AiAccountingFacts(
        policySnapshotId = Some("sha256:policy"),
        rateScheduleId = Some("rates-2026-07"),
        providerRequestId = Some("request_123"),
        limitations = Vector("pricing_estimated")
      )

      When("Textus AI normalizes the response and prepares CallTree attributes")
      val metadata = AiExecutionFacts.normalize(
        SpiSelection(),
        AiRunnerRequirement(),
        None,
        Map.empty,
        accountingfacts = accounting
      )
      val calltree = AiExecutionFacts.calltreeMetadata(metadata, accounting)

      Then("the response excludes the operator rate identity but CallTree retains it safely")
      metadata(AiExecutionFacts.POLICY_SNAPSHOT_ID) shouldBe "sha256:policy"
      metadata(AiExecutionFacts.PROVIDER_REQUEST_ID) shouldBe "request_123"
      metadata(AiExecutionFacts.LIMITATION_CODES) shouldBe "pricing_estimated"
      metadata should not contain AiExecutionFacts.RATE_SCHEDULE_ID
      calltree(s"response_metadata.${AiExecutionFacts.RATE_SCHEDULE_ID}") shouldBe "rates-2026-07"
    }

    "derive a deterministic opaque policy snapshot from effective policy facts" in {
      Given("two equivalent effective policy fact maps in different insertion orders")
      val first = Map(
        AiExecutionFacts.POLICY_MAX_OUTPUT_TOKENS -> "120",
        AiExecutionFacts.POLICY_EFFECTIVE_EXECUTION_CLASS -> "standard-work"
      )
      val second = Map(
        AiExecutionFacts.POLICY_EFFECTIVE_EXECUTION_CLASS -> "standard-work",
        AiExecutionFacts.POLICY_MAX_OUTPUT_TOKENS -> "120"
      )

      When("Textus AI derives the policy snapshot identity")
      val snapshot = AiExecutionFacts.policySnapshotId(first)

      Then("ordering does not affect the identity but a policy change does")
      snapshot shouldBe AiExecutionFacts.policySnapshotId(second)
      snapshot should not be AiExecutionFacts.policySnapshotId(
        first.updated(AiExecutionFacts.POLICY_MAX_OUTPUT_TOKENS, "121")
      )
      snapshot.getOrElse(fail("effective policy facts require a snapshot")) should startWith ("sha256:")
    }

    "record unavailable usage and pricing without synthesizing a numeric value" in {
      Given("a completed execution with no provider usage or rate schedule")

      When("Textus AI records lifecycle limitations")
      val metadata = AiExecutionFacts.lifecycleLimitations(Map.empty)

      Then("the unknown facts remain absent and their limitations are explicit")
      metadata should not contain AiExecutionFacts.INPUT_TOKENS
      metadata(AiExecutionFacts.LIMITATION_CODES) shouldBe
        "cancellation_not_propagated,concurrency_not_enforced,rate_schedule_unavailable,usage_unavailable"
    }

    "omit execution facts that are unknown instead of synthesizing values" in {
      Given("an unresolved selection with no provider response model or optional requirement")
      val selection = SpiSelection()

      When("Textus AI normalizes an empty provider metadata map")
      val metadata = AiExecutionFacts.normalize(
        selection,
        AiRunnerRequirement(),
        None,
        Map.empty
      )

      Then("unknown execution facts are absent")
      metadata shouldBe empty
    }
  }
}
