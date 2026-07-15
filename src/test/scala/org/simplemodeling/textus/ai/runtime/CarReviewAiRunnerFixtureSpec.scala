package org.simplemodeling.textus.ai.runtime

import org.goldenport.Consequence
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.ai.runner.*
import org.goldenport.protocol.Property
import org.goldenport.record.Record
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 * @version Jul. 16, 2026
 * @author  ASAMI, Tomoharu
 */
final class CarReviewAiRunnerFixtureSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  import CarReviewAiRunnerFixture.*

  "CarReviewAiRunnerFixture" should {
    "produce a schema-shaped deterministic candidate through the CNCF AI runner contract" in {
      Given("a CAR Review fixture and a review-shaped record schema")
      given ExecutionContext = ExecutionContext.create()
      val fixture = new CarReviewAiRunnerFixture
      val schema = Record.dataAuto("required" -> Vector("findings"))

      When("record generation is requested without a scenario override")
      val response = fixture.generateRecord(AiRecordRequest("review the component", schema)).toOption.get

      Then("the candidate and normalized execution facts are stable")
      response.record shouldBe Candidate
      response.model shouldBe Some(Model)
      response.metadata("ai.execution.provider") shouldBe "fixture"
      response.metadata("ai.execution.mode") shouldBe "deterministic"
      response.metadata("ai.usage.request_count") shouldBe "1"
    }

    "publish an explicit limitation outcome without inventing usage or provider identity" in {
      Given("a fixture request that models unknown provider facts")
      given ExecutionContext = ExecutionContext.create()
      val fixture = new CarReviewAiRunnerFixture

      When("generation is requested with the unknown scenario")
      val response = fixture.generate(AiGenerateRequest("review", properties = _scenario("unknown"))).toOption.get

      Then("the limitation codes are stable and the response remains deterministic")
      response.text shouldBe "fixture:review-unknown"
      response.metadata("ai.limitation.codes") shouldBe "provider_identity_unavailable,usage_unavailable"
      response.metadata should not contain "ai.execution.provider"
      response.metadata should not contain "ai.usage.request_count"
    }

    "make deterministic provider failures observable without network access" in {
      Given("a fresh fixture for each provider outcome")
      given ExecutionContext = ExecutionContext.create()
      val schema = Record.dataAuto("required" -> Vector("findings"))
      val scenarios = Vector(
        "malformed" -> "malformed structured review output",
        "empty" -> "empty structured review output",
        "unavailable" -> "provider unavailable",
        "quota" -> "quota exhausted",
        "timeout" -> "provider timeout",
        "cancelled" -> "cancellation is not propagated"
      )

      When("each failure scenario is requested")
      val results = scenarios.map { case (scenario, _) =>
        scenario -> (
          (new CarReviewAiRunnerFixture)
            .generateRecord(AiRecordRequest("review", schema, properties = _scenario(scenario)))
        )
      }

      Then("each scenario has an explicit and stable failure")
      results.zip(scenarios).foreach { case ((scenario, result), (_, expected)) =>
        withClue(s"scenario=$scenario ") {
          result shouldBe a[Consequence.Failure[_]]
          result match {
            case Consequence.Failure(conclusion) => conclusion.display should include(expected)
            case _ => fail("fixture failure was expected")
          }
        }
      }
    }

    "retry deterministically once before returning a schema-shaped candidate" in {
      Given("a fixture configured for retry-then-success")
      given ExecutionContext = ExecutionContext.create()
      val fixture = new CarReviewAiRunnerFixture
      val request = AiRecordRequest(
        "review",
        Record.dataAuto("required" -> Vector("findings")),
        properties = _scenario("retry-then-success")
      )

      When("the same request is attempted twice")
      val first = fixture.generateRecord(request)
      val second = fixture.generateRecord(request)

      Then("the first attempt exposes the timeout and the second attempt succeeds")
      first shouldBe a[Consequence.Failure[_]]
      second.toOption.get.record shouldBe Candidate
      second.toOption.get.metadata("ai.execution.retry_count") shouldBe "1"
    }
  }

  private def _scenario(value: String): Vector[Property] =
    Vector(Property(ScenarioProperty, value, None))
}
