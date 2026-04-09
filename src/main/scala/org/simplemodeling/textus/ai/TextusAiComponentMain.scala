package org.simplemodeling.textus.ai

import org.goldenport.cncf.CncfVersion
import org.goldenport.cncf.component.Component

/*
 * @since   Apr.  9, 2026
 * @version Apr.  9, 2026
 * @author  ASAMI, Tomoharu
 */
object TextusAiComponentMain:
  def runtimeVersion: String = CncfVersion.current

  def createComponent(): Component =
    GeneratedDomainComponentLoader.createStandalone()
