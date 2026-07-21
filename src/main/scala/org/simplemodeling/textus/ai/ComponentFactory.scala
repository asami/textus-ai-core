package org.simplemodeling.textus.ai

import scala.util.Try
import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentId, ComponentOrigin}
import org.goldenport.cncf.admission.ScopedConcurrencyAdmission
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.{ScopeContext, ScopeKind}
import org.goldenport.cncf.mcp.client.McpClientSocket
import org.goldenport.cncf.processexecution.{LocalProcessExecutionDriver, ProcessExecutionAdmission, ProcessExecutionDriver, ProcessExecutionGrant, ProcessExecutionPolicy, ProcessProgramDefinition}
import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.cncf.spi.ai.runner.AiRunnerApplicationPurposeRegistrationSocketSet
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ResolvedConfiguration}
import org.simplemodeling.textus.ai.provider.gemma.{GemmaConfig, GemmaRuntimeConfig, OllamaManagedServiceBootstrap, OllamaManagedServiceConfig}
import org.simplemodeling.textus.ai.provider.codex.{CodexConfig, CodexExecutionBinding, CodexExecutionProfile, CodexReasoningLevel, CodexRuntimeConfig}
import org.simplemodeling.textus.ai.provider.claude.{ClaudeCodeConfig, ClaudeCodeExecutionBinding, ClaudeCodeExecutionProfile, ClaudeCodeRuntimeConfig}
import org.simplemodeling.textus.ai.provider.anthropic.AnthropicConfig
import org.simplemodeling.textus.ai.provider.google.GoogleConfig
import org.simplemodeling.textus.ai.provider.openai.OpenAiConfig
import org.simplemodeling.textus.ai.runtime.{AiApplicationPurposeCatalog, AiConcurrencyAdmissionState, AiProfileConfig, AiRuntimeChatBinding, AiRuntimeGenerateBinding, TextusAiRunnerProvider}

/*
 * @since   Apr.  9, 2026
 * @version Jul. 21, 2026
 * @author  ASAMI, Tomoharu
 */
class ComponentFactory extends TextusAiComponent.Factory:
  override protected def create_Component(
    params: ComponentCreate
  ): Component =
    val bootstrapcontext = params.subsystem
    val configuration = Some(bootstrapcontext.configuration)
    val registrations = new AiRunnerApplicationPurposeRegistrationSocketSet {}
    val profiles = AiProfileConfig.fromConfiguration(
      configuration,
      AiApplicationPurposeCatalog.fromSocket(registrations)
    )
    val codex = configuration.flatMap { value =>
      CodexConfig.fromConfiguration(value, ComponentFactory.codexExecutionProfiles(profiles))
    }
    val gemma = ComponentFactory.gemmaRuntimeConfig(configuration, profiles, Some(bootstrapcontext))
    val ollama = ComponentFactory.ollamaManagedServiceConfig(configuration, profiles)
    val claude = configuration.flatMap { value =>
      ClaudeCodeConfig.fromConfiguration(value, ComponentFactory.claudeCodeExecutionProfiles(profiles))
    }
    val concurrencystate = new AiConcurrencyAdmissionState()
    ComponentFactory.configureRuntimeSpi(
      new TextusAiRuntimeComponent(codex, claude, ollama, profiles, concurrencystate),
      configuration,
      codex,
      registrations,
      profiles,
      concurrencystate,
      gemma,
      claude
    )

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
      configuration.flatMap(value => ClaudeCodeConfig.fromConfiguration(value, claudeCodeExecutionProfiles(profiles)))
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
    claudeconfig: Option[ClaudeCodeRuntimeConfig]
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
    val withgenerate = AiRuntimeGenerateBinding.register(component, Some(gemmaconfig), openai, google, codex, claudeconfig, anthropic)
    val withchat = AiRuntimeChatBinding.register(withgenerate, Some(gemmaconfig), openai, google, codex, claudeconfig, anthropic)
    val runnerprovider = new TextusAiRunnerProvider(withchat, defaultselection, profiles, concurrencystate)
    val mcpclientport = mcpClientPortC(profiles) match {
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

private final class TextusAiRuntimeComponent(
  codex: Option[CodexRuntimeConfig],
  claude: Option[ClaudeCodeRuntimeConfig],
  ollama: Option[OllamaManagedServiceConfig],
  profiles: AiProfileConfig,
  concurrencystate: AiConcurrencyAdmissionState
) extends TextusAiComponent {
  private lazy val _process_runtime: Option[(ProcessExecutionAdmission, ProcessExecutionDriver)] = {
    val codexbinding = codex.map(CodexExecutionBinding.definitionsAndGrantsC).getOrElse(
      Consequence.success(Vector.empty -> Vector.empty)
    )
    val claudebinding = claude.map(ClaudeCodeExecutionBinding.definitionsAndGrantsC).getOrElse(
      Consequence.success(Vector.empty -> Vector.empty)
    )
    (for {
      codexparts <- codexbinding
      claudeparts <- claudebinding
      definitions = codexparts._1 ++ claudeparts._1
      grants = codexparts._2 ++ claudeparts._2
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
    profiles.concurrencyAdmissionWithScopesC.toOption.flatten

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
    concurrencystate.registerBootstrap(_concurrency.map(_._2).getOrElse(Set.empty))
    super.withScopeContext(scope)
  }

  private def _runtime_name: String =
    if (Vector(codex, claude, ollama).count(_.nonEmpty) > 1)
      "textus-ai-local-runtime"
    else if (ollama.nonEmpty)
      "textus-ai-ollama-runtime"
    else if (codex.nonEmpty)
      "textus-ai-codex-runtime"
    else if (claude.nonEmpty)
      "textus-ai-claude-code-runtime"
    else
      "textus-ai-concurrency-runtime"
}
