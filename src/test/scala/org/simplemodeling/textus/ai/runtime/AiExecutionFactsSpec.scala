package org.simplemodeling.textus.ai.runtime

import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.cncf.spi.ai.runner.{AiRunnerRequirement, AiTool}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 * @version Jul. 18, 2026
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
        tools = Vector(AiTool.WebSearch, AiTool.UrlContext, AiTool.WebSearch)
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
      metadata(AiExecutionFacts.OUTPUT_TOKENS) shouldBe "17"
      metadata(AiExecutionFacts.TOTAL_TOKENS) shouldBe "58"
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
    }

    "record unsupported lifecycle boundaries as stable limitation codes" in {
      Given("normalized metadata with a provider-specific limitation")
      val metadata = Map(AiExecutionFacts.LIMITATION_CODES -> "usage_unavailable")

      When("Textus AI adds its lifecycle limitations")
      val normalized = AiExecutionFacts.lifecycleLimitations(metadata)

      Then("the limitations are de-duplicated and sorted")
      normalized(AiExecutionFacts.LIMITATION_CODES) shouldBe
        "cancellation_not_propagated,concurrency_not_enforced,usage_unavailable"
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
