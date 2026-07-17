package org.simplemodeling.textus.ai

import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ResolvedConfiguration}
import org.goldenport.configuration.ConfigurationValue
import org.goldenport.cncf.spi.ai.runner.AiRunner
import org.simplemodeling.textus.ai.runtime.TextusAiRunnerProvider
import org.scalatest.GivenWhenThen
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 * @version Jul. 17, 2026
 * @author  ASAMI, Tomoharu
 */
final class ComponentFactorySpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen
  with OptionValues {
  "ComponentFactory" should {
    "publish the artifact component name required by an assembly descriptor" in {
      Given("a Textus AI Runtime component factory")
      val factory = new ComponentFactory()
      val subsystem = new Subsystem(
        name = "textus-ai-runtime-spec",
        configuration = ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty)
      )

      When("the factory creates its primary component")
      val bundle = factory.create(ComponentCreate(subsystem, ComponentOrigin.Main))

      Then("the component core matches the CAR descriptor component name")
      bundle.primary.core.name shouldBe "textus-ai-runtime"
    }

    "canonicalize the codex-cli provider alias before deriving defaults" in {
      Given("a configured and enabled Codex CLI provider alias")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.provider" -> ConfigurationValue.StringValue("codex-cli"),
          "textus.ai.codex.enabled" -> ConfigurationValue.StringValue("true")
        )),
        ConfigurationTrace.empty
      )
      val component = ComponentFactory.configureRuntimeSpi(new Component() {}, Some(configuration))

      When("the component publishes its AI runner SPI provider")
      val provider = component.port.get[TextusAiRunnerProvider]

      Then("the default selection resolves to the Codex CLI engine")
      provider should not be empty
      provider.value.supports(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ) shouldBe true
    }
  }
}
