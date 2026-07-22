package org.simplemodeling.textus.ai.runtime

import java.net.{ConnectException, SocketException}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.goldenport.Consequence
import org.goldenport.cncf.config.RuntimeFileConfigLoader
import org.goldenport.cncf.spi.ai.runner.{AiRunnerApplicationPurpose, AiRunnerApplicationPurposeRegistration, AiRunnerRequirement}
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.scalatest.GivenWhenThen
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * @since   Jul. 21, 2026
 * @version Jul. 22, 2026
 */
final class AiOperationalStrategySpec
  extends AnyWordSpec
  with Matchers
  with OptionValues
  with GivenWhenThen {

  "Gemma-first operational profiles" should {
    "keep Gemma-first strategies within work classes and select thinking directly commercially" in {
      Given("a Gemma-first profile and registered work and thinking purposes")
      val mappings = Vector(
        "fixture-command" -> "command-execution",
        "fixture-structured" -> "software-implementation",
        "fixture-validator-repair" -> "structured-extraction",
        "fixture-design" -> "software-design",
        "fixture-analysis" -> "software-analysis",
        "fixture-web" -> "web-analysis"
      )
      val config = _config(Map(
        "textus.ai.profile" -> "gemma-first-gemini"
      ))
      val profiles = _profiles(config, mappings*)

      When("each application purpose is resolved")
      val resolutions = mappings.map { case (purpose, _) =>
        purpose -> profiles.resolveRequired(AiRunnerRequirement(
          purpose = Some(purpose),
          purposeRequired = true
        )).toOption.value
      }.toMap

      Then("work purposes keep Gemma primary while thinking classes select Gemini directly")
      Vector("fixture-command", "fixture-structured", "fixture-validator-repair").flatMap(
        resolutions(_).operationalStrategy.map(_.id)
      ).toSet shouldBe Set(
        "structured",
        "validator-repair"
      )
      Vector("fixture-command", "fixture-structured", "fixture-validator-repair").foreach { purpose =>
        val resolution = resolutions(purpose)
        val strategy = resolution.operationalStrategy.value
        strategy.primary.provider shouldBe "gemma"
        strategy.commercialFallback.value.provider shouldBe "google"
        strategy.maxRepairs shouldBe 1
        strategy.maxProviderAttempts shouldBe 2
      }
      Vector("fixture-design", "fixture-analysis", "fixture-web").foreach { purpose =>
        val resolution = resolutions(purpose)
        resolution.requirement.provider shouldBe Some("google")
        resolution.operationalStrategy shouldBe None
      }
    }

    "allow operator policy to refine strategy defaults without caller provider selection" in {
      Given("a work-class application strategy override and bounded fallback overrides")
      val config = _config(Map(
        "textus.ai.profile" -> "gemma-first-gemini",
        "textus.ai.application-purposes.sanpomap-scenario-generation.operational-strategy" -> "candidate-ranking",
        "textus.ai.application-purposes.sanpomap-scenario-generation.acceptance-operation" ->
          "Sanpomap.Evaluation.evaluateAiCandidate",
        "textus.ai.execution-classes.standard-work.strategy-max-repairs" -> "2",
        "textus.ai.execution-classes.standard-work.strategy-max-provider-attempts" -> "2",
        "textus.ai.execution-classes.standard-work.fallback-model" -> "gemini-fixture"
      ))
      val profiles = _profiles(
        config,
        "sanpomap-scenario-generation" -> "software-implementation"
      )

      When("the application asks only for its registered purpose")
      val resolution = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("sanpomap-scenario-generation"),
        purposeRequired = true
      )).toOption.value
      val strategy = resolution.operationalStrategy.value

      Then("the operator-owned plan is resolved without changing the public request")
      strategy.id shouldBe "candidate-ranking"
      strategy.acceptanceOperation shouldBe Some("Sanpomap.Evaluation.evaluateAiCandidate")
      strategy.maxRepairs shouldBe 2
      strategy.maxProviderAttempts shouldBe 2
      strategy.commercialFallback.value.model shouldBe "gemini-fixture"
      resolution.requirement.provider shouldBe Some("gemma")
      val metadata = resolution.executionMetadata(None, Vector.empty)
      metadata(AiExecutionFacts.OPERATIONAL_STRATEGY) shouldBe "candidate-ranking"
      metadata(AiExecutionFacts.STRATEGY_MAX_REPAIRS) shouldBe "2"
      metadata(AiExecutionFacts.STRATEGY_MAX_PROVIDER_ATTEMPTS) shouldBe "2"
    }

    "resolve the assembled Sanpomap scenario policy with a smaller Gemma execution" in {
      Given("the guarded Sanpomap scenario policy used for live evidence")
      val config = _config(Map(
        "textus.ai.profile" -> "gemma-first-codex-cli",
        "textus.ai.execution-classes.standard-work.model" -> "gemma3:1b",
        "textus.ai.application-purposes.sanpomap-scenario-generation.operational-strategy" -> "structured",
        "textus.ai.application-purposes.sanpomap-scenario-generation.acceptance-operation" ->
          "Sanpomap.Evaluation.evaluateAiCandidate",
        "textus.ai.application-purposes.sanpomap-scenario-generation.timeout-seconds" -> "900",
        "textus.ai.application-purposes.sanpomap-scenario-generation.max-output-tokens" -> "120"
      ))
      val profiles = _profiles(
        config,
        "sanpomap-scenario-generation" -> "software-implementation"
      )

      When("the registered application purpose is resolved")
      val resolution = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("sanpomap-scenario-generation"),
        purposeRequired = true
      )).toOption.value

      Then("Gemma remains primary and Codex remains a bounded fallback")
      resolution.requirement.model shouldBe Some("gemma3:1b")
      resolution.policy.timeoutSeconds shouldBe Some(900L)
      resolution.policy.maxOutputTokens shouldBe Some(120)
      val strategy = resolution.operationalStrategy.value
      strategy.id shouldBe "structured"
      strategy.acceptanceOperation shouldBe Some("Sanpomap.Evaluation.evaluateAiCandidate")
      strategy.commercialFallback.value.provider shouldBe "codex"
    }

    "accept nested YAML application-purpose policy from the common config reader" in {
      Given("the same policy encoded as normal nested YAML")
      val path = Files.createTempFile("textus-ai-gemma-first", ".yaml")
      Files.writeString(
        path,
        """textus:
          |  ai:
          |    profile: gemma-first-codex-cli
          |    execution-classes:
          |      standard-work:
          |        model: gemma3:1b
          |    application-purposes:
          |      sanpomap-scenario-generation:
          |        operational-strategy: structured
          |        acceptance-operation: Sanpomap.Evaluation.evaluateAiCandidate
          |        timeout-seconds: 900
          |        max-output-tokens: 120
          |""".stripMargin,
        StandardCharsets.UTF_8
      )
      val loaded = new RuntimeFileConfigLoader().load(path).toOption.value
      val config = ResolvedConfiguration(loaded, ConfigurationTrace.empty)
      val profiles = _profiles(
        config,
        "sanpomap-scenario-generation" -> "software-implementation"
      )

      When("the flattened parent and leaf keys are validated")
      val resolution = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("sanpomap-scenario-generation"),
        purposeRequired = true
      )).toOption.value

      Then("parent object keys are not mistaken for forbidden policy leaves")
      resolution.requirement.model shouldBe Some("gemma3:1b")
      resolution.policy.timeoutSeconds shouldBe Some(900L)
      resolution.operationalStrategy.value.acceptanceOperation shouldBe
        Some("Sanpomap.Evaluation.evaluateAiCandidate")
    }

    "retain no-fallback behavior for conventional profiles" in {
      Given("the existing Gemma-only profile")
      val profiles = _profiles(_config(Map("textus.ai.profile" -> "gemma")))

      When("a standard purpose is resolved")
      val resolution = profiles.resolveRequired(AiRunnerRequirement(
        purpose = Some("structured-extraction"),
        purposeRequired = true
      )).toOption.value

      Then("no operational fallback strategy is introduced implicitly")
      resolution.operationalStrategy shouldBe None
    }

    "register managed CLI capabilities selected only as profile fallbacks" in {
      Given("the Gemma-first Codex CLI profile")
      val profiles = AiProfileConfig.fromConfiguration(Some(_config(Map(
        "textus.ai.profile" -> "gemma-first-codex-cli"
      ))))

      When("the runtime builds its Codex process capability catalog")
      val executions = profiles.codexExecutionsC.toOption.value

      Then("every fallback execution class is admitted under its runtime profile name")
      executions.keySet should contain allOf (
        "runtime-simple-work",
        "runtime-standard-work",
        "runtime-simple-thinking",
        "runtime-advanced-thinking",
        "runtime-deep-thinking"
      )
      executions("runtime-standard-work").provider shouldBe "codex"
    }

    "account for commercial fallback with its own operator rate schedule" in {
      Given("a cost-bounded Gemma-first profile with separate primary and fallback rates")
      val rates = Map(
        "textus.ai.profile" -> "gemma-first-gemini",
        "textus.ai.execution-classes.standard-work.max-cost-microunits" -> "1000000",
        "textus.ai.execution-classes.standard-work.rate-schedule" -> "gemma-standard",
        "textus.ai.execution-classes.standard-work.fallback-rate-schedule" -> "gemini-standard",
        "textus.ai.rate-schedules.gemma-standard.input-microunits-per-million-tokens" -> "0",
        "textus.ai.rate-schedules.gemma-standard.cached-input-microunits-per-million-tokens" -> "0",
        "textus.ai.rate-schedules.gemma-standard.output-microunits-per-million-tokens" -> "0",
        "textus.ai.rate-schedules.gemma-standard.reasoning-microunits-per-million-tokens" -> "0",
        "textus.ai.rate-schedules.gemini-standard.input-microunits-per-million-tokens" -> "100",
        "textus.ai.rate-schedules.gemini-standard.cached-input-microunits-per-million-tokens" -> "20",
        "textus.ai.rate-schedules.gemini-standard.output-microunits-per-million-tokens" -> "500",
        "textus.ai.rate-schedules.gemini-standard.reasoning-microunits-per-million-tokens" -> "500"
      )

      When("the cost-bounded strategy is resolved")
      val resolution = _profiles(
        _config(rates),
        "sanpomap-scenario-generation" -> "software-implementation"
      ).resolveRequired(AiRunnerRequirement(
        purpose = Some("sanpomap-scenario-generation"),
        purposeRequired = true
      )).toOption.value

      Then("primary and commercial executions retain distinct accounting schedules")
      resolution.rateSchedule.map(_.id) shouldBe Some("gemma-standard")
      resolution.operationalStrategy.value.commercialFallbackRateSchedule.map(_.id) shouldBe
        Some("gemini-standard")

      And("a cost-accounted fallback without its own schedule is rejected")
      val invalid = _profiles(
        _config(rates - "textus.ai.execution-classes.standard-work.fallback-rate-schedule"),
        "sanpomap-scenario-generation" -> "software-implementation"
      ).resolveRequired(AiRunnerRequirement(
        purpose = Some("sanpomap-scenario-generation"),
        purposeRequired = true
      ))
      invalid should matchPattern { case Consequence.Failure(_) => }
    }

    "admit only classified residual failures for commercial escalation" in {
      Given("the explicit fallback admission vocabulary")
      val admitted = Vector(
        "availability",
        "timeout",
        "malformed-output",
        "domain-validation",
        "insufficient-evidence",
        "ambiguity"
      )
      val denied = Vector(
        "authorization",
        "capability",
        "admission",
        "credential-policy",
        "input",
        "resource-limit",
        "unknown"
      )

      Then("only the six reviewed residual classes permit escalation")
      admitted.foreach { value =>
        AiAttemptFailureClass.fromEscalationReason(value).permitsCommercialEscalation shouldBe true
      }
      denied.foreach { value =>
        AiAttemptFailureClass.fromEscalationReason(value).permitsCommercialEscalation shouldBe false
      }
    }

    "classify wrapped provider failures before applying HTTP status fallback" in {
      def failure(message: String) = Consequence.operationIllegal("fixture", message) match {
        case Consequence.Failure(conclusion) => conclusion
        case _ => fail("fixture consequence must fail")
      }

      AiAttemptFailureClass.fromConclusion(failure("provider timed out")) shouldBe
        AiAttemptFailureClass.Timeout
      AiAttemptFailureClass.fromConclusion(failure("connection refused by local runtime")) shouldBe
        AiAttemptFailureClass.Availability
      AiAttemptFailureClass.fromConclusion(failure("resource-limit denied")) shouldBe
        AiAttemptFailureClass.ResourceLimit
      AiAttemptFailureClass.fromConclusion(failure("credential-policy denied")) shouldBe
        AiAttemptFailureClass.CredentialPolicy
      AiAttemptFailureClass.fromConclusion(failure("Codex CLI returned empty output")) shouldBe
        AiAttemptFailureClass.MalformedOutput
      AiAttemptFailureClass.fromConclusion(failure("Codex execution profile is not admitted: fixture")) shouldBe
        AiAttemptFailureClass.Capability
      AiAttemptFailureClass.fromConclusion(failure("Codex CLI output exceeded the configured limit")) shouldBe
        AiAttemptFailureClass.ResourceLimit
      AiAttemptFailureClass.safeReason(failure("Codex CLI returned empty output")) shouldBe
        "empty-output"
      AiAttemptFailureClass.safeReason(failure("private provider detail")) shouldBe
        "status-only"
      val connection = Consequence(throw new ConnectException) match {
        case Consequence.Failure(conclusion) => conclusion
        case _ => fail("connection fixture consequence must fail")
      }
      AiAttemptFailureClass.fromConclusion(connection) shouldBe AiAttemptFailureClass.Availability
      val socket = Consequence(throw new SocketException) match {
        case Consequence.Failure(conclusion) => conclusion
        case _ => fail("socket fixture consequence must fail")
      }
      AiAttemptFailureClass.fromConclusion(socket) shouldBe AiAttemptFailureClass.Availability
    }
  }

  private def _config(values: Map[String, String]): ResolvedConfiguration =
    ResolvedConfiguration(
      Configuration(values.view.mapValues(ConfigurationValue.StringValue.apply).toMap),
      ConfigurationTrace.empty
    )

  private def _profiles(
    configuration: ResolvedConfiguration,
    mappings: (String, String)*
  ): AiProfileConfig = {
    val registration = AiRunnerApplicationPurposeRegistration(
      mappings.toVector.map { case (application, standard) =>
        AiRunnerApplicationPurpose(application, standard)
      }
    )
    AiProfileConfig.fromConfiguration(
      Some(configuration),
      AiApplicationPurposeCatalog.fromRegistrations(Vector(registration))
    )
  }
}
