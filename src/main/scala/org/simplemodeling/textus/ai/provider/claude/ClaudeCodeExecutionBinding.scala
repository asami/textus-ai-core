package org.simplemodeling.textus.ai.provider.claude

import org.goldenport.Consequence
import org.goldenport.cncf.processexecution.*

object ClaudeCodeExecutionBinding {
  def definitionsAndGrantsC(
    config: ClaudeCodeRuntimeConfig
  ): Consequence[(Vector[ProcessProgramDefinition], Vector[ProcessExecutionGrant])] =
    config.executionProfiles.values.toVector.sortBy(_.name).foldLeft(
      Consequence.success(Vector.empty[ProcessProgramDefinition])
    ) { (z, profile) =>
      z.flatMap { definitions =>
        _definition_c(profile, config).map(definitions :+ _)
      }
    }.flatMap { definitions =>
      definitions.foldLeft(Consequence.success(Vector.empty[ProcessExecutionGrant])) { (z, definition) =>
        z.flatMap { grants =>
          ProcessCapabilityId.parseC(definition.safeProgramIdentity).map(capability => grants :+ ProcessExecutionGrant(capability))
        }
      }.map(grants => definitions -> grants)
    }

  private def _definition_c(
    profile: ClaudeCodeExecutionProfile,
    config: ClaudeCodeRuntimeConfig
  ): Consequence[ProcessProgramDefinition] =
    ProcessCapabilityId.parseC(profile.capability).flatMap { capability =>
      val fixed = Vector("-p", "--output-format", "json", "--model", profile.model, "--max-turns", "1")
      ProcessProgramDefinition.fromRuntimeC(
        capability,
        profile.capability,
        config.executable,
        fixed,
        ProcessArgumentPolicy(fixed, Set("-"), Set(Vector("-"))),
        config.executionLimits,
        Set.empty,
        allowedinputfiles = Set.empty,
        environment = Map.empty
      )
    }
}
