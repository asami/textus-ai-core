package org.simplemodeling.textus.ai

import org.goldenport.cncf.component.{ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ResolvedConfiguration}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 * @version Jul. 16, 2026
 * @author  ASAMI, Tomoharu
 */
final class ComponentFactorySpec extends AnyWordSpec with Matchers with GivenWhenThen {
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
  }
}
