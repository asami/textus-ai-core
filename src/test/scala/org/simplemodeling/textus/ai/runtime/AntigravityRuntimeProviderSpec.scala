package org.simplemodeling.textus.ai.runtime

import java.nio.charset.StandardCharsets
import cats.~>
import org.goldenport.Consequence
import org.goldenport.cncf.component.{ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.{ExecutionContext, GlobalContext, RuntimeContext, ScopeContext, ScopeKind}
import org.goldenport.cncf.processexecution.*
import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.cncf.spi.ai.runner.{AiRunnerRequirement, AiTool}
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.cncf.workarea.WorkAreaSpace
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.goldenport.protocol.Property
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.textus.ai.ComponentFactory
import org.simplemodeling.textus.airuntime.ai.GenerateRequest
import org.simplemodeling.textus.ai.provider.antigravity.{AntigravityConfig, AntigravityExecutionBinding, AntigravityExecutionProfile, AntigravityGenerateService, AntigravityRuntimeConfig}

/*
 * Executable specification for the managed Antigravity CLI provider. It
 * proves adapter behavior without a Google account or network connection.
 *
 * @since   Jul. 22, 2026
 * @version Aug. 21, 2026
 * @author  ASAMI, Tomoharu
 */
final class AntigravityRuntimeProviderSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Antigravity CLI runtime provider" should {
    "configuration and component assembly" which {
      "require explicit enablement and resolve the local Antigravity profile" in {
        Given("disabled, enabled, missing, malformed, and unsafe executable and home configurations")
        val disabled = _configuration(Map(
          "textus.ai.antigravity-cli.executable" -> "/opt/homebrew/bin/agy"
        ))
        val enabled = _configuration(Map(
          "textus.ai.profile" -> "antigravity-cli",
          "textus.ai.antigravity-cli.enabled" -> "true",
          "textus.ai.antigravity-cli.executable" -> "/opt/homebrew/bin/agy",
          "textus.ai.antigravity-cli.home" -> "/tmp/textus-ai-antigravity-home"
        ))
        val unsafe = _configuration(Map(
          "textus.ai.antigravity-cli.enabled" -> "true",
          "textus.ai.antigravity-cli.executable" -> "agy",
          "textus.ai.antigravity-cli.home" -> "/tmp/textus-ai-antigravity-home"
        ))
        val missingexecutable = _configuration(Map(
          "textus.ai.antigravity-cli.enabled" -> "true",
          "textus.ai.antigravity-cli.home" -> "/tmp/textus-ai-antigravity-home"
        ))
        val missinghome = _configuration(Map(
          "textus.ai.antigravity-cli.enabled" -> "true",
          "textus.ai.antigravity-cli.executable" -> "/opt/homebrew/bin/agy"
        ))
        val relativehome = _configuration(Map(
          "textus.ai.antigravity-cli.enabled" -> "true",
          "textus.ai.antigravity-cli.executable" -> "/opt/homebrew/bin/agy",
          "textus.ai.antigravity-cli.home" -> "relative-home"
        ))
        val malformed = _configuration(Map(
          "textus.ai.antigravity-cli.enabled" -> "sometimes",
          "textus.ai.antigravity-cli.executable" -> "/opt/homebrew/bin/agy"
        ))
        val profiles = AiProfileConfig.fromConfiguration(Some(enabled))

        When("configuration and the standard-work purpose are resolved")
        val runtimeconfig = AntigravityConfig.fromConfigurationC(
          enabled,
          _execution_profiles(profiles)
        ).toOption.flatten.get
        val resolution = profiles.resolveRequired(AiRunnerRequirement(purpose = Some("standard-work")))
        val processbinding = AntigravityExecutionBinding.definitionsAndGrantsC(runtimeconfig).toOption.get

        Then("only an absolute explicitly enabled executable installs the local provider")
        AntigravityConfig.fromConfigurationC(disabled, Map.empty).toOption.flatten shouldBe None
        AntigravityConfig.fromConfigurationC(missingexecutable, Map.empty).isFaillure shouldBe true
        AntigravityConfig.fromConfigurationC(missinghome, Map.empty).isFaillure shouldBe true
        AntigravityConfig.fromConfigurationC(relativehome, Map.empty).isFaillure shouldBe true
        AntigravityConfig.fromConfigurationC(unsafe, Map.empty).isFaillure shouldBe true
        AntigravityConfig.fromConfigurationC(malformed, Map.empty).isFaillure shouldBe true
        runtimeconfig.executable shouldBe "/opt/homebrew/bin/agy"
        runtimeconfig.home shouldBe "/tmp/textus-ai-antigravity-home"
        runtimeconfig.executionProfiles.get("runtime-standard-work").map(_.model) shouldBe Some("auto")
        resolution.toOption.flatMap(_.runtimeProfile) shouldBe Some("antigravity-cli")
        resolution.toOption.flatMap(_.requirement.provider) shouldBe Some("google")
        resolution.toOption.flatMap(_.requirement.mode) shouldBe Some("local")
        resolution.toOption.flatMap(_.requirement.engine) shouldBe Some("antigravity-cli")
        resolution.toOption.flatMap(_.requirement.model) shouldBe Some("auto")
        resolution.toOption.map(_.isManagedCliProfile) shouldBe Some(true)
        val definition = processbinding._1.find(_.safeProgramIdentity == "antigravity-cli-profile-runtime-standard-work").get
        definition.fixedArguments shouldBe Vector(
          "--output-format", "json", "--mode", "plan", "--sandbox", "--print"
        )
        definition.argumentPolicy.validateC(Vector.empty, definition.maximumLimits).isFaillure shouldBe true
        definition.argumentPolicy.validateC(Vector("prompt"), definition.maximumLimits).isSuccess shouldBe true
        definition.argumentPolicy.validateC(Vector("--model", "caller-model"), definition.maximumLimits).isFaillure shouldBe true
        definition.argumentPolicy.fixedPrefix shouldBe definition.fixedArguments
      }

      "reject invalid explicit configuration during component assembly" in {
        Given("an enabled Antigravity provider with a relative executable path")
        val configuration = _configuration(Map(
          "textus.ai.antigravity-cli.enabled" -> "true",
          "textus.ai.antigravity-cli.executable" -> "agy",
          "textus.ai.antigravity-cli.home" -> "/tmp/textus-ai-antigravity-home"
        ))

        When("the CNCF component factory assembles the provider runtime")
        val result = new ComponentFactory().createC(ComponentCreate(
          new Subsystem(name = "textus-ai-antigravity-invalid-config-spec", configuration = configuration),
          ComponentOrigin.Main
        ))

        Then("component assembly fails deterministically instead of installing an unavailable provider")
        result.isFaillure shouldBe true
        result.toString should include ("absolute executable path")
      }

      "install the fixed capability in the assembled component scope" in {
        Given("an explicitly enabled Antigravity CLI runtime")
        given ExecutionContext = ExecutionContext.create()
        val configuration = _configuration(Map(
          "textus.ai.profile" -> "antigravity-cli",
          "textus.ai.antigravity-cli.enabled" -> "true",
          "textus.ai.antigravity-cli.executable" -> "/opt/homebrew/bin/agy",
          "textus.ai.antigravity-cli.home" -> "/tmp/textus-ai-antigravity-home"
        ))
        val component = new ComponentFactory().create(ComponentCreate(
          new Subsystem(name = "textus-ai-antigravity-scope-spec", configuration = configuration),
          ComponentOrigin.Main
        )).primary
        component.withScopeContext(ScopeContext(
          ScopeKind.Runtime,
          "textus-ai-antigravity-scope-spec",
          None,
          summon[ExecutionContext].observability
        ))
        val capability = ProcessCapabilityId.parseC("antigravity-cli-profile-runtime-standard-work").toOption.get

        When("the component resolves the process capability")
        val admitted = ProcessExecutionAdmission.resolveC(
          component.scopeContext,
          ProcessExecutionRequest(capability, Vector("prompt"))
        )
        val unsafe = ProcessExecutionAdmission.resolveC(
          component.scopeContext,
          ProcessExecutionRequest(capability, Vector("--model", "caller-model"))
        )

        Then("only the runtime-fixed sandboxed plan-mode invocation is admitted")
        withClue(admitted.toString) {
          admitted.toOption.map(_.effectiveArguments) shouldBe Some(Vector(
            "--output-format", "json", "--mode", "plan", "--sandbox", "--print", "prompt"
          ))
        }
        unsafe.isFaillure shouldBe true
        component.scopeContext.processExecutionDriverOption.exists(_.isInstanceOf[LocalProcessExecutionDriver]) shouldBe true
      }
    }

    "managed process execution" which {
      "execute admitted Web-capable requests through one bounded prompt argument and parse headless JSON" in {
        Given("a deterministic Antigravity CLI result and an admitted Web profile")
        val profilename = "runtime-standard-work"
        val profile = AntigravityExecutionProfile(
          profilename,
          "auto",
          Set(AiTool.WebSearch, AiTool.UrlContext)
        )
        val capabilityname = profile.webCapability
        val fixture = _fixture(capabilityname, _result(
          """{"conversation_id":"conversation-1","status":"SUCCESS","response":"Nakano has a notable temple.","duration_seconds":3.2,"num_turns":1,"usage":{"input_tokens":21,"output_tokens":9,"cached_input_tokens":3,"thinking_tokens":4,"total_tokens":34,"tool_calls":2}}"""
        ))
        given ExecutionContext = _context(fixture)
        val service = new AntigravityGenerateService(
          AntigravityRuntimeConfig(
            "/opt/homebrew/bin/agy",
            "/tmp/textus-ai-antigravity-home",
            Map(profilename -> profile)
          ),
          summon[ExecutionContext]
        )

        When("the resolved runtime marker requests Gemini Web tools")
        val result = service.generate(GenerateRequest(
          prompt = "Find notable places near Nakano Station.",
          properties = Vector(
            Property(AiRequestProperties.ANTIGRAVITY_EXECUTION_PROFILE, profilename, None),
            Property("ai.tools", "web_search,url_context", None)
          )
        ))

        Then("only one bounded prompt argument crosses the managed process boundary and safe JSON metadata is surfaced")
        val response = result.toOption.get
        val normalized = AiExecutionFacts.normalize(
          SpiSelection(provider = Some("google"), mode = Some("local"), engine = Some("antigravity-cli")),
          AiRunnerRequirement(),
          response.model,
          response.metadata
        )
        response.text shouldBe "Nakano has a notable temple."
        response.model shouldBe None
        response.metadata.get("google.antigravity_conversation_id") shouldBe Some("conversation-1")
        response.metadata.get("google.antigravity_tool_calls") shouldBe Some("2")
        response.metadata.get("google.usage.input_tokens") shouldBe Some("21")
        response.metadata.get("google.usage.output_tokens") shouldBe Some("9")
        response.metadata.get("google.usage.reasoning_tokens") shouldBe Some("4")
        normalized.get(AiExecutionFacts.INPUT_TOKENS) shouldBe Some("21")
        normalized.get(AiExecutionFacts.OUTPUT_TOKENS) shouldBe Some("9")
        normalized.get(AiExecutionFacts.TOOL_RESULT_SUMMARY) shouldBe Some("antigravity_tool_calls=2")
        fixture.profile.driver.executions.map(_.request.arguments) shouldBe Vector(Vector(
          "Find notable places near Nakano Station."
        ))
        val request = fixture.profile.driver.executions.head.request
        request.input shouldBe ProcessExecutionInput.Empty
      }

      "reject model overrides before process execution" in {
        Given("an admitted local profile")
        val profilename = "runtime-standard-work"
        val profile = AntigravityExecutionProfile(profilename, "auto")
        val fixture = _fixture(profile.plainCapability, _result("""{"response":"unused"}"""))
        given ExecutionContext = _context(fixture)
        val service = new AntigravityGenerateService(
          AntigravityRuntimeConfig(
            "/opt/homebrew/bin/agy",
            "/tmp/textus-ai-antigravity-home",
            Map(profilename -> profile)
          ),
          summon[ExecutionContext]
        )

        When("a caller attempts to override the runtime-owned model")
        val result = service.generate(GenerateRequest(
          prompt = "unsafe",
          properties = Vector(
            Property(AiRequestProperties.ANTIGRAVITY_EXECUTION_PROFILE, profilename, None),
            Property("ai.google.model", "caller-model", None)
          )
        ))

        Then("the request fails before invoking Antigravity CLI")
        result.isFaillure shouldBe true
        result.toString should include ("model override")
        fixture.profile.driver.executions shouldBe Vector.empty
      }
    }
  }

  private def _execution_profiles(
    profiles: AiProfileConfig
  ): Map[String, AntigravityExecutionProfile] =
    profiles.antigravityExecutionsC.toOption.getOrElse(Map.empty).map { case (name, execution) =>
      name -> AntigravityExecutionProfile(name, execution.model, execution.tools.toSet)
    }

  private def _fixture(
    capabilityname: String,
    result: ProcessExecutionResult
  ): ProcessExecutionTestFixture = {
    val capability = ProcessCapabilityId.parseC(capabilityname).toOption.get
    val profile = ProcessExecutionTestProfile(Map(capability -> result))
    val definition = ProcessProgramDefinition.fromRuntimeC(
      capability,
      "deterministic-antigravity-cli",
      "test-runtime-owned-location",
      Vector.empty,
      ProcessArgumentPolicy(
        Vector.empty,
        admission = ProcessArgumentAdmission.BoundedText
      ),
      AntigravityRuntimeConfig.defaultExecutionLimits,
      Set.empty,
      allowedinputfiles = Set.empty
    ).toOption.get
    val policy = ProcessExecutionPolicy.createC(Vector(definition)).toOption.get
    val admission = ProcessExecutionAdmission.createC(policy, Vector(ProcessExecutionGrant(capability))).toOption.get
    val executionresult = admission.admitC(ProcessExecutionRequest(capability, Vector("fixture prompt")))
    withClue(executionresult.toString) {
      executionresult.isSuccess shouldBe true
    }
    val execution = executionresult.toOption.get
    ProcessExecutionTestFixture(profile, policy, admission, execution)
  }

  private def _result(stdout: String): ProcessExecutionResult = {
    val stdoutbytes = stdout.getBytes(StandardCharsets.UTF_8).toVector
    val empty = ProcessExecutionCapture(Vector.empty, 0L, truncated = false)
    ProcessExecutionResult(
      ProcessExecutionTermination.Exited(0),
      ProcessExecutionCapture(stdoutbytes, stdoutbytes.length.toLong, truncated = false),
      empty,
      Vector.empty,
      elapsedMillis = 1L,
      safeProgramIdentity = "antigravity-cli"
    )
  }

  private def _context(fixture: ProcessExecutionTestFixture): ExecutionContext = {
    GlobalContext.set(GlobalContext(WorkAreaSpace.create(RuntimeConfig.default)))
    val base = ExecutionContext.create()
    var runtime: RuntimeContext = null
    lazy val context: ExecutionContext = ExecutionContext.withRuntimeContext(base, runtime)
    lazy val uow = new UnitOfWork(context)
    runtime = new RuntimeContext(
      core = RuntimeContext.core(
        name = "antigravity-runtime-provider-spec",
        parent = None,
        observabilitycontext = base.observability
      ).copy(
        processExecutionDriverOption = Some(fixture.profile.driver),
        processExecutionAdmissionOption = Some(fixture.admission)
      ),
      unitofworksupplier = () => uow,
      unitofworkinterpreterfn = new (UnitOfWorkOp ~> Consequence) {
        def apply[A](operation: UnitOfWorkOp[A]): Consequence[A] =
          new UnitOfWorkInterpreter(uow).interpret(operation)
      },
      commitaction = _ => (),
      abortaction = _ => (),
      disposeaction = _ => (),
      token = "antigravity-runtime-provider-spec"
    )
    context
  }

  private def _configuration(values: Map[String, String]): ResolvedConfiguration =
    ResolvedConfiguration(
      Configuration(values.map { case (key, value) => key -> ConfigurationValue.StringValue(value) }),
      ConfigurationTrace.empty
    )
}
