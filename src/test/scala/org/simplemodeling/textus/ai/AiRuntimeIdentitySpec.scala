package org.simplemodeling.textus.ai

import java.nio.file.{Path, Paths}
import org.goldenport.cncf.component.Component
import org.goldenport.record.{Record, RecordFormat}
import org.goldenport.record.io.RecordSourceLoader
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.textus.airuntime.AiRuntimeComponent

/*
 * @since   Aug. 21, 2026
 * @version Aug. 21, 2026
 * @author  ASAMI, Tomoharu
 */
final class AiRuntimeIdentitySpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen {

  "AI runtime canonical identity" should {
    "expose the canonical generated component class" in {
      Given("the generated component projection selected by the CAR source")
      val generatedclass = classOf[AiRuntimeComponent]

      When("the generated class identity is inspected")
      val packagename = generatedclass.getPackageName
      val classname = generatedclass.getSimpleName

      Then("the canonical generated package and class name are exposed")
      packagename shouldBe "org.simplemodeling.textus.airuntime"
      classname shouldBe "AiRuntimeComponent"
    }

    "load the canonical component through every handwritten entry point" in {
      Given("the runtime factory and its two handwritten loading entry points")
      val entrypoints: Vector[(String, () => Component)] = Vector(
        "ComponentFactory" -> (() => ComponentFactory.createStandalone()),
        "GeneratedDomainComponentLoader" -> (() => GeneratedDomainComponentLoader.createStandalone()),
        "TextusAiComponentMain" -> (() => TextusAiComponentMain.createComponent())
      )

      When("each entry point constructs the runtime component")
      val components = entrypoints.map { case (entrypoint, construct) =>
        entrypoint -> construct()
      }

      Then("each component remains assignable to the canonical generated type and qualified core")
      components.foreach { case (entrypoint, component) =>
        withClue(entrypoint) {
          component shouldBe a[AiRuntimeComponent]
          component.core.name shouldBe "org.simplemodeling.textus.AiRuntime"
        }
      }
    }

    "retain canonical identity in machine-readable CAR source" in {
      Given("checked-in project, CAR descriptor, and ABI manifest sources")
      val project = _load_record(Paths.get("project.yaml"), RecordFormat.Yaml)
      val descriptor = _load_record(
        Paths.get("src/main/car/component-descriptor.json"),
        RecordFormat.Json
      )
      val abi = _load_record(Paths.get("src/main/car/abi-manifest.json"), RecordFormat.Json)

      When("the structured CAR sources are decoded")
      val projectidentity = Vector(
        _value(project, "project", "namespace"),
        _value(project, "project", "id"),
        _value(project, "project", "name"),
        _value(project, "project", "scalaPackage"),
        _value(project, "project", "component", "name"),
        _value(project, "project", "component", "className"),
        _value(project, "project", "component", "version")
      )

      Then("the source metadata, descriptor, and ABI agree on the SNAPSHOT identity")
      projectidentity shouldBe Vector(
        Some("org.simplemodeling.textus"),
        Some("AiRuntime"),
        Some("textus-ai-runtime"),
        Some("org.simplemodeling.textus.airuntime"),
        Some("AiRuntime"),
        Some("AiRuntimeComponent"),
        Some("0.2.1-SNAPSHOT")
      )
      _value(descriptor, "component", "namespace") shouldBe Some("org.simplemodeling.textus")
      _value(descriptor, "component", "id") shouldBe Some("AiRuntime")
      _value(descriptor, "component", "version") shouldBe Some("0.2.1-SNAPSHOT")
      _value(abi, "component", "namespace") shouldBe Some("org.simplemodeling.textus")
      _value(abi, "component", "id") shouldBe Some("AiRuntime")
      _value(abi, "component", "version") shouldBe Some("0.2.1-SNAPSHOT")
    }
  }

  private def _load_record(path: Path, format: RecordFormat): Record = {
    val source = scala.io.Source.fromFile(path.toFile, "UTF-8")
    try RecordSourceLoader.load(source.mkString, format).toOption.get
    finally source.close()
  }

  private def _value(record: Record, path: String*): Option[String] =
    path.toVector.foldLeft(Option(record): Option[Any]) {
      case (Some(value: Record), key) => value.getAny(key)
      case _ => None
    }.map(_.toString)
}
