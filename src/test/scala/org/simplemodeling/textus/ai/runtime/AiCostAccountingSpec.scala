package org.simplemodeling.textus.ai.runtime

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 18, 2026
 * @version Jul. 18, 2026
 * @author  ASAMI, Tomoharu
 */
final class AiCostAccountingSpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen {

  "AiCostAccounting" should {
    "round admission and measured costs upward in integer microunits" in {
      Given("an operator rate schedule with separate input, cached, and output rates")
      val schedule = AiRateSchedule(
        "test-rates",
        inputMicrounitsPerMillion = 1500000L,
        cachedInputMicrounitsPerMillion = 500000L,
        outputMicrounitsPerMillion = 2000000L,
        reasoningMicrounitsPerMillion = 0L
      )

      When("Textus calculates bounded and provider-reported usage costs")
      val admission = AiCostAccounting.admissionC(
        schedule,
        AiInputTokenEstimator.generate("abc"),
        maxOutputTokens = 2,
        maxReasoningTokens = 0
      ).toOption.get
      val measured = AiCostAccounting.measuredC(
        schedule,
        inputTokens = 3,
        cachedInputTokens = 1,
        outputTokens = 2,
        reasoningTokens = 0
      ).toOption.get

      Then("the upper bound uses the more expensive input rate and measured cached usage uses its own rate")
      admission.inputMicrounits shouldBe 5L
      admission.outputMicrounits shouldBe 4L
      admission.upperBoundMicrounits shouldBe 9L
      measured shouldBe 8L
    }

    "reject provider usage that cannot be charged safely" in {
      Given("a schedule and an impossible cached-input response")
      val schedule = AiRateSchedule("test-rates", 1L, 1L, 1L, 0L)

      When("cached tokens exceed input tokens")
      val result = AiCostAccounting.measuredC(schedule, 1L, 2L, 1L, 0L)

      Then("cost accounting returns a structured failure rather than fabricating a charge")
      result.isFaillure shouldBe true
      result.toString should include ("cached input tokens exceed input tokens")
    }
  }
}
