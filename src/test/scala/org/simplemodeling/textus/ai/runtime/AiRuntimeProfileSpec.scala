package org.simplemodeling.textus.ai.runtime

import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.goldenport.cncf.spi.ai.runner.{AiExecutionClass, AiRunnerApplicationPurpose, AiRunnerApplicationPurposePolicy, AiRunnerApplicationPurposeRegistration, AiRunnerRequirement}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 18, 2026
 * @version Jul. 19, 2026
 * @author  ASAMI, Tomoharu
 */
final class AiRuntimeProfileSpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen {

  "AiProfileConfig runtime profiles" should {
    "resolve every standard purpose through the built-in Codex CLI defaults" in {
      Given("a Codex CLI runtime profile with no model-profile configuration")
      val profiles = _profiles("textus.ai.profile" -> "codex-cli")

      When("each standard purpose is resolved")
      val resolutions = Vector(
        "command-execution" -> AiExecutionClass.SimpleWork,
        "software-implementation" -> AiExecutionClass.StandardWork,
        "software-analysis" -> AiExecutionClass.StandardConsideration,
        "software-design" -> AiExecutionClass.DeepConsideration
      ).map { case (purpose, executionclass) =>
        profiles.resolveRequired(AiRunnerRequirement(purpose = Some(purpose))) -> executionclass
      }

      Then("the profile supplies the approved Codex model and class reasoning")
      resolutions.foreach { case (resolution, executionclass) =>
        resolution.toOption.flatMap(_.runtimeProfile) shouldBe Some("codex-cli")
        resolution.toOption.flatMap(_.requirement.executionClass) shouldBe Some(executionclass)
        resolution.toOption.flatMap(_.requirement.provider) shouldBe Some("codex")
        resolution.toOption.flatMap(_.requirement.model) shouldBe Some("gpt-5.6-sol")
      }
      resolutions.head._1.toOption.flatMap(_.reasoningLevel) shouldBe Some("minimal")
      resolutions.last._1.toOption.flatMap(_.reasoningLevel) shouldBe Some("xhigh")
    }

    "resolve profile-name purposes and merged execution-class overrides through Gemini" in {
      Given("a Gemini profile with an operator class override")
      val profiles = _profiles(
        "textus.ai.profile" -> "gemini",
        "textus.ai.execution-classes.standard-work.model" -> "gemini-project-work",
        "textus.ai.execution-classes.standard-work.max-output-tokens" -> "240"
      )

      When("a caller uses the standard-work implicit purpose")
      val resolution = profiles.resolveRequired(AiRunnerRequirement(purpose = Some("standard-work")))

      Then("the selected profile remains Gemini while the merged class policy applies")
      resolution.toOption.flatMap(_.runtimeProfile) shouldBe Some("gemini")
      resolution.toOption.flatMap(_.requirement.provider) shouldBe Some("google")
      resolution.toOption.flatMap(_.requirement.model) shouldBe Some("gemini-project-work")
      resolution.toOption.flatMap(_.requirement.executionClass) shouldBe Some(AiExecutionClass.StandardWork)
      resolution.toOption.flatMap(_.policy.maxOutputTokens) shouldBe Some(240)
    }

    "map an application purpose to its standard purpose without granting caller selection" in {
      Given("a mapped application purpose with a narrower output bound")
      val profiles = _profiles(Vector(_registration(
        "sanpomap-scenario-generation",
        "structured-extraction",
        AiRunnerApplicationPurposePolicy(maxOutputTokens = Some(120), maxConcurrent = Some(1))
      )),
        "textus.ai.profile" -> "gemini",
        "textus.ai.application-purposes.sanpomap-scenario-generation.max-output-tokens" -> "100"
      )

      When("the application purpose is resolved and a caller attempts a provider selection")
      val mapped = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("sanpomap-scenario-generation")
      ))
      val selected = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("sanpomap-scenario-generation"),
        provider = Some("openai")
      ))

      Then("only the mapped standard purpose reaches the runtime profile")
      mapped.toOption.flatMap(_.requirement.executionClass) shouldBe Some(AiExecutionClass.StandardWork)
      mapped.toOption.flatMap(_.policy.maxOutputTokens) shouldBe Some(100)
      profiles.concurrencyAdmissionC.toOption.flatten should not be empty
      selected.isFaillure shouldBe true
      selected.toString should include ("callers may select only an application purpose")
    }

    "reject legacy configuration aliases before resolution and publish safe facts" in {
      Given("a selected profile together with replaced canonical and alias keys")
      val legacy = _profiles(
        "textus.ai.profile" -> "gemini",
        "textus.ai.model-profiles.old.provider" -> "google"
      )
      val replacedpurpose = _profiles(
        "textus.ai.profile" -> "gemini",
        "textus.ai.purposes.structured-extraction.provider" -> "openai"
      )
      val legacylevel = _profiles(
        "textus.ai.profile" -> "gemini",
        "cncf.runtime.ai.levels.standard-work.model-profile" -> "old"
      )
      val invalidapplication = _profiles(Vector(_registration(
        "sanpomap-scenario-generation",
        "structured-extraction"
      )),
        "textus.ai.profile" -> "gemini",
        "textus.ai.application-purposes.sanpomap-scenario-generation.provider" -> "openai"
      )
      val current = _profiles("textus.ai.profile" -> "gemini")

      When("the runtime resolves a request")
      val rejected = legacy.resolveRequired(AiRunnerRequirement(purpose = Some("structured-extraction")))
      val replaced = replacedpurpose.resolveRequired(AiRunnerRequirement(purpose = Some("structured-extraction")))
      val level = legacylevel.resolveRequired(AiRunnerRequirement(purpose = Some("structured-extraction")))
      val application = invalidapplication.resolveRequired(AiRunnerRequirement(purpose = Some("sanpomap-scenario-generation")))
      val metadata = current.resolveRequired(AiRunnerRequirement(purpose = Some("structured-extraction")))
        .toOption.get.executionMetadata(None, Vector.empty)

      Then("legacy configuration fails structurally and new facts disclose no model-profile identity")
      rejected.isFaillure shouldBe true
      rejected.toString should include ("Legacy AI configuration is not supported")
      replaced.isFaillure shouldBe true
      replaced.toString should include ("Standard AI purposes are runtime-owned")
      level.isFaillure shouldBe true
      level.toString should include ("Legacy AI configuration is not supported")
      application.isFaillure shouldBe true
      application.toString should include ("AI application purpose may not configure this key")
      metadata(AiExecutionFacts.POLICY_RUNTIME_PROFILE) shouldBe "gemini"
      metadata(AiExecutionFacts.POLICY_EFFECTIVE_EXECUTION_CLASS) shouldBe "standard-work"
      metadata should not contain "ai.policy.model_profile"
      metadata should not contain "ai.policy.logical_level"
    }

    "resolve registered defaults without application-purpose configuration" in {
      Given("a registered application purpose with a default policy")
      val profiles = _profiles(Vector(_registration(
        "sanpomap-location-investigation",
        "web-analysis",
        AiRunnerApplicationPurposePolicy(maxOutputTokens = Some(120))
      )), "textus.ai.profile" -> "gemini")

      When("the application calls its registered purpose")
      val resolution = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("sanpomap-location-investigation"),
        purposeRequired = true
      ))

      Then("the registration supplies the standard purpose and policy defaults")
      resolution.toOption.flatMap(_.effectiveStandardPurpose) shouldBe Some("web-analysis")
      resolution.toOption.flatMap(_.policy.maxOutputTokens) shouldBe Some(120)
    }

    "reject duplicate invalid and configuration-only application purposes structurally" in {
      Given("duplicate, invalid, and unregistered application-purpose definitions")
      val duplicate = _profiles(Vector(
        _registration("sanpomap-scenario-generation", "structured-extraction"),
        _registration("sanpomap-scenario-generation", "web-analysis")
      ), "textus.ai.profile" -> "gemini")
      val invalid = _profiles(Vector(_registration(
        "sanpomap-scenario-generation",
        "not-a-standard-purpose"
      )), "textus.ai.profile" -> "gemini")
      val configurationonly = _profiles(
        "textus.ai.profile" -> "gemini",
        "textus.ai.application-purposes.sanpomap-scenario-generation.max-output-tokens" -> "120"
      )

      When("the runtime resolves application requests")
      val duplicated = duplicate.resolveRequired(AiRunnerRequirement(
        purpose = Some("sanpomap-scenario-generation"), purposeRequired = true
      ))
      val unsupported = invalid.resolveRequired(AiRunnerRequirement(
        purpose = Some("sanpomap-scenario-generation"), purposeRequired = true
      ))
      val configured = configurationonly.resolveRequired(AiRunnerRequirement(
        purpose = Some("sanpomap-scenario-generation"), purposeRequired = true
      ))

      Then("every malformed catalog condition fails before provider selection")
      duplicated.toString should include ("Duplicate AI application-purpose registration")
      unsupported.toString should include ("unknown standard purpose")
      configured.toString should include ("configuration does not register a purpose")
    }
  }

  private def _profiles(values: (String, String)*): AiProfileConfig =
    _profiles(Vector.empty, values*)

  private def _profiles(
    registrations: Vector[AiRunnerApplicationPurposeRegistration],
    values: (String, String)*
  ): AiProfileConfig =
    AiProfileConfig.fromConfiguration(Some(ResolvedConfiguration(
      Configuration(values.map { case (key, value) => key -> ConfigurationValue.StringValue(value) }.toMap),
      ConfigurationTrace.empty
    )), AiApplicationPurposeCatalog.fromRegistrations(registrations))

  private def _registration(
    name: String,
    standardpurpose: String,
    policy: AiRunnerApplicationPurposePolicy = AiRunnerApplicationPurposePolicy()
  ): AiRunnerApplicationPurposeRegistration =
    AiRunnerApplicationPurposeRegistration(Vector(AiRunnerApplicationPurpose(
      name,
      standardpurpose,
      policy
    )))
}
