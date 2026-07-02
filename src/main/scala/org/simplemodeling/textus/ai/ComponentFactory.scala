package org.simplemodeling.textus.ai

import org.goldenport.cncf.component.{Component, ComponentCreate}
import org.simplemodeling.textus.ai.provider.gemma.GemmaConfig
import org.simplemodeling.textus.ai.provider.google.GoogleConfig
import org.simplemodeling.textus.ai.provider.openai.OpenAiConfig
import org.simplemodeling.textus.ai.runtime.{AiRuntimeChatBinding, AiRuntimeGenerateBinding, TextusAiRunnerProvider}

/*
 * @since   Apr.  9, 2026
 * @version Jul.  2, 2026
 * @author  ASAMI, Tomoharu
 */
class ComponentFactory extends TextusAiComponent.Factory:
  override protected def create_Component(
    params: ComponentCreate
  ): Component =
    ComponentFactory.configureRuntimeSpi(TextusAiComponent())

object ComponentFactory:
  def create(componentCreate: ComponentCreate): Seq[Component] =
    new ComponentFactory().create(componentCreate).participants

  def createStandalone(): Component =
    configureRuntimeSpi(TextusAiComponent())

  private[ai] def configureRuntimeSpi(
    component: Component
  ): Component =
    val gemma = Some(GemmaConfig.fromEnvironment())
    val openai = OpenAiConfig.fromEnvironment()
    val google = GoogleConfig.fromEnvironment()
    AiRuntimeGenerateBinding.register(component, gemma, openai, google)
    AiRuntimeChatBinding.register(component, gemma, openai, google)
    component.withPort(
      Component.Port
        .of(new TextusAiRunnerProvider(component))
        .orElse(component.port)
    )
