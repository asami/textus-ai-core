package org.simplemodeling.textus.ai

import scala.util.Try
import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentInit, ComponentOrigin}
import org.goldenport.cncf.admission.ScopedConcurrencyAdmission
import org.goldenport.cncf.config.{ComponentParameterDecoder, ComponentParameterKey, ComponentParameterPathLeaf, ComponentParameterPathParameters, ComponentParameterPathRoute}
import org.goldenport.cncf.context.{ScopeContext, ScopeKind}
import org.goldenport.cncf.mcp.client.McpClientSocket
import org.goldenport.cncf.operationtool.OperationToolSocket
import org.goldenport.cncf.processexecution.{LocalProcessExecutionDriver, ProcessExecutionAdmission, ProcessExecutionDriver, ProcessExecutionGrant, ProcessExecutionPolicy, ProcessProgramDefinition}
import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.cncf.spi.ai.runner.AiRunnerApplicationPurposeRegistrationSocketSet
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
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
 *  version Jul. 26, 2026
 * @version Aug.  8, 2026
 * @author  ASAMI, Tomoharu
 */
class ComponentFactory extends TextusAiComponent.Factory:
  override def initializationParameterDeclarations: Vector[ComponentParameterKey[?]] =
    Vector(ComponentFactory.profileParameterKey)

  override def initializationParameterPathRoutes: Vector[ComponentParameterPathRoute] =
    Vector(ComponentFactory.executionClassParameterPathRoute)

  override protected def create_Component(
    params: ComponentCreate
  ): Component =
    new TextusAiRuntimeComponent()

  override protected def initialize_component_c(
    component: Component,
    params: ComponentInit
  ): Consequence[Component] =
    for {
      initializedcomponent <- super.initialize_component_c(component, params)
      runtime <- initializedcomponent match {
        case value: TextusAiRuntimeComponent => Consequence.success(value)
        case value => Consequence.componentInvalid(
          new IllegalStateException(
            s"Textus AI factory initialized an incompatible component: ${value.getClass.getName}"
          )
        )
      }
      profile <- params.initializationParameters.resolve(ComponentFactory.profileParameterKey)
      executionclassparameters <- params.initializationParameters.resolvePath(
        ComponentFactory.executionClassParameterPathRoute
      )
      bootstrapcontext = initializedcomponent.subsystem
      rawconfiguration = bootstrapcontext.map(_.configuration)
      profileconfiguration <- ComponentFactory._execution_class_configuration_c(
        rawconfiguration,
        executionclassparameters
      )
      configured = {
        val profiles = AiProfileConfig.fromConfiguration(
          profileconfiguration,
          AiApplicationPurposeCatalog.fromSocket(runtime._registration_socket_set),
          profile.value
        )
        val codex = rawconfiguration.flatMap { value =>
          CodexConfig.fromConfiguration(value, ComponentFactory._codex_execution_profiles(profiles))
        }
        val gemma = ComponentFactory._gemma_runtime_config(rawconfiguration, profiles, bootstrapcontext)
        val ollama = ComponentFactory._ollama_managed_service_config(rawconfiguration, profiles)
        val claude = rawconfiguration.flatMap { value =>
          ClaudeCodeConfig.fromConfiguration(value, ComponentFactory._claude_code_execution_profiles(profiles))
        }
        val antigravity = ComponentFactory._antigravity_runtime_config(
          rawconfiguration,
          ComponentFactory._antigravity_execution_profiles(profiles)
        )
        runtime._configure(codex, antigravity, claude, ollama, profiles)
        val configured = ComponentFactory._configure_runtime_spi(
          runtime,
          rawconfiguration,
          codex,
          runtime._registration_socket_set,
          profiles,
          runtime._concurrency_state_value,
          gemma,
          claude,
          bootstrapcontext,
          antigravity
        )
        configured
      }
    } yield configured

  override protected def create_Core(
    params: ComponentCreate,
    comp: Component
  ): Component.Core =
    spec_create(
      TextusAiComponent.name,
      TextusAiComponent.componentId,
      Vector(
        TextusAiComponent.AggregateService,
        TextusAiComponent.ViewService,
        TextusAiComponent.EntityService
      )
    )

object ComponentFactory:
  private def _required_declaration[A](
    definition: String,
    declaration: Consequence[A]
  ): A =
    declaration match {
      case Consequence.Success(value) => value
      case Consequence.Failure(conclusion) =>
        throw new IllegalStateException(
          s"Invalid Textus AI $definition declaration: ${conclusion.display}"
        )
    }

  private[ai] val _execution_class_parameter_path_leaves: Vector[ComponentParameterPathLeaf[String]] =
    Vector(
      ComponentParameterPathLeaf.optionalString("provider"),
      ComponentParameterPathLeaf.optionalString("mode"),
      ComponentParameterPathLeaf.optionalString("engine"),
      ComponentParameterPathLeaf.optionalString("model"),
      ComponentParameterPathLeaf.optionalString("reasoning-level", Vector("reasoningLevel")),
      ComponentParameterPathLeaf.optionalString("tools", Vector("enabled-tools")),
      ComponentParameterPathLeaf.optionalString("mcp-server-set", Vector("mcpServerSet")),
      ComponentParameterPathLeaf.optionalString("operation-tool-set", Vector("operationToolSet")),
      ComponentParameterPathLeaf.optionalString("max-input-tokens"),
      ComponentParameterPathLeaf.optionalString("max-output-tokens"),
      ComponentParameterPathLeaf.optionalString("max-reasoning-tokens"),
      ComponentParameterPathLeaf.optionalString("max-cost-microunits"),
      ComponentParameterPathLeaf.optionalString("rate-schedule"),
      ComponentParameterPathLeaf.optionalString("timeout-seconds"),
      ComponentParameterPathLeaf.optionalString("record-retry-limit"),
      ComponentParameterPathLeaf.optionalString("max-concurrent"),
      ComponentParameterPathLeaf.optionalString("strategy-max-repairs"),
      ComponentParameterPathLeaf.optionalString("strategy-max-provider-attempts"),
      ComponentParameterPathLeaf.optionalString("fallback-provider"),
      ComponentParameterPathLeaf.optionalString("fallback-mode"),
      ComponentParameterPathLeaf.optionalString("fallback-engine"),
      ComponentParameterPathLeaf.optionalString("fallback-model"),
      ComponentParameterPathLeaf.optionalString("fallback-reasoning-level"),
      ComponentParameterPathLeaf.optionalString("fallback-rate-schedule")
    ).map(declaration => _required_declaration("execution-class leaf", declaration))

  val executionClassParameterPathRoute: ComponentParameterPathRoute =
    _required_declaration("execution-class route", ComponentParameterPathRoute.createC(
      prefix = "textus.ai.execution-classes",
      segmentName = "execution-class",
      leaves = _execution_class_parameter_path_leaves,
      prefixAliases = Vector(
        "textus.ai.executionClasses",
        "textus.runtime.ai.execution-classes",
        "cncf.ai.execution-classes",
        "cncf.runtime.ai.execution-classes"
      )
    ))

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

  private[ai] def _execution_class_configuration_c(
    rawconfiguration: Option[ResolvedConfiguration],
    parameters: ComponentParameterPathParameters
  ): Consequence[Option[ResolvedConfiguration]] = {
    val entries = for {
      segment <- parameters.segments
      leaf <- _execution_class_parameter_path_leaves
    } yield _execution_class_string_entry_c(parameters, segment, leaf)
    _sequence(entries).map { values =>
      val typed = values.flatten.toMap
      rawconfiguration match {
        case Some(raw) =>
          val retained = _flatten_configuration(raw.configuration).filterNot { case (key, _) =>
            _is_execution_class_key(key)
          }
          Some(ResolvedConfiguration(Configuration(retained ++ typed), ConfigurationTrace.empty))
        case None if typed.nonEmpty =>
          Some(ResolvedConfiguration(Configuration(typed), ConfigurationTrace.empty))
        case None =>
          None
      }
    }
  }

  private def _execution_class_string_entry_c(
    parameters: ComponentParameterPathParameters,
    segment: org.goldenport.cncf.config.ComponentParameterPathSegment,
    leaf: ComponentParameterPathLeaf[String]
  ): Consequence[Option[(String, ConfigurationValue)]] =
    parameters.resolve(segment, leaf).map(_.value.map { value =>
      s"${executionClassParameterPathRoute.prefix}.${segment.value}.${leaf.name}" ->
        ConfigurationValue.StringValue(value)
    })

  private def _flatten_configuration(
    configuration: Configuration
  ): Map[String, ConfigurationValue] = {
    def _nested_(prefix: Vector[String], values: Map[String, ConfigurationValue]): Vector[(String, ConfigurationValue)] =
      values.toVector.flatMap {
        case (name, ConfigurationValue.ObjectValue(children)) =>
          _nested_(prefix :+ name, children)
        case (name, value) =>
          Vector((prefix :+ name).mkString(".") -> value)
      }

    val nested = _nested_(Vector.empty, configuration.values).toMap
    val direct = configuration.values.collect {
      case (name, value) if !value.isInstanceOf[ConfigurationValue.ObjectValue] =>
        name -> value
    }
    nested ++ direct
  }

  private def _is_execution_class_key(key: String): Boolean =
    (executionClassParameterPathRoute.prefix +: executionClassParameterPathRoute.prefixAliases)
      .exists(prefix => key == prefix || key.startsWith(s"$prefix."))

  private def _sequence[A](
    consequences: Vector[Consequence[A]]
  ): Consequence[Vector[A]] =
    consequences.foldLeft(Consequence.success(Vector.empty[A])) { (acc, consequence) =>
      acc.flatMap(values => consequence.map(values :+ _))
    }

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

  private[ai] def _configure_runtime_spi(
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
    _configure_runtime_spi(
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

  private[ai] def _configure_runtime_spi(
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
      CodexConfig.fromConfiguration(value, _codex_execution_profiles(profiles))
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
    val mcpclientport = _mcp_client_port_c(profiles) match {
      case Consequence.Success(port) => port
      case Consequence.Failure(conclusion) =>
        throw conclusion.getException.getOrElse(new IllegalArgumentException(conclusion.display))
    }
    val operationtoolport = _operation_tool_port_c(profiles) match {
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

  private[ai] def _mcp_client_port_c(
    profiles: AiProfileConfig
  ): Consequence[Component.Port] =
    profiles.mcpClientRequirementsC.flatMap {
      case Vector() => Consequence.success(Component.Port.empty)
      case requirements =>
        McpClientSocket.createC(requirements).map(Component.Port.input(_))
    }

  private[ai] def _operation_tool_port_c(
    profiles: AiProfileConfig
  ): Consequence[Component.Port] =
    profiles.operationToolRequirementsC.flatMap {
      case Vector() => Consequence.success(Component.Port.empty)
      case requirements =>
        OperationToolSocket.createC(requirements).map(Component.Port.input(_))
    }

  private[ai] def _ollama_managed_service_config(
    configuration: Option[ResolvedConfiguration],
    profiles: AiProfileConfig
  ): Option[OllamaManagedServiceConfig] =
    OllamaManagedServiceConfig.fromConfiguration(configuration, profiles)

  private[ai] def _gemma_runtime_config(
    configuration: Option[ResolvedConfiguration],
    profiles: AiProfileConfig,
    subsystem: Option[Subsystem]
  ): GemmaRuntimeConfig = {
    val gemma = configuration.flatMap(GemmaConfig.fromConfiguration).getOrElse(GemmaConfig.default)
    (_ollama_managed_service_config(configuration, profiles), subsystem) match {
      case (Some(config), Some(owner)) =>
        gemma.copy(bootstrap = Some(new OllamaManagedServiceBootstrap(owner, config)))
      case (Some(_), None) =>
        gemma.copy(configurationError = Some("Gemma managed-docker runtime requires a Component Subsystem"))
      case _ => gemma
    }
  }

  private[ai] def _codex_execution_profiles(
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

  private[ai] def _claude_code_execution_profiles(
    profiles: AiProfileConfig
  ): Map[String, ClaudeCodeExecutionProfile] =
    profiles.claudeCodeExecutionsC.toOption.getOrElse(Map.empty).map { case (name, execution) =>
      name -> ClaudeCodeExecutionProfile(name, execution.model)
    }

  private[ai] def _antigravity_execution_profiles(
    profiles: AiProfileConfig
  ): Map[String, AntigravityExecutionProfile] =
    profiles.antigravityExecutionsC.toOption.getOrElse(Map.empty).map { case (name, execution) =>
      name -> AntigravityExecutionProfile(name, execution.model, execution.tools.toSet)
    }

  private[ai] def _antigravity_runtime_config(
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

  private[ai] def _registration_socket_set: AiRunnerApplicationPurposeRegistrationSocketSet =
    _registrations

  private[ai] def _concurrency_state_value: AiConcurrencyAdmissionState =
    _concurrency_state

  private[ai] def _configure(
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
          observabilitycontext = parent.observabilityContext.createChild(
            parent,
            ScopeKind.Component,
            runtimename
          ),
          processexecutiondriveroption = runtime.map(_._2),
          processexecutionadmissionoption = runtime.map(_._1),
          scopedconcurrencyadmissionoption = admission.map(_._1)
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
