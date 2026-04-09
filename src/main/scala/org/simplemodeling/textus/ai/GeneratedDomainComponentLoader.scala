package org.simplemodeling.textus.ai

import org.goldenport.cncf.component.{Component, ComponentCreate}

/*
 * @since   Apr.  9, 2026
 * @version Apr.  9, 2026
 * @author  ASAMI, Tomoharu
 */
object GeneratedDomainComponentLoader:
  def create(componentCreate: ComponentCreate): Seq[Component] =
    ComponentFactory.create(componentCreate)

  def createStandalone(): Component =
    ComponentFactory.createStandalone()
