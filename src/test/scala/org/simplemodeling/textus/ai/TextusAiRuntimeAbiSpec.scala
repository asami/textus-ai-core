package org.simplemodeling.textus.ai

import org.goldenport.cncf.entity.runtime.{
  EntityKind,
  EntityMemoryPolicy,
  PartitionStrategy
}
import org.goldenport.cncf.security.{
  EntityApplicationDomain,
  EntityOperationKind,
  EntityUsageKind
}
import org.simplemodeling.model.datatype.EntityCollectionId
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 26, 2026
 * @version Jul. 27, 2026
 * @author  ASAMI, Tomoharu
 */
final class TextusAiRuntimeAbiSpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen {
  private val _descriptor_module_class =
    Class.forName("org.goldenport.cncf.entity.runtime.EntityRuntimeDescriptor$")
  private val _generated_car_entity_descriptor_signature = Vector[Class[?]](
    classOf[String],
    classOf[EntityCollectionId],
    classOf[EntityMemoryPolicy],
    classOf[PartitionStrategy],
    java.lang.Integer.TYPE,
    java.lang.Integer.TYPE,
    classOf[Option[?]],
    classOf[Option[?]],
    classOf[Option[?]],
    classOf[Option[?]],
    classOf[Vector[?]],
    classOf[Vector[?]],
    classOf[EntityKind],
    classOf[EntityUsageKind],
    classOf[EntityOperationKind],
    classOf[EntityApplicationDomain],
    java.lang.Boolean.TYPE,
    java.lang.Boolean.TYPE,
    classOf[Option[?]],
    classOf[Option[?]],
    classOf[Option[?]]
  )

  "Textus AI packaged runtime ABI" should {
    "require the CNCF EntityRuntimeDescriptor constructor used by generated CAR descriptors" in {
      Given("Textus AI is packaged with generated CAR descriptors")
      val methods = _descriptor_module_class.getMethods.toVector

      When("Sanpomap assembly loads Textus AI with the CNCF runtime")
      val methodoption = methods.find { method =>
        method.getName == "apply" &&
        method.getParameterTypes.toVector == _generated_car_entity_descriptor_signature
      }

      Then("the runtime dependency exposes the binary entry point used by generated CAR loading")
      methodoption.isDefined shouldBe true
    }
  }
}
