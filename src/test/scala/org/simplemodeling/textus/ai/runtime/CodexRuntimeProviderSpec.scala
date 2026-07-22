package org.simplemodeling.textus.ai.runtime

import java.nio.charset.StandardCharsets
import cats.~>
import org.goldenport.Consequence
import org.goldenport.cncf.component.Component
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.{ExecutionContext, GlobalContext, RuntimeContext}
import org.goldenport.cncf.processexecution.*
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.{AiChatRequest, AiGenerateRequest, AiMessage, AiRecordRequest, AiRunner, AiRunnerRequirement, AiTool}
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.cncf.workarea.WorkAreaSpace
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.goldenport.protocol.Property
import org.goldenport.record.Record
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.textus.ai.provider.codex.{CodexCliVersion, CodexConfig, CodexExecutionProfile, CodexReasoningLevel, CodexRuntimeConfig}

/*
 * Executable specification for the managed Codex CLI provider. The test
 * profile proves adapter intent without a Codex binary, account, or network.
 *
 * @since   Jul. 17, 2026
 * @version Jul. 22, 2026
 * @author  ASAMI, Tomoharu
 */
final class CodexRuntimeProviderSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Codex runtime provider" should {
    "require explicit Codex provider enablement from runtime configuration" in {
      Given("configuration with Codex settings but no enabled flag")
      val disabled = _configuration(Map(
        "textus.ai.codex.engine" -> "codex-cli"
      ))
      val enabled = _configuration(Map(
        "textus.ai.profile" -> "codex-cli",
        "textus.ai.codex.enabled" -> "true",
        "textus.ai.codex.executable" -> "/runtime/codex-cli",
        "textus.ai.codex.schema-maximum-bytes" -> "4096"
      ))
      val unsafe = _configuration(Map(
        "textus.ai.codex.enabled" -> "true",
        "textus.ai.codex.executable" -> "codex"
      ))

      When("the Codex adapter configuration is resolved")
      val disabledconfig = CodexConfig.fromConfiguration(disabled)
      val profiles = AiProfileConfig.fromConfiguration(Some(enabled))
      val enabledconfig = CodexConfig.fromConfiguration(enabled, _codex_executions(profiles))
      val unsafeconfig = CodexConfig.fromConfiguration(unsafe)
      val legacy = CodexRuntimeConfig("local", "codex-cli", 4096L)

      Then("only the explicitly enabled runtime installs the provider adapter")
      disabledconfig shouldBe None
      enabledconfig.map(_.schemaMaximumBytes) shouldBe Some(4096L)
      enabledconfig.map(_.executable) shouldBe Some("/runtime/codex-cli")
      enabledconfig.flatMap(_.executionProfiles.get("runtime-simple-thinking")).map(_.reasoningLevel) shouldBe Some(Some(CodexReasoningLevel.Medium))
      unsafeconfig shouldBe None
      legacy.schemaMaximumBytes shouldBe 4096L
      CodexCliVersion.minimumForModel("gpt-5.6-sol") shouldBe Some(CodexCliVersion.Gpt56Minimum)
      CodexCliVersion.minimumForModel("gpt-5.6-terra") shouldBe Some(CodexCliVersion.Gpt56Minimum)
      CodexCliVersion.minimumForModel("gpt-5.6-luna") shouldBe Some(CodexCliVersion.Gpt56Minimum)
    }

    "run generate and record requests through the admitted CNCF process capability" in {
      Given("a deterministic runtime with the codex-cli capability and a JSON result")
      val fixture = _fixture(_result("{\"title\":\"Codex result\"}"))
      given ExecutionContext = _context(fixture)
      val runner = _runner()

      When("plain and record generation select the codex-cli provider alias")
      val generated = runner.generate(
        AiGenerateRequest("plain prompt", requirement = AiRunnerRequirement(provider = Some("codex-cli")))
      )
      val recorded = runner.generateRecord(
        AiRecordRequest(
          "record prompt",
          _schema,
          requirement = AiRunnerRequirement(provider = Some("codex"))
        )
      )
      val chatted = runner.chat(
        AiChatRequest(
          Vector(AiMessage("user", "chat prompt")),
          requirement = AiRunnerRequirement(provider = Some("codex"))
        )
      )
      val generatedresponse = generated match {
        case Consequence.Success(value) => value
        case Consequence.Failure(conclusion) => fail(conclusion.display)
      }

      Then("the adapter emits admitted stdin and schema-file intents without direct process access")
      generatedresponse.text shouldBe "{\"title\":\"Codex result\"}"
      generatedresponse.metadata.get(AiExecutionFacts.PROVIDER) shouldBe Some("codex")
      generatedresponse.metadata.get(AiExecutionFacts.FINISH_REASON) shouldBe Some("exited")
      recorded.toOption.flatMap(_.record.getAny("title")) shouldBe Some("Codex result")
      chatted.toOption.map(_.message.content) shouldBe Some("{\"title\":\"Codex result\"}")
      fixture.profile.driver.executions.map(_.request.arguments) shouldBe Vector(
        Vector("-"),
        Vector("--output-schema", "schema.json", "-"),
        Vector("-")
      )
      val plain = fixture.profile.driver.executions.head.request
      new String(plain.input.asInstanceOf[ProcessExecutionInput.Bytes].value.toArray, StandardCharsets.UTF_8) shouldBe "plain prompt"
      val record = fixture.profile.driver.executions(1).request
      record.inputFiles.map(_.name.print) shouldBe Vector("schema")
      val recordschema = new String(record.inputFiles.head.content.toArray, StandardCharsets.UTF_8)
      recordschema should include ("\"title\"")
      recordschema should include ("\"additionalProperties\":false")
      recordschema should include ("\"confidence\":{\"type\":[\"integer\",\"null\"]}")
      val chat = fixture.profile.driver.executions(2).request
      new String(chat.input.asInstanceOf[ProcessExecutionInput.Bytes].value.toArray, StandardCharsets.UTF_8) should include ("chat prompt")
    }

    "surface unavailable Codex CLI execution without exposing process output" in {
      Given("an admitted Codex capability whose runtime cannot launch the program")
      val fixture = _fixture(_result("", ProcessExecutionTermination.LaunchFailed))
      given ExecutionContext = _context(fixture)
      val runner = _runner()

      When("generation is requested")
      val result = runner.generate(
        AiGenerateRequest("confidential prompt", requirement = AiRunnerRequirement(provider = Some("codex")))
      )

      Then("the caller receives a structured unavailable-service failure")
      result.isFaillure shouldBe true
      result.toString should not include "confidential prompt"
    }

    "reject unsupported tools and unsafe terminal process outcomes explicitly" in {
      Given("controlled Codex terminal outcomes and a request for a provider web tool")
      val outcomes = Vector(
        ProcessExecutionTermination.Exited(9),
        ProcessExecutionTermination.TimedOut,
        ProcessExecutionTermination.Cancelled,
        ProcessExecutionTermination.OutputLimitExceeded(ProcessExecutionStream.Stdout)
      )

      When("each outcome and unsupported request is sent through the provider")
      val results = outcomes.map { outcome =>
        val fixture = _fixture(_result("sensitive stdout", outcome))
        given ExecutionContext = _context(fixture)
        _runner().generate(AiGenerateRequest("confidential prompt"))
      }
      val toolfixture = _fixture(_result("{}"))
      given ExecutionContext = _context(toolfixture)
      val toolresult = _runner().generate(
        AiGenerateRequest(
          "confidential prompt",
          requirement = AiRunnerRequirement(tools = Vector(AiTool.UrlContext))
        )
      )

      Then("no terminal output is exposed and unsupported tools never reach the process driver")
      results.foreach { result =>
        result.isFaillure shouldBe true
        result.toString should not include "sensitive stdout"
        result.toString should not include "confidential prompt"
      }
      toolresult.isFaillure shouldBe true
      toolfixture.profile.driver.executions shouldBe Vector.empty
    }

    "reject generic and Codex-scoped model overrides before invoking the managed Codex CLI" in {
      Given("an admitted Codex capability and generic or provider-scoped model overrides")
      val fixture = _fixture(_result("{}"))
      given ExecutionContext = _context(fixture)
      val genericrequest = AiGenerateRequest(
        "confidential prompt",
        requirement = AiRunnerRequirement(model = Some("unconfigured-model"))
      )
      val scopedrequest = AiGenerateRequest(
        "confidential prompt",
        properties = Vector(Property("ai.codex-cli.model", "unconfigured-cli-model", None))
      )
      val outputlimitedrequest = AiGenerateRequest(
        "confidential prompt",
        maxTokens = Some(64)
      )

      When("generation is requested through the Codex provider")
      val results = Vector(
        _runner().generate(genericrequest),
        _runner().generate(scopedrequest),
        _runner().generate(outputlimitedrequest)
      )

      Then("the unsupported override fails before any process execution")
      results.foreach { result =>
        result.isFaillure shouldBe true
        result.toString should not include "unconfigured"
      }
      fixture.profile.driver.executions shouldBe Vector.empty
    }

    "submit purpose timeout through the admitted managed-process limit" in {
      Given("an admitted Codex capability and a generic purpose timeout")
      val fixture = _fixture(_result("{}"))
      given ExecutionContext = _context(fixture)

      When("generation is routed through the Codex provider")
      val result = _runner().generate(
        AiGenerateRequest(
          "bounded prompt",
          properties = Vector(Property("ai.timeout-seconds", "121", None))
        )
      )

      Then("the capability admission rejects the converted limit before process execution")
      result shouldBe a[Consequence.Failure[_]]
      result match
        case Consequence.Failure(conclusion) =>
          conclusion.display should include ("121000")
        case _ =>
          fail("an over-broad process timeout must be rejected by admission")
      fixture.profile.driver.executions shouldBe Vector.empty
    }

    "run a Web-research purpose through its fixed admitted Codex profile" in {
      val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map(
        "textus.ai.profile" -> "codex-cli",
        "textus.ai.execution-classes.deep-thinking.reasoning-level" -> "high",
        "textus.ai.execution-classes.deep-thinking.tools" -> "url_context,web_search"
      ))))
      val profile = _codex_executions(profiles)("runtime-deep-thinking")
      val fixture = _fixture(
        _result("{\"title\":\"Web result\"}"),
        profile.webCapability,
        versioncapabilityname = Some(profile.versionCapability)
      )
      given ExecutionContext = _context(fixture)

      When("a caller names only the required purpose")
      val result = _runner(
        CodexRuntimeConfig(executionProfiles = Map(profile.name -> profile)),
        profiles
      ).generateRecord(AiRecordRequest(
        "research the supplied official URL",
        _schema,
        requirement = AiRunnerRequirement(
          purpose = Some("web-analysis"),
          purposeRequired = true
        )
      ))

      Then("the selected profile controls the model and Web capability without caller CLI input")
      withClue(result.toString) {
        result.toOption.flatMap(_.record.getAny("title")) shouldBe Some("Web result")
      }
      result.toOption.flatMap(_.metadata.get(AiExecutionFacts.POLICY_RUNTIME_PROFILE)) shouldBe Some("codex-cli")
      result.toOption.flatMap(_.metadata.get(AiExecutionFacts.POLICY_REASONING_LEVEL)) shouldBe Some("high")
      result.toOption.flatMap(_.metadata.get(AiExecutionFacts.ENABLED_TOOLS)) shouldBe Some("url_context,web_search")
      fixture.profile.driver.executions.map(_.request.capability.print) shouldBe Vector(profile.versionCapability, profile.webCapability)
      fixture.profile.driver.executions.map(_.request.arguments) shouldBe Vector(Vector.empty, Vector("--output-schema", "schema.json", "-"))
    }

    "reject a Codex runtime profile whose managed CLI is below its model requirement" in {
      Given("a GPT-5.6 profile and a managed Codex CLI that reports version 0.143.0")
      val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map(
        "textus.ai.profile" -> "codex-cli"
      ))))
      val profile = _codex_executions(profiles)("runtime-standard-work")
      val fixture = _fixture(
        _result("{\"title\":\"unexpected\"}"),
        profile.plainCapability,
        Some(profile.versionCapability),
        _result("codex-cli 0.143.0")
      )
      given ExecutionContext = _context(fixture)

      When("a caller requests the standard-work purpose")
      val result = _runner(
        CodexRuntimeConfig(executionProfiles = Map(profile.name -> profile)),
        profiles
      ).generate(AiGenerateRequest(
        "implement the requested change",
        requirement = AiRunnerRequirement(
          purpose = Some("standard-work"),
          purposeRequired = true
        )
      ))

      Then("the version probe rejects the runtime before the prompt reaches Codex")
      result.isFaillure shouldBe true
      result.toString should include ("0.144.0")
      fixture.profile.driver.executions.map(_.request.capability.print) shouldBe Vector(profile.versionCapability)
    }

    "reject a Codex URL-context purpose without the admitted Web-search capability" in {
      Given("a Codex runtime profile that requests URL context without Web search")
      val fixture = _fixture(_result("{}"))
      given ExecutionContext = _context(fixture)
      val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map(
        "textus.ai.profile" -> "codex-cli",
        "textus.ai.execution-classes.deep-thinking.tools" -> "url_context"
      ))))

      When("the Web-research purpose is required")
      val result = _runner(CodexRuntimeConfig(), profiles).generate(
        AiGenerateRequest(
          "confidential URL",
          requirement = AiRunnerRequirement(
            purpose = Some("web-analysis"),
            purposeRequired = true
          )
        )
      )

      Then("the runtime fails before it selects a process or an implicit fallback")
      result.isFaillure shouldBe true
      result.toString should include ("Codex URL context requires the web_search capability")
      fixture.profile.driver.executions shouldBe Vector.empty
    }

    "reject an unsupported Codex reasoning policy before process selection" in {
      Given("a Codex runtime profile with an unsupported reasoning level")
      val fixture = _fixture(_result("{}"))
      given ExecutionContext = _context(fixture)
      val profiles = AiProfileConfig.fromConfiguration(Some(_configuration(Map(
        "textus.ai.profile" -> "codex-cli",
        "textus.ai.execution-classes.deep-thinking.reasoning-level" -> "experimental"
      ))))

      When("the caller requires that purpose")
      val result = _runner(CodexRuntimeConfig(), profiles).generate(
        AiGenerateRequest(
          "confidential Web research",
          requirement = AiRunnerRequirement(
            purpose = Some("web-analysis"),
            purposeRequired = true
          )
        )
      )

      Then("the unsupported policy is reported without invoking the local process")
      result.isFaillure shouldBe true
      result.toString should include ("unsupported reasoning-level")
      fixture.profile.driver.executions shouldBe Vector.empty
    }
  }

  private def _runner(
    config: CodexRuntimeConfig = CodexRuntimeConfig(),
    profiles: AiProfileConfig = AiProfileConfig.empty
  )(using ExecutionContext): AiRunner = {
    val component = new Component() {}
      .withBinding("generate", AiRuntimeGenerateBinding.create(None, None, None, Some(config)))
      .withBinding("chat", AiRuntimeChatBinding.create(None, None, None, Some(config)))
    new TextusAiRunnerProvider(
      component,
      SpiSelection(provider = Some("codex"), mode = Some("local"), engine = Some("codex-cli")),
      profiles
    ).provide(
      SpiContract("ai-runner", classOf[AiRunner]),
      SpiSelection(provider = Some("codex"), mode = Some("local"), engine = Some("codex-cli"))
    ).toOption.get
  }

  private def _fixture(
    result: ProcessExecutionResult,
    capabilityname: String = "codex-cli",
    versioncapabilityname: Option[String] = None,
    versionresult: ProcessExecutionResult = _result("codex-cli 0.144.0")
  ): ProcessExecutionTestFixture = {
    val capability = ProcessCapabilityId.parseC(capabilityname).toOption.get
    val schema = ProcessArtifactName.parseC("schema").toOption.get
    val capabilities = Map(capability -> result) ++ versioncapabilityname.map { name =>
      ProcessCapabilityId.parseC(name).toOption.get -> versionresult
    }
    val profile = ProcessExecutionTestProfile(capabilities)
    val definitions = capabilities.keys.toVector.map { key =>
      ProcessProgramDefinition.fromRuntimeC(
        key,
        "deterministic-test-program",
        "test-runtime-owned-location",
        Vector.empty,
        ProcessArgumentPolicy(Vector.empty, Set("-", "--output-schema", "schema.json")),
        CodexRuntimeConfig.defaultExecutionLimits,
        Set.empty,
        allowedinputfiles = Set(schema)
      ).toOption.get
    }
    val policy = ProcessExecutionPolicy.createC(definitions).toOption.get
    val admission = ProcessExecutionAdmission.createC(
      policy,
      capabilities.keys.toVector.map(ProcessExecutionGrant(_))
    ).toOption.get
    val execution = admission.admitC(ProcessExecutionRequest(capability)).toOption.get
    ProcessExecutionTestFixture(profile, policy, admission, execution)
  }

  private def _result(
    stdout: String,
    termination: ProcessExecutionTermination = ProcessExecutionTermination.Exited(0)
  ): ProcessExecutionResult = {
    val stdoutbytes = stdout.getBytes(StandardCharsets.UTF_8).toVector
    val empty = ProcessExecutionCapture(Vector.empty, 0L, truncated = false)
    ProcessExecutionResult(
      termination,
      ProcessExecutionCapture(stdoutbytes, stdoutbytes.length.toLong, truncated = false),
      empty,
      Vector.empty,
      elapsedMillis = 1L,
      safeProgramIdentity = "codex-cli"
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
        name = "codex-runtime-provider-spec",
        parent = None,
        observabilityContext = base.observability
      ).copy(
        processExecutionDriverOption = Some(fixture.profile.driver),
        processExecutionAdmissionOption = Some(fixture.admission)
      ),
      unitOfWorkSupplier = () => uow,
      unitOfWorkInterpreterFn = new (UnitOfWorkOp ~> Consequence) {
        def apply[A](operation: UnitOfWorkOp[A]): Consequence[A] =
          new UnitOfWorkInterpreter(uow).interpret(operation)
      },
      commitAction = _ => (),
      abortAction = _ => (),
      disposeAction = _ => (),
      token = "codex-runtime-provider-spec"
    )
    context
  }

  private val _schema: Record = Record.dataAuto(
    "required" -> Vector("title"),
    "fields" -> Vector(
      Record.dataAuto("name" -> "title", "type" -> "string"),
      Record.dataAuto("name" -> "confidence", "type" -> "integer", "optional" -> true)
    )
  )

  private def _codex_executions(
    profiles: AiProfileConfig
  ): Map[String, CodexExecutionProfile] =
    profiles.codexExecutionsC.toOption.getOrElse(Map.empty).map { case (name, execution) =>
      name -> CodexExecutionProfile(
        name,
        execution.model,
        execution.reasoningLevel.flatMap(CodexReasoningLevel.parse),
        execution.tools.toSet
      )
    }

  private def _configuration(values: Map[String, String]): ResolvedConfiguration =
    ResolvedConfiguration(
      Configuration(values.map { case (key, value) => key -> ConfigurationValue.StringValue(value) }),
      ConfigurationTrace.empty
    )
}
