package org.simplemodeling.textus.ai

import scala.util.Try
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentId, ComponentOrigin}
import org.goldenport.cncf.admission.ScopedConcurrencyAdmission
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.context.{ScopeContext, ScopeKind}
import org.goldenport.cncf.processexecution.{LocalProcessExecutionDriver, ProcessExecutionAdmission, ProcessExecutionDriver}
import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ResolvedConfiguration}
import org.simplemodeling.textus.ai.provider.gemma.GemmaConfig
import org.simplemodeling.textus.ai.provider.codex.{CodexConfig, CodexExecutionBinding, CodexRuntimeConfig}
import org.simplemodeling.textus.ai.provider.google.GoogleConfig
import org.simplemodeling.textus.ai.provider.openai.OpenAiConfig
import org.simplemodeling.textus.ai.runtime.{AiProfileConfig, AiRuntimeChatBinding, AiRuntimeGenerateBinding, TextusAiRunnerProvider}

/*
 * @since   Apr.  9, 2026
 * @version Jul. 18, 2026
 * @author  ASAMI, Tomoharu
 */
class ComponentFactory extends TextusAiComponent.Factory:
  override protected def create_Component(
    params: ComponentCreate
  ): Component =
    val bootstrapcontext = params.subsystem
    val configuration = Some(bootstrapcontext.configuration)
    val codex = configuration.flatMap(CodexConfig.fromConfiguration)
    val concurrency = AiProfileConfig.fromConfiguration(configuration).concurrencyAdmissionC.toOption.flatten
    ComponentFactory.configureRuntimeSpi(
      new TextusAiRuntimeComponent(codex, concurrency),
      configuration,
      codex
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
  ): Component =
    val gemma = configuration.flatMap(GemmaConfig.fromConfiguration).getOrElse(GemmaConfig.fromEnvironment())
    val openai = configuration.flatMap(OpenAiConfig.fromConfiguration).orElse(OpenAiConfig.fromEnvironment())
    val google = configuration.flatMap(GoogleConfig.fromConfiguration).orElse(GoogleConfig.fromEnvironment())
    val codex = codexconfig.orElse(configuration.flatMap(CodexConfig.fromConfiguration))
    val defaultselection = _default_selection(configuration, openai.nonEmpty, google.nonEmpty, codex.nonEmpty)
    val profiles = AiProfileConfig.fromConfiguration(configuration)
    val withgenerate = AiRuntimeGenerateBinding.register(component, Some(gemma), openai, google, codex)
    val withchat = AiRuntimeChatBinding.register(withgenerate, Some(gemma), openai, google, codex)
    val runnerprovider = new TextusAiRunnerProvider(withchat, defaultselection, profiles)
    withchat.withPort(
      Component.Port
        .of(
          runnerprovider
        )
      .orElse(withchat.port)
    )

  private def _default_selection(
    configuration: Option[ResolvedConfiguration],
    openaiconfigured: Boolean,
    googleconfigured: Boolean,
    codexconfigured: Boolean
  ): SpiSelection = {
    val configuredprovider = _config_string(configuration, Vector(
      "textus.ai.provider",
      "textus.runtime.ai.provider",
      "cncf.ai.provider",
      "cncf.runtime.ai.provider",
      "textus.ai.llm.provider",
      "cncf.ai.llm.provider"
    )).orElse(_bootstrap_environment.get("AI_LLM_PROVIDER")).map(_canonical_provider)
    val provider =
      configuredprovider.
        orElse(Option.when(googleconfigured)("google")).
        orElse(Option.when(openaiconfigured)("openai")).
        orElse(Option.when(codexconfigured)("codex")).
        getOrElse("gemma")
    SpiSelection(
      provider = Some(provider),
      mode = _config_string(configuration, Vector(
        "textus.ai.mode",
        "textus.runtime.ai.mode",
        "cncf.ai.mode",
        "cncf.runtime.ai.mode",
        "textus.ai.llm.mode",
        "cncf.ai.llm.mode"
      )).orElse(Some(_default_mode(provider))),
      engine = _config_string(configuration, Vector(
        "textus.ai.engine",
        "textus.runtime.ai.engine",
        "cncf.ai.engine",
        "cncf.runtime.ai.engine",
        "textus.ai.llm.engine",
        "cncf.ai.llm.engine"
      )).orElse(Some(_default_engine(provider)))
    )
  }

  private def _config_string(
    configuration: Option[ResolvedConfiguration],
    keys: Vector[String]
  ): Option[String] =
    configuration.flatMap { resolved =>
      keys.iterator.flatMap(key => Try(RuntimeConfig.getString(resolved, key)).toOption.flatten).find(_.trim.nonEmpty).map(_.trim)
    }

  // cncf-car-lint: ignore provider/bootstrap compatibility fallback
  private def _bootstrap_environment: scala.collection.immutable.Map[String, String] = sys.env

  private def _default_mode(provider: String): String =
    provider match {
      case "google" | "openai" => "remote"
      case _ => "local"
    }

  private def _default_engine(provider: String): String =
    provider match {
      case "google" => "gemini"
      case "openai" => "gpt"
      case "codex" => "codex-cli"
      case _ => "ollama"
    }

  private def _canonical_provider(provider: String): String =
    provider.trim.toLowerCase(java.util.Locale.ROOT) match {
      case "codex-cli" => "codex"
      case value => value
    }

private final class TextusAiRuntimeComponent(
  codex: Option[CodexRuntimeConfig],
  concurrency: Option[ScopedConcurrencyAdmission]
) extends TextusAiComponent {
  private lazy val _codex_runtime: Option[(ProcessExecutionAdmission, ProcessExecutionDriver)] =
    codex.flatMap { config =>
      CodexExecutionBinding.admissionC(config).toOption.map { admission =>
        admission -> new LocalProcessExecutionDriver()
      }
    }

  override def withScopeContext(parent: ScopeContext): Component = {
    val scope = (_codex_runtime, concurrency) match {
      case (None, None) => parent
      case (codexruntime, admission) =>
        ScopeContext(
          kind = ScopeKind.Component,
          name = if (codexruntime.nonEmpty) "textus-ai-codex-runtime" else "textus-ai-concurrency-runtime",
          parent = Some(parent),
          observabilityContext = parent.observabilityContext.createChild(
            parent,
            ScopeKind.Component,
            if (codexruntime.nonEmpty) "textus-ai-codex-runtime" else "textus-ai-concurrency-runtime"
          ),
          processExecutionDriverOption = codexruntime.map(_._2),
          processExecutionAdmissionOption = codexruntime.map(_._1),
          scopedConcurrencyAdmissionOption = admission
        )
    }
    super.withScopeContext(scope)
  }
}
