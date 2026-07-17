package org.simplemodeling.textus.ai

import cats.~>
import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.{ExecutionContext, GlobalContext, RuntimeContext, ScopeContext, ScopeKind}
import org.goldenport.cncf.processexecution.{LocalProcessExecutionDriver, ProcessArtifactName, ProcessCapabilityId, ProcessExecutionAdmission, ProcessExecutionInputFile, ProcessExecutionRequest, WorkAreaRelativePath}
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.cncf.workarea.WorkAreaSpace
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ResolvedConfiguration}
import org.goldenport.configuration.ConfigurationValue
import org.goldenport.cncf.spi.ai.runner.{AiGenerateRequest, AiRecordRequest, AiRunner}
import org.goldenport.record.Record
import org.simplemodeling.textus.ai.provider.codex.CodexRuntimeConfig
import org.simplemodeling.textus.ai.runtime.TextusAiRunnerProvider
import org.scalatest.GivenWhenThen
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 * @version Jul. 18, 2026
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
          "textus.ai.codex.enabled" -> ConfigurationValue.StringValue("true"),
          "textus.ai.codex.executable" -> ConfigurationValue.StringValue("/runtime/codex-cli")
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

    "install the enabled Codex capability into the component execution scope" in {
      Given("an explicitly enabled Codex runtime with a trusted executable location")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.codex.enabled" -> ConfigurationValue.StringValue("true"),
          "textus.ai.codex.executable" -> ConfigurationValue.StringValue("/runtime/codex-cli")
        )),
        ConfigurationTrace.empty
      )
      val subsystem = new Subsystem(
        name = "textus-ai-codex-scope-spec",
        configuration = configuration
      )
      val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
      val parent = ScopeContext(
        ScopeKind.Runtime,
        "textus-ai-codex-scope-spec",
        None,
        summon[ExecutionContext].observability
      )
      component.withScopeContext(parent)
      val capability = ProcessCapabilityId.parseC("codex-cli").toOption.get

      When("the runtime admits its plain and record provider suffixes")
      val plain = ProcessExecutionAdmission.resolveC(
        component.scopeContext,
        ProcessExecutionRequest(capability, Vector("-"))
      )
      val record = ProcessExecutionAdmission.resolveC(
        component.scopeContext,
        ProcessExecutionRequest(capability, Vector("--output-schema", "schema.json", "-"))
      )
      val unsafe = ProcessExecutionAdmission.resolveC(
        component.scopeContext,
        ProcessExecutionRequest(capability, Vector("-", "--output-schema", "schema.json"))
      )
      val schema = ProcessArtifactName.parseC("schema").toOption.get
      val alternatepath = WorkAreaRelativePath.parseC("alternate-schema.json").toOption.get
      val alternateinput = ProcessExecutionInputFile.createC(schema, alternatepath, Vector(1.toByte), 16L).toOption.get
      val relocated = ProcessExecutionAdmission.resolveC(
        component.scopeContext,
        ProcessExecutionRequest(
          capability,
          Vector("--output-schema", "schema.json", "-"),
          inputFiles = Vector(alternateinput)
        )
      )

      Then("the component contributes only the fixed Codex invocation protocol and local driver")
      plain.toOption.map(_.effectiveArguments) shouldBe Some(Vector("exec", "--sandbox", "read-only", "--ephemeral", "--skip-git-repo-check", "-"))
      record.toOption.map(_.effectiveArguments) shouldBe Some(Vector("exec", "--sandbox", "read-only", "--ephemeral", "--skip-git-repo-check", "--output-schema", "schema.json", "-"))
      unsafe.isFaillure shouldBe true
      relocated.isFaillure shouldBe true
      component.scopeContext.processExecutionDriverOption.exists(_.isInstanceOf[LocalProcessExecutionDriver]) shouldBe true
    }

    "leave Codex process execution unavailable when the provider is disabled" in {
      Given("a Textus AI runtime without enabled Codex configuration")
      given ExecutionContext = ExecutionContext.create()
      val subsystem = new Subsystem(
        name = "textus-ai-codex-disabled-spec",
        configuration = ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty)
      )
      val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
      component.withScopeContext(ScopeContext(
        ScopeKind.Runtime,
        "textus-ai-codex-disabled-spec",
        None,
        summon[ExecutionContext].observability
      ))
      val capability = ProcessCapabilityId.parseC("codex-cli").toOption.get

      When("a Codex process request is resolved")
      val result = ProcessExecutionAdmission.resolveC(
        component.scopeContext,
        ProcessExecutionRequest(capability, Vector("-"))
      )

      Then("no component-owned Codex capability or local driver has been installed")
      result.isFaillure shouldBe true
      component.scopeContext.processExecutionDriverOption shouldBe None
    }

    "execute Codex requests through the component-local driver and managed WorkArea" in {
      Given("an enabled component-local Codex driver and a controlled executable")
      val executable = java.nio.file.Files.createTempFile("textus-ai-codex-spec", ".sh")
      java.nio.file.Files.writeString(executable,
        "#!/bin/sh\n" +
          "if [ -f schema.json ]; then marker=record; else marker=plain; fi\n" +
          "if [ -z \"$HOME\" ]; then home=empty; else home=set; fi\n" +
          "printf '{\"title\":\"%s:%s:%s\"}' \"$marker\" \"$home\" \"$*\"\n"
      )
      executable.toFile.setExecutable(true)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.codex.enabled" -> ConfigurationValue.StringValue("true"),
          "textus.ai.codex.executable" -> ConfigurationValue.StringValue(executable.toString)
        )),
        ConfigurationTrace.empty
      )
      val subsystem = new Subsystem("textus-ai-codex-live-spec", configuration)
      val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
      val parentcontext = ExecutionContext.create()
      component.withScopeContext(ScopeContext(
        ScopeKind.Runtime,
        "textus-ai-codex-live-spec",
        None,
        parentcontext.observability
      ))
      given ExecutionContext = _runtime_context(component.scopeContext)
      val runner = component.port.get[TextusAiRunnerProvider].value.provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      When("plain and structured requests reach the component-owned local driver")
      val (generated, recorded) = try {
        runner.generate(AiGenerateRequest("plain prompt")) -> runner.generateRecord(AiRecordRequest(
          "record prompt",
          Record.dataAuto("required" -> Vector("title"))
        ))
      } finally {
        java.nio.file.Files.deleteIfExists(executable)
      }

      Then("the fixed command, empty environment, and bounded schema file are executed in managed WorkAreas")
      generated.toOption.map(_.text) shouldBe Some("{\"title\":\"plain:empty:exec --sandbox read-only --ephemeral --skip-git-repo-check -\"}")
      recorded.toOption.flatMap(_.record.getAny("title")) shouldBe Some("record:empty:exec --sandbox read-only --ephemeral --skip-git-repo-check --output-schema schema.json -")
    }
  }

  private def _runtime_context(scope: ScopeContext): ExecutionContext = {
    GlobalContext.set(GlobalContext(WorkAreaSpace.create(RuntimeConfig.default)))
    val base = ExecutionContext.create()
    var runtime: RuntimeContext = null
    lazy val context: ExecutionContext = ExecutionContext.withRuntimeContext(base, runtime)
    lazy val uow = new UnitOfWork(context)
    runtime = new RuntimeContext(
      core = RuntimeContext.core(
        name = "textus-ai-codex-live-spec",
        parent = Some(scope),
        observabilityContext = base.observability
      ),
      unitOfWorkSupplier = () => uow,
      unitOfWorkInterpreterFn = new (UnitOfWorkOp ~> Consequence) {
        def apply[A](operation: UnitOfWorkOp[A]): Consequence[A] =
          new UnitOfWorkInterpreter(uow).interpret(operation)
      },
      commitAction = _ => (),
      abortAction = _ => (),
      disposeAction = _ => (),
      token = "textus-ai-codex-live-spec"
    )
    context
  }
}
