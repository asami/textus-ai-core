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
      definitions <- _definitions_c(config)
      policy <- ProcessExecutionPolicy.createC(definitions)
      grants <- _grants_c(definitions)
      admission <- ProcessExecutionAdmission.createC(policy, grants)
    } yield admission

  private def _definitions_c(
    config: CodexRuntimeConfig
  ): Consequence[Vector[ProcessProgramDefinition]] =
    _definition_c(_default_capability, _base_arguments, config).flatMap { default =>
      config.executionProfiles.values.toVector.sortBy(_.name).foldLeft(
        Consequence.success(Vector(default))
      ) { (z, profile) =>
        z.flatMap { definitions =>
          _definition_c(profile.plainCapability, _profile_arguments(profile, web = false), config).flatMap { plain =>
            if (profile.supportsWeb)
              _definition_c(profile.webCapability, _profile_arguments(profile, web = true), config).map { web =>
                definitions ++ Vector(plain, web)
              }
            else
              Consequence.success(definitions :+ plain)
          }
        }
      }
    }

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
