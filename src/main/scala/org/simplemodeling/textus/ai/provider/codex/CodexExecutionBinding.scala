package org.simplemodeling.textus.ai.provider.codex

import org.goldenport.Consequence
import org.goldenport.cncf.processexecution.*

/**
 * Trusted runtime assembly for fixed local Codex CLI capabilities. A purpose
 * resolves a named profile before this binding is selected; callers supply
 * only the bounded stdin/schema suffix.
 */
object CodexExecutionBinding {
  private val _default_capability = "codex-cli"
  private val _schema_artifact = "schema"

  def admissionC(config: CodexRuntimeConfig): Consequence[ProcessExecutionAdmission] =
    for {
      binding <- definitionsAndGrantsC(config)
      (definitions, grants) = binding
      policy <- ProcessExecutionPolicy.createC(definitions)
      admission <- ProcessExecutionAdmission.createC(policy, grants)
    } yield admission

  private[ai] def definitionsAndGrantsC(
    config: CodexRuntimeConfig
  ): Consequence[(Vector[ProcessProgramDefinition], Vector[ProcessExecutionGrant])] =
    _definitions_c(config).flatMap { definitions =>
      _grants_c(definitions).map(grants => definitions -> grants)
    }

  private def _definitions_c(
    config: CodexRuntimeConfig
  ): Consequence[Vector[ProcessProgramDefinition]] =
    _definition_c(_default_capability, _base_arguments, config).flatMap { default =>
      config.executionProfiles.values.toVector.sortBy(_.name).foldLeft(
        Consequence.success(Vector(default))
      ) { (z, profile) =>
        z.flatMap { definitions =>
          _profile_definitions_c(profile, config).map { profiledefinitions =>
            definitions ++ profiledefinitions
          }
        }
      }
    }

  private def _profile_definitions_c(
    profile: CodexExecutionProfile,
    config: CodexRuntimeConfig
  ): Consequence[Vector[ProcessProgramDefinition]] = {
    val versiondefinitions = profile.requiredMinimumCliVersion match {
      case Some(_) => _version_definition_c(profile, config).map(Vector(_))
      case None => Consequence.success(Vector.empty)
    }
    versiondefinitions.flatMap { version =>
      _definition_c(profile.plainCapability, _profile_arguments(profile, web = false), config).flatMap { plain =>
        if (profile.supportsWeb)
          _definition_c(profile.webCapability, _profile_arguments(profile, web = true), config).map { web =>
            version ++ Vector(plain, web)
          }
        else
          Consequence.success(version :+ plain)
      }
    }
  }

  private def _version_definition_c(
    profile: CodexExecutionProfile,
    config: CodexRuntimeConfig
  ): Consequence[ProcessProgramDefinition] =
    for {
      capability <- ProcessCapabilityId.parseC(profile.versionCapability)
      definition <- ProcessProgramDefinition.fromRuntimeC(
        capability = capability,
        safeprogramidentity = profile.versionCapability,
        executablelocation = config.executable,
        fixedarguments = Vector("--version"),
        argumentpolicy = ProcessArgumentPolicy(Vector("--version"), Set.empty, Set(Vector.empty)),
        maximumlimits = config.executionLimits,
        allowedartifacts = Set.empty,
        allowedinputfiles = Set.empty,
        environment = Map.empty
      )
    } yield definition

  private def _definition_c(
    capabilityname: String,
    fixedarguments: Vector[String],
    config: CodexRuntimeConfig
  ): Consequence[ProcessProgramDefinition] =
    for {
      capability <- ProcessCapabilityId.parseC(capabilityname)
      schema <- ProcessArtifactName.parseC(_schema_artifact)
      schemapath <- WorkAreaRelativePath.parseC("schema.json")
      definition <- ProcessProgramDefinition.fromRuntimeC(
        capability = capability,
        safeprogramidentity = capabilityname,
        executablelocation = config.executable,
        fixedarguments = fixedarguments,
        argumentpolicy = ProcessArgumentPolicy(
          fixedarguments,
          _permitted_arguments,
          _permitted_argument_vectors
        ),
        maximumlimits = config.executionLimits,
        allowedartifacts = Set.empty,
        allowedinputfiles = Set(schema),
        allowedinputfilepaths = Map(schema -> schemapath),
        environment = Map.empty
      )
    } yield definition

  private def _grants_c(
    definitions: Vector[ProcessProgramDefinition]
  ): Consequence[Vector[ProcessExecutionGrant]] =
    definitions.foldLeft(Consequence.success(Vector.empty[ProcessExecutionGrant])) { (z, definition) =>
      z.flatMap { grants =>
        ProcessCapabilityId.parseC(definition.safeProgramIdentity).map { capability =>
          grants :+ ProcessExecutionGrant(capability)
        }
      }
    }

  private val _base_arguments = Vector(
    "exec",
    "--sandbox",
    "read-only",
    "--ephemeral",
    "--skip-git-repo-check"
  )

  private def _profile_arguments(
    profile: CodexExecutionProfile,
    web: Boolean
  ): Vector[String] = {
    val search = Option.when(web)("--search").toVector
    val model = Vector("--model", profile.model)
    val reasoning = profile.reasoningLevel.toVector.flatMap { level =>
      Vector("--config", s"model_reasoning_effort=\"${level.id}\"")
    }
    search ++ Vector("exec") ++ model ++ reasoning ++ _base_arguments.drop(1)
  }

  private val _permitted_arguments = Set(
    "-",
    "--output-schema",
    "schema.json"
  )

  private val _permitted_argument_vectors = Set(
    Vector("-"),
    Vector("--output-schema", "schema.json", "-")
  )
}
