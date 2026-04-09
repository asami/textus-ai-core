package org.simplemodeling.textus.ai

import org.goldenport.cncf.component.{Component, ComponentCreate}
import org.simplemodeling.textus.ai.gemma.{GemmaChatBinding, GemmaConfig, GemmaGenerateBinding}

/*
 * @since   Apr.  9, 2026
 * @version Apr.  9, 2026
 * @author  ASAMI, Tomoharu
 */
class ComponentFactory extends TextusAiComponent.Factory:
  override protected def create_Components(params: ComponentCreate): Vector[Component] =
    Vector(ComponentFactory.createStandalone())

object ComponentFactory:
  def create(componentCreate: ComponentCreate): Seq[Component] =
    new ComponentFactory().create(componentCreate)

  def createStandalone(): Component =
    val component = TextusAiComponent()
    val config = GemmaConfig.fromEnvironment()
    GemmaGenerateBinding.register(component, config)
    GemmaChatBinding.register(component, config)
    component
