package org.simplemodeling.textus.ai

import scala.util.Try
import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentId, ComponentInit, ComponentOrigin}
import org.goldenport.cncf.admission.ScopedConcurrencyAdmission
import org.goldenport.cncf.config.{ComponentParameterDecoder, ComponentParameterKey, RuntimeConfig}
import org.goldenport.cncf.context.{ScopeContext, ScopeKind}
import org.goldenport.cncf.mcp.client.McpClientSocket
import org.goldenport.cncf.operationtool.OperationToolSocket
import org.goldenport.cncf.processexecution.{LocalProcessExecutionDriver, ProcessExecutionAdmission, ProcessExecutionDriver, ProcessExecutionGrant, ProcessExecutionPolicy, ProcessProgramDefinition}
import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.cncf.spi.ai.runner.AiRunnerApplicationPurposeRegistrationSocketSet
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ResolvedConfiguration}
import org.simplemodeling.textus.ai.provider.gemma.{GemmaConfig, GemmaRuntimeConfig, OllamaManagedServiceBootstrap, OllamaManagedServiceConfig}
import org.simplemodeling.textus.ai.provider.codex.{CodexConfig, CodexExecutionBinding, CodexExecutionProfile, CodexReasoningLevel, CodexRuntimeConfig}
import org.simplemodeling.textus.ai.provider.claude.{ClaudeCodeConfig, ClaudeCodeExecutionBinding, ClaudeCodeExecutionProfile, ClaudeCodeRuntimeConfig}
import org.simplemodeling.textus.ai.provider.antigravity.{AntigravityConfig, AntigravityExecutionBinding, AntigravityExecutionProfile, AntigravityRuntimeConfig}
import org.simplemodeling.textus.ai.provider.anthropic.AnthropicConfig
import org.simplemodeling.textus.ai.provider.google.GoogleConfig
import org.simplemodeling.textus.ai.provider.openai.OpenAiConfig
import org.simplemodeling.textus.ai.runtime.{AiApplicationPurposeCatalog, AiConcurrencyAdmissionState, AiProfileConfig, AiRuntimeChatBinding, AiRuntimeGenerateBinding, AiRuntimeProfileCatalog, TextusAiRunnerProvider}

/*
 * @since   Apr.  9, 2026
 * @version Jul. 22, 2026
 * @author  ASAMI, Tomoharu
 */
class ComponentFactory extends TextusAiComponent.Factory:
  override def initializationParameterDeclarations: Vector[ComponentParameterKey[?]] =
    Vector(ComponentFactory.profileParameterKey)

  override protected def create_Component(
    params: ComponentCreate
  ): Component =
    new TextusAiRuntimeComponent()

  override protected def initialize_component_c(
    component: Component,
    params: ComponentInit
  ): Consequence[Component] =
    val runtime = component.asInstanceOf[TextusAiRuntimeComponent]
    params.initializationParameters.resolve(ComponentFactory.profileParameterKey).flatMap { resolution =>
      val bootstrapcontext = params.subsystem
      val configuration = Some(bootstrapcontext.configuration)
      val profiles = AiProfileConfig.fromConfiguration(
        configuration,
        AiApplicationPurposeCatalog.fromSocket(runtime.registrations),
        resolution.value
      )
      val codex = configuration.flatMap { value =>
        CodexConfig.fromConfiguration(value, ComponentFactory.codexExecutionProfiles(profiles))
      }
      val gemma = ComponentFactory.gemmaRuntimeConfig(configuration, profiles, Some(bootstrapcontext))
      val ollama = ComponentFactory.ollamaManagedServiceConfig(configuration, profiles)
      val claude = configuration.flatMap { value =>
        ClaudeCodeConfig.fromConfiguration(value, ComponentFactory.claudeCodeExecutionProfiles(profiles))
      }
      val antigravity = ComponentFactory.antigravityRuntimeConfig(
        configuration,
        ComponentFactory.antigravityExecutionProfiles(profiles)
      )
      runtime.configure(codex, antigravity, claude, ollama, profiles)
      val configured = ComponentFactory.configureRuntimeSpi(
        runtime,
        configuration,
        codex,
        runtime.registrations,
        profiles,
        runtime.concurrencyState,
        gemma,
        claude,
        Some(bootstrapcontext),
        antigravity
      )
      super.initialize_component_c(configured, params)
    }

  override protected def create_Core(
    params: ComponentCreate,
    comp: Component
  ): Component.Core =
    spec_create(
      _runtime_component_name,
      ComponentId(_runtime_component_id),
      Vector(
        TextusAiComponent.AggregateService,
        TextusAiComponent.ViewService,
        TextusAiComponent.EntityService
      )
    )

  private val _runtime_component_name = "textus-ai-runtime"
  private val _runtime_component_id = "TextusAiRuntime"

object ComponentFactory:
  val profileParameterKey: ComponentParameterKey[String] =
    ComponentParameterKey.optional(
      "textus.ai.profile",
      ComponentParameterDecoder { value =>
        ComponentParameterDecoder.string.decode(value).flatMap { name =>
          AiRuntimeProfileCatalog.profile(name) match {
            case Some(profile) => Consequence.success(profile.name)
            case None => Consequence.configurationInvalid(s"AI runtime profile is not supported: $name")
          }
        }
      }
    )

  def create(componentCreate: ComponentCreate): Seq[Component] =
    new ComponentFactory().create(componentCreate).participants

  def createStandalone(): Component = {
    val subsystem = new Subsystem(
      name = "textus-ai-runtime-standalone",
      configuration = ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty)
    )
    val bundle = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main))
    bundle.primary
  }

  private[ai] def configureRuntimeSpi(
    component: Component,
    configuration: Option[ResolvedConfiguration],
    codexconfig: Option[CodexRuntimeConfig] = None
  ): Component = {
    val registrations = new AiRunnerApplicationPurposeRegistrationSocketSet {}
    val profiles = AiProfileConfig.fromConfiguration(
      configuration,
      AiApplicationPurposeCatalog.fromSocket(registrations)
    )
    configureRuntimeSpi(
      component,
      configuration,
      codexconfig,
      registrations,
      profiles,
      new AiConcurrencyAdmissionState(),
      gemmaRuntimeConfig(configuration, profiles, component.subsystem),
      configuration.flatMap(value => ClaudeCodeConfig.fromConfiguration(value, claudeCodeExecutionProfiles(profiles))),
      component.subsystem,
      antigravityRuntimeConfig(configuration, antigravityExecutionProfiles(profiles))
    )
  }

  private[ai] def configureRuntimeSpi(
    component: Component,
    configuration: Option[ResolvedConfiguration],
    codexconfig: Option[CodexRuntimeConfig],
    registrations: AiRunnerApplicationPurposeRegistrationSocketSet,
    profiles: AiProfileConfig,
    concurrencystate: AiConcurrencyAdmissionState,
    gemmaconfig: GemmaRuntimeConfig,
    claudeconfig: Option[ClaudeCodeRuntimeConfig],
    subsystem: Option[Subsystem]
  ): Component =
    configureRuntimeSpi(
      component,
      configuration,
      codexconfig,
      registrations,
      profiles,
      concurrencystate,
      gemmaconfig,
      claudeconfig,
      subsystem,
      None
    )

  private[ai] def configureRuntimeSpi(
    component: Component,
    configuration: Option[ResolvedConfiguration],
    codexconfig: Option[CodexRuntimeConfig],
    registrations: AiRunnerApplicationPurposeRegistrationSocketSet,
    profiles: AiProfileConfig,
    concurrencystate: AiConcurrencyAdmissionState,
    gemmaconfig: GemmaRuntimeConfig,
    claudeconfig: Option[ClaudeCodeRuntimeConfig],
    subsystem: Option[Subsystem],
    antigravityconfig: Option[AntigravityRuntimeConfig]
  ): Component =
    val openai = configuration.flatMap(OpenAiConfig.fromConfiguration)
    val anthropic = configuration.flatMap(AnthropicConfig.fromConfiguration)
    val google = configuration.flatMap(GoogleConfig.fromConfiguration)
    val codex = codexconfig.orElse(configuration.flatMap { value =>
      CodexConfig.fromConfiguration(value, codexExecutionProfiles(profiles))
    })
    val defaultselection = profiles.defaultSelectionC.toOption.getOrElse(
      SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama"))
    )
    val withgenerate = AiRuntimeGenerateBinding.register(
      component,
      Some(gemmaconfig),
      openai,
      google,
      codex,
      claudeconfig,
      anthropic,
      antigravityconfig
    )
    val withchat = AiRuntimeChatBinding.register(
      withgenerate,
      Some(gemmaconfig),
      openai,
      google,
      codex,
      claudeconfig,
      anthropic,
      antigravityconfig
    )
    val runnerprovider = new TextusAiRunnerProvider(
      withchat,
      defaultselection,
      profiles,
      concurrencystate,
      subsystem
    )
    val mcpclientport = mcpClientPortC(profiles) match {
      case Consequence.Success(port) => port
      case Consequence.Failure(conclusion) =>
        throw conclusion.getException.getOrElse(new IllegalArgumentException(conclusion.display))
    }
    val operationtoolport = operationToolPortC(profiles) match {
      case Consequence.Success(port) => port
      case Consequence.Failure(conclusion) =>
        throw conclusion.getException.getOrElse(new IllegalArgumentException(conclusion.display))
    }
    withchat.withPort(
      Component.Port
        .of(
          runnerprovider
        )
      .orElse(mcpclientport)
      .orElse(operationtoolport)
      .orElse(Component.Port.input(registrations))
      .orElse(withchat.port)
    )

  private[ai] def mcpClientPortC(
    profiles: AiProfileConfig
  ): Consequence[Component.Port] =
    profiles.mcpClientRequirementsC.flatMap {
      case Vector() => Consequence.success(Component.Port.empty)
      case requirements =>
        McpClientSocket.createC(requirements).map(Component.Port.input(_))
    }

  private[ai] def operationToolPortC(
    profiles: AiProfileConfig
  ): Consequence[Component.Port] =
    profiles.operationToolRequirementsC.flatMap {
      case Vector() => Consequence.success(Component.Port.empty)
      case requirements =>
        OperationToolSocket.createC(requirements).map(Component.Port.input(_))
    }

  private[ai] def ollamaManagedServiceConfig(
    configuration: Option[ResolvedConfiguration],
    profiles: AiProfileConfig
  ): Option[OllamaManagedServiceConfig] =
    OllamaManagedServiceConfig.fromConfiguration(configuration, profiles)

  private[ai] def gemmaRuntimeConfig(
    configuration: Option[ResolvedConfiguration],
    profiles: AiProfileConfig,
    subsystem: Option[Subsystem]
  ): GemmaRuntimeConfig = {
    val gemma = configuration.flatMap(GemmaConfig.fromConfiguration).getOrElse(GemmaConfig.default)
    (ollamaManagedServiceConfig(configuration, profiles), subsystem) match {
      case (Some(config), Some(owner)) =>
        gemma.copy(bootstrap = Some(new OllamaManagedServiceBootstrap(owner, config)))
      case (Some(_), None) =>
        gemma.copy(configurationError = Some("Gemma managed-docker runtime requires a Component Subsystem"))
      case _ => gemma
    }
  }

  private[ai] def codexExecutionProfiles(
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

  private[ai] def claudeCodeExecutionProfiles(
    profiles: AiProfileConfig
  ): Map[String, ClaudeCodeExecutionProfile] =
    profiles.claudeCodeExecutionsC.toOption.getOrElse(Map.empty).map { case (name, execution) =>
      name -> ClaudeCodeExecutionProfile(name, execution.model)
    }

  private[ai] def antigravityExecutionProfiles(
    profiles: AiProfileConfig
  ): Map[String, AntigravityExecutionProfile] =
    profiles.antigravityExecutionsC.toOption.getOrElse(Map.empty).map { case (name, execution) =>
      name -> AntigravityExecutionProfile(name, execution.model, execution.tools.toSet)
    }

  private[ai] def antigravityRuntimeConfig(
    configuration: Option[ResolvedConfiguration],
    executions: Map[String, AntigravityExecutionProfile]
  ): Option[AntigravityRuntimeConfig] =
    configuration.flatMap { value =>
      AntigravityConfig.fromConfigurationC(value, executions) match {
        case Consequence.Success(config) => config
        case Consequence.Failure(conclusion) =>
          throw conclusion.getException.getOrElse(new IllegalArgumentException(conclusion.display))
      }
    }

private[ai] final class TextusAiRuntimeComponent() extends TextusAiComponent {
  private var _codex: Option[CodexRuntimeConfig] = None
  private var _antigravity: Option[AntigravityRuntimeConfig] = None
  private var _claude: Option[ClaudeCodeRuntimeConfig] = None
  private var _ollama: Option[OllamaManagedServiceConfig] = None
  private var _profiles: AiProfileConfig = AiProfileConfig.empty
  private val _registrations = new AiRunnerApplicationPurposeRegistrationSocketSet {}
  private val _concurrency_state = new AiConcurrencyAdmissionState()

  private[ai] def registrations: AiRunnerApplicationPurposeRegistrationSocketSet =
    _registrations

  private[ai] def concurrencyState: AiConcurrencyAdmissionState =
    _concurrency_state

  private[ai] def configure(
    codex: Option[CodexRuntimeConfig],
    antigravity: Option[AntigravityRuntimeConfig],
    claude: Option[ClaudeCodeRuntimeConfig],
    ollama: Option[OllamaManagedServiceConfig],
    profiles: AiProfileConfig
  ): Unit = {
    _codex = codex
    _antigravity = antigravity
    _claude = claude
    _ollama = ollama
    _profiles = profiles
  }

  private lazy val _process_runtime: Option[(ProcessExecutionAdmission, ProcessExecutionDriver)] = {
    val codexbinding = _codex.map(CodexExecutionBinding.definitionsAndGrantsC).getOrElse(
      Consequence.success(Vector.empty -> Vector.empty)
    )
    val claudebinding = _claude.map(ClaudeCodeExecutionBinding.definitionsAndGrantsC).getOrElse(
      Consequence.success(Vector.empty -> Vector.empty)
    )
    val antigravitybinding = _antigravity.map(AntigravityExecutionBinding.definitionsAndGrantsC).getOrElse(
      Consequence.success(Vector.empty -> Vector.empty)
    )
    (for {
      codexparts <- codexbinding
      antigravityparts <- antigravitybinding
      claudeparts <- claudebinding
      definitions = codexparts._1 ++ antigravityparts._1 ++ claudeparts._1
      grants = codexparts._2 ++ antigravityparts._2 ++ claudeparts._2
      runtime <- if (definitions.nonEmpty)
        for {
          policy <- ProcessExecutionPolicy.createC(definitions)
          admission <- ProcessExecutionAdmission.createC(policy, grants)
        } yield Some(admission -> new LocalProcessExecutionDriver())
      else
        Consequence.success(None)
    } yield runtime).toOption.flatten
  }

  private lazy val _concurrency: Option[(ScopedConcurrencyAdmission, Set[org.goldenport.cncf.admission.ConcurrencyScopeId])] =
    _profiles.concurrencyAdmissionWithScopesC.toOption.flatten

  override def withScopeContext(parent: ScopeContext): Component = {
    val localruntime = _process_runtime
    val runtimename = _runtime_name
    val scope = (localruntime, _concurrency) match {
      case (None, None) => parent
      case (runtime, admission) =>
        ScopeContext(
          kind = ScopeKind.Component,
          name = runtimename,
          parent = Some(parent),
          observabilityContext = parent.observabilityContext.createChild(
            parent,
            ScopeKind.Component,
            runtimename
          ),
          processExecutionDriverOption = runtime.map(_._2),
          processExecutionAdmissionOption = runtime.map(_._1),
          scopedConcurrencyAdmissionOption = admission.map(_._1)
        )
    }
    _concurrency_state.registerBootstrap(_concurrency.map(_._2).getOrElse(Set.empty))
    super.withScopeContext(scope)
  }

  private def _runtime_name: String =
    if (Vector(_codex, _antigravity, _claude, _ollama).count(_.nonEmpty) > 1)
      "textus-ai-local-runtime"
    else if (_ollama.nonEmpty)
      "textus-ai-ollama-runtime"
    else if (_codex.nonEmpty)
      "textus-ai-codex-runtime"
    else if (_antigravity.nonEmpty)
      "textus-ai-antigravity-runtime"
    else if (_claude.nonEmpty)
      "textus-ai-claude-code-runtime"
    else
      "textus-ai-concurrency-runtime"
}
