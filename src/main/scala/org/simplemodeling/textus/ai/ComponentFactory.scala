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
import org.simplemodeling.textus.ai.provider.codex.{CodexConfig, CodexExecutionBinding, CodexExecutionProfile, CodexReasoningLevel, CodexRuntimeConfig}
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
    val profiles = AiProfileConfig.fromConfiguration(configuration)
    val codex = configuration.flatMap { value =>
      CodexConfig.fromConfiguration(value, ComponentFactory.codexExecutionProfiles(profiles))
    }
    val concurrency = profiles.concurrencyAdmissionC.toOption.flatten
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
    val profiles = AiProfileConfig.fromConfiguration(configuration)
    val codex = codexconfig.orElse(configuration.flatMap { value =>
      CodexConfig.fromConfiguration(value, codexExecutionProfiles(profiles))
    })
    val defaultselection = profiles.defaultSelectionC.toOption.getOrElse(
      SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama"))
    )
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
