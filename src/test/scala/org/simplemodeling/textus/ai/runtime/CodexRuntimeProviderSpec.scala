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
import org.simplemodeling.textus.ai.provider.codex.{CodexConfig, CodexRuntimeConfig}

/*
 * Executable specification for the managed Codex CLI provider. The test
 * profile proves adapter intent without a Codex binary, account, or network.
 *
 * @since   Jul. 17, 2026
 * @version Jul. 18, 2026
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
      val enabledconfig = CodexConfig.fromConfiguration(enabled)
      val unsafeconfig = CodexConfig.fromConfiguration(unsafe)
      val legacy = CodexRuntimeConfig("local", "codex-cli", 4096L)

      Then("only the explicitly enabled runtime installs the provider adapter")
      disabledconfig shouldBe None
      enabledconfig.map(_.schemaMaximumBytes) shouldBe Some(4096L)
      enabledconfig.map(_.executable) shouldBe Some("/runtime/codex-cli")
      unsafeconfig shouldBe None
      legacy.schemaMaximumBytes shouldBe 4096L
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
      new String(record.inputFiles.head.content.toArray, StandardCharsets.UTF_8) should include ("\"title\"")
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

      When("generation is requested through the Codex provider")
      val results = Vector(
        _runner().generate(genericrequest),
        _runner().generate(scopedrequest)
      )

      Then("the unsupported override fails before any process execution")
      results.foreach { result =>
        result.isFaillure shouldBe true
        result.toString should not include "unconfigured"
      }
      fixture.profile.driver.executions shouldBe Vector.empty
    }
  }

  private def _runner()(using ExecutionContext): AiRunner = {
    val config = CodexRuntimeConfig()
    val component = new Component() {}
      .withBinding("generate", AiRuntimeGenerateBinding.create(None, None, None, Some(config)))
      .withBinding("chat", AiRuntimeChatBinding.create(None, None, None, Some(config)))
    new TextusAiRunnerProvider(
      component,
      SpiSelection(provider = Some("codex"), mode = Some("local"), engine = Some("codex-cli"))
    ).provide(
      SpiContract("ai-runner", classOf[AiRunner]),
      SpiSelection(provider = Some("codex"), mode = Some("local"), engine = Some("codex-cli"))
    ).toOption.get
  }

  private def _fixture(result: ProcessExecutionResult): ProcessExecutionTestFixture = {
    val capability = ProcessCapabilityId.parseC("codex-cli").toOption.get
    val schema = ProcessArtifactName.parseC("schema").toOption.get
    ProcessExecutionTestProfile.admittedC(
      capability,
      result,
      permittedarguments = Set("-", "--output-schema", "schema.json"),
      allowedinputfiles = Set(schema)
    ).toOption.get
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

  private val _schema: Record = Record.dataAuto("required" -> Vector("title"))

  private def _configuration(values: Map[String, String]): ResolvedConfiguration =
    ResolvedConfiguration(
      Configuration(values.map { case (key, value) => key -> ConfigurationValue.StringValue(value) }),
      ConfigurationTrace.empty
    )
}
