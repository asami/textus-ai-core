package org.simplemodeling.textus.ai.runtime

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 18, 2026
 * @version Jul. 18, 2026
 * @author  ASAMI, Tomoharu
 */
final class AiInputTokenEstimatorSpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen {

  "AiInputTokenEstimator" should {
    "measure generate and record input by UTF-8 bytes" in {
      Given("ASCII and multi-byte prompts")

      When("Textus AI derives admission estimates")
      val generated = AiInputTokenEstimator.generate("abc")
      val recorded = AiInputTokenEstimator.record("a\u3042")

      Then("the estimate is deterministic and source-qualified")
      generated.tokens shouldBe 3L
      generated.payloadBytes shouldBe 3L
      generated.envelopeTokens shouldBe 0L
      recorded.tokens shouldBe 4L
      recorded.payloadBytes shouldBe 4L
      recorded.usageFacts.inputTokens.map(_.source) shouldBe Some(AiUsageSource.Estimated)
    }

    "add a fixed bounded envelope for normalized chat messages" in {
      Given("two normalized chat messages")

      When("Textus AI derives the admission estimate")
      val estimate = AiInputTokenEstimator.chat("user: a\nassistant: \u3042", 2)

      Then("the estimate contains the UTF-8 payload and fixed per-message envelope")
      estimate.payloadBytes shouldBe 22L
      estimate.messageCount shouldBe 2
      estimate.envelopeTokens shouldBe 32L
      estimate.tokens shouldBe 54L
    }
  }
}
