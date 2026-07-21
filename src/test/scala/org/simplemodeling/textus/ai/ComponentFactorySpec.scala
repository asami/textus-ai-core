package org.simplemodeling.textus.ai

import cats.~>
import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.admission.ConcurrencyScopeId
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.{ExecutionContext, GlobalContext, RuntimeContext, ScopeContext, ScopeKind}
import org.goldenport.cncf.mcp.client.McpClientSocket
import org.goldenport.cncf.processexecution.{LocalProcessExecutionDriver, ProcessArtifactName, ProcessCapabilityId, ProcessExecutionAdmission, ProcessExecutionInputFile, ProcessExecutionRequest, WorkAreaRelativePath}
import org.goldenport.cncf.spi.{SpiContract, SpiResolver, SpiSelection}
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.cncf.workarea.WorkAreaSpace
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ResolvedConfiguration}
import org.goldenport.configuration.ConfigurationValue
import org.goldenport.cncf.spi.ai.runner.{AiChatRequest, AiGenerateRequest, AiMessage, AiRecordRequest, AiRunner, AiRunnerApplicationPurpose, AiRunnerApplicationPurposePolicy, AiRunnerApplicationPurposeRegistration, AiRunnerRequirement}
import org.goldenport.record.Record
import org.simplemodeling.textus.ai.provider.codex.CodexRuntimeConfig
import org.simplemodeling.textus.ai.runtime.{AiApplicationPurposeCatalog, AiProfileConfig, TextusAiRunnerProvider}
import org.scalatest.GivenWhenThen
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 * @version Jul. 21, 2026
 * @author  ASAMI, Tomoharu
 */
final class ComponentFactorySpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen
  with OptionValues {
  "ComponentFactory" should {
    "publish only a normalized MCP client input socket for runtime-owned server sets" in {
      Given("a Textus AI execution class configured with one logical MCP server set")
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
          "textus.ai.execution-classes.standard-work.mcp-server-set" -> ConfigurationValue.StringValue("research")
        )),
        ConfigurationTrace.empty
      )

      When("the component factory constructs its CNCF Port")
      val component = ComponentFactory.configureRuntimeSpi(new Component() {}, Some(configuration))
      val socket = component.port.get[McpClientSocket]

      Then("the consumer exposes only the logical requirement and no installed provider details")
      socket.map(_.serverSetIds.map(_.print)) shouldBe Some(Vector("research"))
      socket.exists(_.isInstalled) shouldBe false
      component.port.inputEntries.collect { case value: McpClientSocket => value }.size shouldBe 1
    }

    "install configured purpose concurrency admission in the provider component scope" in {
      Given("a Textus AI runtime with one bootstrap-registered bounded ArtScene purpose")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemini")
        )),
        ConfigurationTrace.empty
      )
      val subsystem = new Subsystem(
        name = "textus-ai-concurrency-scope-spec",
        configuration = configuration
      )
      val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
      val application = new Component() {}.withPort(Component.Port.of(
        AiRunnerApplicationPurposeRegistration(Vector(AiRunnerApplicationPurpose(
          "artscene-exhibition-web-research",
          "web-analysis",
          AiRunnerApplicationPurposePolicy(maxConcurrent = Some(1))
        )))
      ))
      SpiResolver.resolve(Vector(application, component)) shouldBe a[Consequence.Success[_]]
      val parent = ScopeContext(
        ScopeKind.Runtime,
        "textus-ai-concurrency-scope-spec",
        None,
        summon[ExecutionContext].observability
      )
      component.withScopeContext(parent)
      val key = ConcurrencyScopeId.parseC("artscene-exhibition-web-research").toOption.get

      When("the component resolves and consumes the configured permit")
      val admission = component.scopeContext.scopedConcurrencyAdmissionOption
      val first = admission.flatMap(_.acquireC(key).toOption)
      val saturated = admission.map(_.acquireC(key))
      first.foreach(_.release())

      Then("the component owns a per-purpose runtime admission boundary")
      admission should not be empty
      saturated.exists(_.isFaillure) shouldBe true
    }

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

    "bind commercial providers only from merged CNCF configuration" in {
      Given("an empty runtime configuration and explicitly configured commercial runtimes")
      given ExecutionContext = ExecutionContext.create()
      val empty = ComponentFactory.configureRuntimeSpi(new Component() {}, None)
      val openaiconfigured = ComponentFactory.configureRuntimeSpi(new Component() {}, Some(ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.openai.api-key" -> ConfigurationValue.StringValue("test-openai-key"),
          "textus.ai.openai.model" -> ConfigurationValue.StringValue("gpt-test")
        )),
        ConfigurationTrace.empty
      )))
      val googleconfigured = ComponentFactory.configureRuntimeSpi(new Component() {}, Some(ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.google.api-key" -> ConfigurationValue.StringValue("test-google-key"),
          "textus.ai.google.model" -> ConfigurationValue.StringValue("gemini-test")
        )),
        ConfigurationTrace.empty
      )))
      val anthropicconfigured = ComponentFactory.configureRuntimeSpi(new Component() {}, Some(ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.anthropic.api-key" -> ConfigurationValue.StringValue("test-anthropic-key")
        )),
        ConfigurationTrace.empty
      )))
      val contract = SpiContract("ai-runner", classOf[AiRunner])
      val openaiselection = SpiSelection(provider = Some("openai"))
      val googleselection = SpiSelection(provider = Some("google"))
      val anthropicselection = SpiSelection(provider = Some("anthropic"))

      When("the component resolves commercial AI runners")
      val emptyopenai = empty.port.get[TextusAiRunnerProvider].value.provide(contract, openaiselection)
      val emptygoogle = empty.port.get[TextusAiRunnerProvider].value.provide(contract, googleselection)
      val emptyanthropic = empty.port.get[TextusAiRunnerProvider].value.provide(contract, anthropicselection)
      val configuredopenai = openaiconfigured.port.get[TextusAiRunnerProvider].value.provide(contract, openaiselection)
      val configuredgoogle = googleconfigured.port.get[TextusAiRunnerProvider].value.provide(contract, googleselection)
      val configuredanthropic = anthropicconfigured.port.get[TextusAiRunnerProvider].value.provide(contract, anthropicselection)

      Then("only explicit merged configuration admits the commercial provider")
      emptyopenai.isFaillure shouldBe true
      emptygoogle.isFaillure shouldBe true
      emptyanthropic.isFaillure shouldBe true
      configuredopenai.isSuccess shouldBe true
      configuredgoogle.isSuccess shouldBe true
      configuredanthropic.isSuccess shouldBe true
    }

    "install the enabled Codex capability into the component execution scope" in {
      Given("an explicitly enabled Codex runtime with a trusted executable location")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("codex-cli"),
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

    "declare a managed Ollama service without installing Docker lifecycle process capabilities" in {
      Given("a Gemma runtime profile with managed image and volume overrides")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemma"),
          "textus.ai.gemma.service.image" -> ConfigurationValue.StringValue("example/ollama:test"),
          "textus.ai.gemma.service.volume-name" -> ConfigurationValue.StringValue("textus-ai-test-models")
        )),
        ConfigurationTrace.empty
      )
      val profiles = AiProfileConfig.fromConfiguration(Some(configuration), AiApplicationPurposeCatalog.empty)
      val managed = ComponentFactory.ollamaManagedServiceConfig(Some(configuration), profiles)
      val subsystem = new Subsystem(
        name = "textus-ai-ollama-scope-spec",
        configuration = configuration
      )
      val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
      val parent = ScopeContext(
        ScopeKind.Runtime,
        "textus-ai-ollama-scope-spec",
        None,
        summon[ExecutionContext].observability
      )
      component.withScopeContext(parent)
      When("the component and its managed service definition are resolved")
      val definition = managed.map(_.definitionC)

      Then("lifecycle intent is typed while the component scope has no Docker command capability")
      managed should not be empty
      definition.exists(_.isSuccess) shouldBe true
      definition.flatMap(_.toOption).map(_.image.print) shouldBe Some("example/ollama:test")
      definition.flatMap(_.toOption).map(_.serviceId.print) shouldBe Some("ollama")
      definition.flatMap(_.toOption).map(_.persistence).collect {
        case x: org.goldenport.cncf.servicecontainer.ServiceContainerPersistence.NamedVolumes =>
          x.volumes.map(volume => volume.name.print -> volume.target.print)
      } shouldBe Some(Vector("textus-ai-test-models" -> "/root/.ollama"))
      component.scopeContext.processExecutionAdmissionOption shouldBe empty
      component.scopeContext.processExecutionDriverOption shouldBe empty
    }

    "skip managed Ollama lifecycle for an explicit external endpoint" in {
      Given("a Gemma profile configured to use an externally managed Ollama endpoint")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemma"),
          "textus.ai.gemma.endpoint" -> ConfigurationValue.StringValue("http://ollama.example:11434")
        )),
        ConfigurationTrace.empty
      )
      val subsystem = new Subsystem(
        name = "textus-ai-external-ollama-scope-spec",
        configuration = configuration
      )
      val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
      val parent = ScopeContext(
        ScopeKind.Runtime,
        "textus-ai-external-ollama-scope-spec",
        None,
        summon[ExecutionContext].observability
      )
      component.withScopeContext(parent)
      val profiles = AiProfileConfig.fromConfiguration(Some(configuration), AiApplicationPurposeCatalog.empty)

      When("the managed service configuration and component scope are inspected")
      val managed = ComponentFactory.ollamaManagedServiceConfig(Some(configuration), profiles)

      Then("the external endpoint takes precedence and managed lifecycle is not installed")
      managed shouldBe empty
      component.scopeContext.processExecutionDriverOption shouldBe empty
    }

    "combine managed Gemma lifecycle with the Codex CLI one-shot capability" in {
      Given("a Gemma-simple Codex profile with an enabled Codex CLI runtime")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemma-simple-codex-cli"),
          "textus.ai.codex.enabled" -> ConfigurationValue.StringValue("true"),
          "textus.ai.codex.executable" -> ConfigurationValue.StringValue("/runtime/codex-cli")
        )),
        ConfigurationTrace.empty
      )
      val subsystem = new Subsystem(
        name = "textus-ai-composite-local-scope-spec",
        configuration = configuration
      )
      val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
      val parent = ScopeContext(
        ScopeKind.Runtime,
        "textus-ai-composite-local-scope-spec",
        None,
        summon[ExecutionContext].observability
      )
      component.withScopeContext(parent)
      val codex = ProcessCapabilityId.parseC("codex-cli").toOption.get

      When("the composite runtime admits its one-shot Codex backend")
      val codexresult = ProcessExecutionAdmission.resolveC(component.scopeContext, ProcessExecutionRequest(codex, Vector("-")))

      Then("Ollama lifecycle remains outside Process Execution while Codex stays admitted")
      codexresult.toOption.map(_.effectiveArguments) shouldBe Some(Vector(
        "exec", "--sandbox", "read-only", "--ephemeral", "--skip-git-repo-check", "-"
      ))
    }

    "install the enabled Claude Code capability from its runtime profile" in {
      Given("an enabled Claude Code runtime with a trusted executable location")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("claude-code"),
          "textus.ai.claude.enabled" -> ConfigurationValue.StringValue("true"),
          "textus.ai.claude.executable" -> ConfigurationValue.StringValue("/runtime/claude")
        )),
        ConfigurationTrace.empty
      )
      val subsystem = new Subsystem(
        name = "textus-ai-claude-code-scope-spec",
        configuration = configuration
      )
      val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
      val parent = ScopeContext(
        ScopeKind.Runtime,
        "textus-ai-claude-code-scope-spec",
        None,
        summon[ExecutionContext].observability
      )
      component.withScopeContext(parent)
      val capability = ProcessCapabilityId.parseC("claude-code-profile-runtime-standard-work").toOption.get

      When("the runtime resolves its standard-work process request")
      val result = ProcessExecutionAdmission.resolveC(
        component.scopeContext,
        ProcessExecutionRequest(capability, Vector("-"))
      )

      Then("only Claude Code print-mode JSON arguments and the profile model are admitted")
      result.toOption.map(_.effectiveArguments) shouldBe Some(Vector(
        "-p", "--output-format", "json", "--model", "sonnet", "--max-turns", "1", "-"
      ))
      component.scopeContext.processExecutionDriverOption.exists(_.isInstanceOf[LocalProcessExecutionDriver]) shouldBe true
    }

    "compile a Codex runtime-profile binding into fixed model, reasoning, and Web arguments" in {
      Given("an enabled Codex runtime and a deep-consideration Web binding")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("codex-cli"),
          "textus.ai.codex.enabled" -> ConfigurationValue.StringValue("true"),
          "textus.ai.codex.executable" -> ConfigurationValue.StringValue("/runtime/codex-cli"),
          "textus.ai.execution-classes.deep-consideration.reasoning-level" -> ConfigurationValue.StringValue("high"),
          "textus.ai.execution-classes.deep-consideration.tools" -> ConfigurationValue.StringValue("url_context,web_search")
        )),
        ConfigurationTrace.empty
      )
      val component = new ComponentFactory().create(ComponentCreate(
        new Subsystem(name = "textus-ai-codex-profile-spec", configuration = configuration),
        ComponentOrigin.Main
      )).primary
      component.withScopeContext(ScopeContext(
        ScopeKind.Runtime,
        "textus-ai-codex-profile-spec",
        None,
        summon[ExecutionContext].observability
      ))
      val capability = ProcessCapabilityId.parseC("codex-cli-profile-runtime-deep-consideration-web").toOption.get
      val versioncapability = ProcessCapabilityId.parseC("codex-cli-profile-runtime-deep-consideration-version").toOption.get

      When("the runtime-profile Web capability is resolved")
      val result = ProcessExecutionAdmission.resolveC(
        component.scopeContext,
        ProcessExecutionRequest(capability, Vector("-"))
      )
      val version = ProcessExecutionAdmission.resolveC(
        component.scopeContext,
        ProcessExecutionRequest(versioncapability)
      )

      Then("only the fixed approved model, reasoning, and global Web option are admitted")
      result.toOption.map(_.effectiveArguments) shouldBe Some(Vector(
        "--search", "exec", "--model", "gpt-5.6-sol",
        "--config", "model_reasoning_effort=\"high\"",
        "--sandbox", "read-only", "--ephemeral", "--skip-git-repo-check", "-"
      ))
      version.toOption.map(_.effectiveArguments) shouldBe Some(Vector("--version"))
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

    "execute Codex requests through the provider component scope from a sibling caller scope" in {
      Given("an enabled component-local Codex driver, a sibling caller scope, and a controlled executable")
      val executable = java.nio.file.Files.createTempFile("textus-ai-codex-spec", ".sh")
      java.nio.file.Files.writeString(executable,
        "#!/bin/sh\n" +
          "if [ \"$1\" = \"--version\" ]; then printf 'codex-cli 0.144.0'; exit 0; fi\n" +
          "if [ -f schema.json ]; then marker=record; else marker=plain; fi\n" +
          "if [ -z \"$HOME\" ]; then home=empty; else home=set; fi\n" +
          "args=$(printf '%s' \"$*\" | tr -d '\"')\n" +
          "printf '{\"title\":\"%s:%s:%s\"}' \"$marker\" \"$home\" \"$args\"\n"
      )
      executable.toFile.setExecutable(true)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("codex-cli"),
          "textus.ai.codex.enabled" -> ConfigurationValue.StringValue("true"),
          "textus.ai.codex.executable" -> ConfigurationValue.StringValue(executable.toString)
        )),
        ConfigurationTrace.empty
      )
      val subsystem = new Subsystem(
        name = "textus-ai-codex-live-spec",
        configuration = configuration
      )
      val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
      val parentcontext = ExecutionContext.create()
      val runtimescope = ScopeContext(
        ScopeKind.Runtime,
        "textus-ai-codex-live-spec",
        None,
        parentcontext.observability
      )
      component.withScopeContext(runtimescope)
      val callerscope = ScopeContext(
        ScopeKind.Component,
        "textus-ai-codex-caller-spec",
        Some(runtimescope),
        parentcontext.observability
      )
      given ExecutionContext = _runtime_context(callerscope)
      val runner = component.port.get[TextusAiRunnerProvider].value.provide(
        SpiContract("ai-runner", classOf[AiRunner]),
        SpiSelection()
      ).toOption.get

      When("plain, structured, and chat requests reach the component-owned local driver")
      val (generated, recorded, chatted) = try {
        (
          runner.generate(AiGenerateRequest("plain prompt", requirement = AiRunnerRequirement(
            purpose = Some("software-implementation"),
            purposeRequired = true
          ))),
          runner.generateRecord(AiRecordRequest(
            "record prompt",
            Record.dataAuto("required" -> Vector("title")),
            requirement = AiRunnerRequirement(
              purpose = Some("software-implementation"),
              purposeRequired = true
            )
          )),
          runner.chat(AiChatRequest(
            Vector(AiMessage("user", "chat prompt")),
            requirement = AiRunnerRequirement(
              purpose = Some("software-implementation"),
              purposeRequired = true
            )
          ))
        )
      } finally {
        java.nio.file.Files.deleteIfExists(executable)
      }

      Then("the fixed command, empty environment, and bounded schema file are executed in provider-owned managed WorkAreas")
      withClue(generated.toString) {
        generated.toOption.map(_.text) shouldBe Some("{\"title\":\"plain:empty:exec --model gpt-5.6-sol --config model_reasoning_effort=low --sandbox read-only --ephemeral --skip-git-repo-check -\"}")
      }
      recorded.toOption.flatMap(_.record.getAny("title")) shouldBe Some("record:empty:exec --model gpt-5.6-sol --config model_reasoning_effort=low --sandbox read-only --ephemeral --skip-git-repo-check --output-schema schema.json -")
      chatted.toOption.map(_.message.content) shouldBe Some("{\"title\":\"plain:empty:exec --model gpt-5.6-sol --config model_reasoning_effort=low --sandbox read-only --ephemeral --skip-git-repo-check -\"}")
      callerscope.processExecutionDriverOption shouldBe None
      callerscope.processExecutionAdmissionOption shouldBe None
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
