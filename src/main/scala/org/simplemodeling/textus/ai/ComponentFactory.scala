package org.simplemodeling.textus.ai

import scala.util.Try
import org.goldenport.cncf.component.{Component, ComponentCreate}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.configuration.ResolvedConfiguration
import org.simplemodeling.textus.ai.provider.gemma.GemmaConfig
import org.simplemodeling.textus.ai.provider.google.GoogleConfig
import org.simplemodeling.textus.ai.provider.openai.OpenAiConfig
import org.simplemodeling.textus.ai.runtime.{AiProfileConfig, AiRuntimeChatBinding, AiRuntimeGenerateBinding, TextusAiRunnerProvider}

/*
 * @since   Apr.  9, 2026
 * @version Jul.  5, 2026
 * @author  ASAMI, Tomoharu
 */
class ComponentFactory extends TextusAiComponent.Factory:
  override protected def create_Component(
    params: ComponentCreate
  ): Component =
    val bootstrapcontext = params.subsystem
    ComponentFactory.configureRuntimeSpi(TextusAiComponent(), Some(bootstrapcontext.configuration))

object ComponentFactory:
  def create(componentCreate: ComponentCreate): Seq[Component] =
    new ComponentFactory().create(componentCreate).participants

  def createStandalone(): Component =
    configureRuntimeSpi(TextusAiComponent(), None)

  private[ai] def configureRuntimeSpi(
    component: Component,
    configuration: Option[ResolvedConfiguration]
  ): Component =
    val gemma = Some(GemmaConfig.fromEnvironment())
    val openai = configuration.flatMap(OpenAiConfig.fromConfiguration).orElse(OpenAiConfig.fromEnvironment())
    val google = configuration.flatMap(GoogleConfig.fromConfiguration).orElse(GoogleConfig.fromEnvironment())
    val defaultselection = _default_selection(configuration, openai.nonEmpty, google.nonEmpty)
    val profiles = AiProfileConfig.fromConfiguration(configuration)
    AiRuntimeGenerateBinding.register(component, gemma, openai, google)
    AiRuntimeChatBinding.register(component, gemma, openai, google)
    component.withPort(
      Component.Port
        .of(new TextusAiRunnerProvider(component, defaultselection, profiles))
        .orElse(component.port)
    )

  private def _default_selection(
    configuration: Option[ResolvedConfiguration],
    openaiconfigured: Boolean,
    googleconfigured: Boolean
  ): SpiSelection = {
    val configuredprovider = _config_string(configuration, Vector(
      "textus.ai.provider",
      "textus.runtime.ai.provider",
      "cncf.ai.provider",
      "cncf.runtime.ai.provider",
      "textus.ai.llm.provider",
      "cncf.ai.llm.provider"
    )).orElse(sys.env.get("AI_LLM_PROVIDER"))
    val provider =
      configuredprovider.
        orElse(Option.when(googleconfigured)("google")).
        orElse(Option.when(openaiconfigured)("openai")).
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

  private def _default_mode(provider: String): String =
    provider match {
      case "google" | "openai" => "remote"
      case _ => "local"
    }

  private def _default_engine(provider: String): String =
    provider match {
      case "google" => "gemini"
      case "openai" => "gpt"
      case _ => "ollama"
    }
