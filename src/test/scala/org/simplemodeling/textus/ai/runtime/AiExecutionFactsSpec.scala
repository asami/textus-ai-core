package org.simplemodeling.textus.ai.runtime

import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.cncf.spi.ai.runner.{AiRunnerRequirement, AiTool}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 * @version Jul. 16, 2026
 * @author  ASAMI, Tomoharu
 */
final class AiExecutionFactsSpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen {

  "AiExecutionFacts" should {
    "reserve normalized namespaces for Textus AI while preserving provider metadata" in {
      Given("a selected Google runtime and provider metadata with a spoofed normalized value")
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
          "google.response_id" -> "safe-response-id"
        )
      )

      Then("the effective selection wins and foreign provider facts remain available")
      metadata(AiExecutionFacts.PROVIDER) shouldBe "google"
      metadata(AiExecutionFacts.MODEL) shouldBe "gemini-2.5-pro"
      metadata(AiExecutionFacts.TOOLS) shouldBe "url_context,web_search"
      metadata("google.response_id") shouldBe "safe-response-id"
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
