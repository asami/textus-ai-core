package org.simplemodeling.textus.ai.provider.codex

import org.goldenport.Consequence
import org.goldenport.cncf.processexecution.*

/**
 * Trusted runtime assembly for the fixed local Codex CLI capability. Provider
 * requests can only supply the bounded suffixes admitted by this definition.
 */
object CodexExecutionBinding {
  private val _capability = "codex-cli"
  private val _schema_artifact = "schema"

  def admissionC(config: CodexRuntimeConfig): Consequence[ProcessExecutionAdmission] =
    for {
      capability <- ProcessCapabilityId.parseC(_capability)
      schema <- ProcessArtifactName.parseC(_schema_artifact)
      schemapath <- WorkAreaRelativePath.parseC("schema.json")
      definition <- ProcessProgramDefinition.fromRuntimeC(
        capability = capability,
        safeprogramidentity = _capability,
        executablelocation = config.executable,
        fixedarguments = _fixed_arguments,
        argumentpolicy = ProcessArgumentPolicy(
          _fixed_arguments,
          _permitted_arguments,
          _permitted_argument_vectors
        ),
        maximumlimits = config.executionLimits,
        allowedartifacts = Set.empty,
        allowedinputfiles = Set(schema),
        allowedinputfilepaths = Map(schema -> schemapath),
        environment = Map.empty
      )
      policy <- ProcessExecutionPolicy.createC(Vector(definition))
      admission <- ProcessExecutionAdmission.createC(
        policy,
        Vector(ProcessExecutionGrant(capability))
      )
    } yield admission

  private val _fixed_arguments = Vector(
    "exec",
    "--sandbox",
    "read-only",
    "--ephemeral",
    "--skip-git-repo-check"
  )

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
