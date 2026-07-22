package org.simplemodeling.textus.ai.provider.antigravity

import org.goldenport.Consequence
import org.goldenport.cncf.processexecution.*

object AntigravityExecutionBinding {
  def definitionsAndGrantsC(
    config: AntigravityRuntimeConfig
  ): Consequence[(Vector[ProcessProgramDefinition], Vector[ProcessExecutionGrant])] =
    config.executionProfiles.values.toVector.sortBy(_.name).foldLeft(
      Consequence.success(Vector.empty[ProcessProgramDefinition])
    ) { (z, profile) =>
      z.flatMap { definitions =>
        _definitions_c(profile, config).map(definitions ++ _)
      }
    }.flatMap { definitions =>
      definitions.foldLeft(Consequence.success(Vector.empty[ProcessExecutionGrant])) { (z, definition) =>
        z.flatMap { grants =>
          ProcessCapabilityId.parseC(definition.safeProgramIdentity).map(capability => grants :+ ProcessExecutionGrant(capability))
        }
      }.map(grants => definitions -> grants)
    }

  private def _definitions_c(
    profile: AntigravityExecutionProfile,
    config: AntigravityRuntimeConfig
  ): Consequence[Vector[ProcessProgramDefinition]] =
    _definition_c(profile.plainCapability, profile, config).flatMap { plain =>
      if (profile.tools.nonEmpty)
        _definition_c(profile.webCapability, profile, config).map(web => Vector(plain, web))
      else
        Consequence.success(Vector(plain))
    }

  private def _definition_c(
    capabilityname: String,
    profile: AntigravityExecutionProfile,
    config: AntigravityRuntimeConfig
  ): Consequence[ProcessProgramDefinition] =
    ProcessCapabilityId.parseC(capabilityname).flatMap { capability =>
      val modelarguments = Option.when(!profile.model.equalsIgnoreCase("auto"))(
        Vector("--model", profile.model)
      ).getOrElse(Vector.empty)
      val fixed = Vector(
        "--output-format",
        "json",
        "--mode",
        "plan",
        "--sandbox"
      ) ++ modelarguments ++ Vector("--print")
      ProcessProgramDefinition.fromRuntimeC(
        capability,
        capabilityname,
        config.executable,
        fixed,
        ProcessArgumentPolicy(
          fixed,
          admission = ProcessArgumentAdmission.BoundedText
        ),
        config.executionLimits,
        Set.empty,
        allowedinputfiles = Set.empty,
        environment = Map("HOME" -> config.home)
      )
    }
}
