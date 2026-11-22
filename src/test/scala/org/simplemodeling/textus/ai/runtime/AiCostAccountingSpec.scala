package org.simplemodeling.textus.ai.runtime

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 18, 2026
 * @version Nov. 23, 2026
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

  "AiTaskRunner" should {
    "require verified entitlement and keep overage separate from preference" in {
      Given("a bounded request and candidates with different quota evidence")
      val request = AiTaskRequest("r", "review", AiTaskPackage("pkg", "source", "input"),
        "schema", Set("record"), Set.empty, "standard", 1000L, 10L, true, false)
      val unknown = AiTaskCandidate("a", "m", Set("record"), Set.empty, None, 2L, request, Some(1L))
      val included = AiTaskCandidate("b", "m", Set("record"), Set.empty, Some(2L), 2L, request, None)
      var calls = Vector.empty[String]
      val receipt = AiTaskReceipt(Some("observed"), Some("actual-model"), Some("remote-id"), Some("trace"), AiTaskUsage.Measured(2L, None))
      val invoker = new AiTaskInvoker {
        def preflight(request: AiTaskRequest, candidate: AiTaskCandidate): Either[String, AiTaskInvocationCapability] =
          Right(AiTaskInvocationCapability(request, candidate, request.input.content.length,
            request.maxOutputCharacters, request.budgetMicrounits, request.maxLatencyMillis, true, true, true))
        def invoke(request: AiTaskRequest, candidate: AiTaskCandidate): AiTaskDispatch = {
          calls = calls :+ candidate.provider
          AiTaskDispatch.Completed("result", receipt)
        }
      }
      When("selection considers unknown quota and a verified included allocation")
      val result = AiTaskRunner.run(request, Vector(unknown, included), invoker)
      Then("only the included candidate is called and actual facts are retained")
      calls shouldBe Vector("b")
      AiTaskRunner.run(request, Vector(included, included), invoker) shouldBe
        AiTaskResult.Declined(request, "Ambiguous provider and model candidate")
      AiTaskRunner.run(request.copy(id = "other"), Vector(included), invoker)
        .isInstanceOf[AiTaskResult.Declined] shouldBe true
      result shouldBe AiTaskResult.Completed(request, AiTaskAttempt(1, included, AiTaskDispatch.Completed("result", receipt)))
      AiTaskRunner.run(request.copy(allowPaidOverage = true), Vector(unknown.copy(verifiedForRequest = request.copy(allowPaidOverage = true))), invoker) shouldBe
        AiTaskResult.Declined(request.copy(allowPaidOverage = true), "No candidate has verified eligibility and budget")
      val paidrequest = request.copy(allowPaidOverage = true)
      val overage = unknown.copy(verifiedIncludedUnits = Some(0L), verifiedForRequest = paidrequest, verifiedPaidEntitlement = true)
      AiTaskRunner.run(request, Vector(overage.copy(verifiedForRequest = request)), invoker).isInstanceOf[AiTaskResult.Declined] shouldBe true
      val zerobudget = paidrequest.copy(budgetMicrounits = 0)
      AiTaskRunner.run(zerobudget, Vector(overage.copy(verifiedForRequest = zerobudget)), invoker)
        .isInstanceOf[AiTaskResult.Declined] shouldBe true
      AiTaskRunner.run(paidrequest, Vector(overage.copy(verifiedPaidEntitlement = false)), invoker)
        .isInstanceOf[AiTaskResult.Declined] shouldBe true
      AiTaskRunner.run(paidrequest, Vector(overage), invoker)
        .isInstanceOf[AiTaskResult.Completed] shouldBe true
      val paidincluded = included.copy(verifiedForRequest = paidrequest)
      AiTaskRunner.run(paidrequest, Vector(overage, paidincluded), invoker)
      calls.last shouldBe "b"
    }

    "skip an included candidate whose known cost exceeds the budget" in {
      val request = AiTaskRequest("budget", "review", AiTaskPackage("p", "v", "x"), "s", Set.empty,
        Set.empty, "standard", 100L, 10L, true, true)
      val expensive = AiTaskCandidate("a", "m", Set.empty, Set.empty, Some(1L), 1L, request, Some(11L))
      val alternative = AiTaskCandidate("b", "m", Set.empty, Set.empty, Some(0L), 1L, request, Some(5L), true)
      var preflights = Vector.empty[String]
      var calls = Vector.empty[String]
      val receipt = AiTaskReceipt(None, None, None, None, AiTaskUsage.Unknown)
      val invoker = new AiTaskInvoker {
        def preflight(r: AiTaskRequest, c: AiTaskCandidate): Either[String, AiTaskInvocationCapability] = {
          preflights = preflights :+ c.provider
          Right(AiTaskInvocationCapability(r, c, r.input.content.length, r.maxOutputCharacters,
            r.budgetMicrounits, r.maxLatencyMillis, true, true, true))
        }
        def invoke(r: AiTaskRequest, c: AiTaskCandidate): AiTaskDispatch = {
          calls = calls :+ c.provider
          AiTaskDispatch.Completed("result", receipt)
        }
      }
      AiTaskRunner.run(request, Vector(expensive, alternative.copy(verifiedPaidEntitlement = false)), invoker) shouldBe
        AiTaskResult.Declined(request, "No candidate has verified eligibility and budget")
      preflights shouldBe Vector.empty
      calls shouldBe Vector.empty
      val disallowed = request.copy(allowPaidOverage = false)
      AiTaskRunner.run(disallowed, Vector(expensive.copy(verifiedForRequest = disallowed),
        alternative.copy(verifiedForRequest = disallowed)), invoker) shouldBe
        AiTaskResult.Declined(disallowed, "No candidate has verified eligibility and budget")
      preflights shouldBe Vector.empty
      calls shouldBe Vector.empty
      AiTaskRunner.run(request, Vector(expensive, alternative), invoker) shouldBe
        AiTaskResult.Completed(request, AiTaskAttempt(1, alternative, AiTaskDispatch.Completed("result", receipt)))
      preflights shouldBe Vector("b")
      calls shouldBe Vector("b")
    }

    "stop after an uncertain dispatch without fallback" in {
      Given("two eligible providers and an invoker with an uncertain outcome")
      val request = AiTaskRequest("r", "review", AiTaskPackage("p", "v", "x"), "s", Set.empty,
        Set.empty, "standard", 1L, 0L, true, false)
      val first = AiTaskCandidate("a", "m", Set.empty, Set.empty, Some(1L), 1L, request, None)
      val second = first.copy(provider = "b")
      var calls = 0
      val invoker = new AiTaskInvoker {
        def preflight(request: AiTaskRequest, candidate: AiTaskCandidate): Either[String, AiTaskInvocationCapability] =
          Right(AiTaskInvocationCapability(request, candidate, request.input.content.length,
            request.maxOutputCharacters, request.budgetMicrounits, request.maxLatencyMillis, true, true, true))
        def invoke(request: AiTaskRequest, candidate: AiTaskCandidate): AiTaskDispatch = {
          calls += 1
          AiTaskDispatch.Uncertain("timeout", AiTaskReceipt(None, None, None, None, AiTaskUsage.Unknown))
        }
      }
      When("the first provider may have received the request")
      val result = AiTaskRunner.run(request, Vector(second, first), invoker)
      Then("one attempt and the uncertainty are returned to the consumer")
      calls shouldBe 1
      result.isInstanceOf[AiTaskResult.Uncertain] shouldBe true
    }
    "refuse an adapter that cannot attest exact bounded execution before dispatch" in {
      val request = AiTaskRequest("bounded", "review", AiTaskPackage("p", "v", "x"), "s", Set.empty,
        Set.empty, "standard", 100L, 0L, true, false, 8)
      val candidate = AiTaskCandidate("fixture", "model", Set.empty, Set.empty, Some(1L), 1L, request, None)
      var calls = 0
      val unsupported = new AiTaskInvoker {
        def preflight(r: AiTaskRequest, c: AiTaskCandidate): Either[String, AiTaskInvocationCapability] =
          Right(AiTaskInvocationCapability(r, c, 1, 8, 0L, 100L, true, true, false))
        def invoke(r: AiTaskRequest, c: AiTaskCandidate): AiTaskDispatch = {
          calls += 1
          AiTaskDispatch.Failed("must not call", AiTaskReceipt(None, None, None, None, AiTaskUsage.Unknown))
        }
      }
      AiTaskRunner.run(request, Vector(candidate), unsupported).isInstanceOf[AiTaskResult.Declined] shouldBe true
      calls shouldBe 0
    }

  }

}
