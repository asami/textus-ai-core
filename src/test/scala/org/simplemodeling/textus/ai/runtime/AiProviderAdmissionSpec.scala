package org.simplemodeling.textus.ai.runtime

import org.goldenport.Consequence
import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.protocol.Property
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 18, 2026
 * @version Jul. 18, 2026
 * @author  ASAMI, Tomoharu
 */
final class AiProviderAdmissionSpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen {

  "AiProviderAdmission" should {
    "admit provider-neutral web tools only for Google and OpenAI" in {
      Given("a request that selects URL context and web search")
      val properties = Vector(Property("ai.tools", "url_context,web_search", None))

      When("the selected provider is Google or OpenAI")
      val results = Vector("google", "openai").map { provider =>
        AiProviderAdmission.validate(SpiSelection(provider = Some(provider)), properties)
      }

      Then("the provider can be bound")
      results.foreach(_.toOption shouldBe Some(()))
    }

    "reject tools that the selected provider cannot execute" in {
      Given("a request that requires URL context")
      val properties = Vector(Property("ai.tools", "url_context", None))

      When("Gemma, Codex, or an unknown provider is selected")
      val results = Vector("gemma", "codex", "unconfigured").map { provider =>
        provider -> AiProviderAdmission.validate(SpiSelection(provider = Some(provider)), properties)
      }

      Then("admission fails before any provider binding")
      results.foreach { case (provider, result) =>
        result shouldBe a[Consequence.Failure[_]]
        result match {
          case Consequence.Failure(conclusion) =>
            conclusion.display should include (s"provider '$provider'")
          case _ =>
            fail("unsupported tools must fail admission")
        }
      }
    }

    "reject unknown tools and Codex model overrides" in {
      Given("an invalid logical tool and a Codex model override")
      val unknown = AiProviderAdmission.validate(
        SpiSelection(provider = Some("google")),
        Vector(Property("ai.tools", "unconfigured-tool", None))
      )
      val codexmodel = AiProviderAdmission.validate(
        SpiSelection(provider = Some("codex-cli")),
        Vector(Property("ai.model", "codex-model", None))
      )

      When("admission validates provider-compatible request properties")

      Then("both invalid requests fail structurally")
      unknown shouldBe a[Consequence.Failure[_]]
      codexmodel shouldBe a[Consequence.Failure[_]]
      unknown match {
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("Unknown AI tools")
        case _ =>
          fail("unknown logical tools must fail admission")
      }
      codexmodel match {
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("model override is not supported")
        case _ =>
          fail("Codex model overrides must fail admission")
      }
    }
  }
}
