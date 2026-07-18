package org.simplemodeling.textus.ai.runtime

import scala.util.Try
import org.goldenport.Consequence
import org.goldenport.cncf.admission.{ConcurrencyGrant, ConcurrencyScopeId, ScopedConcurrencyAdmission}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.spi.ai.runner.{AiExecutionClass, AiRunnerRequirement, AiTool}
import org.goldenport.configuration.ResolvedConfiguration

/*
 * Purpose and model profile configuration for Textus AI runtime selection.
 *
 * Request-level AiRunnerRequirement fields still have highest priority. This
 * resolver fills only missing provider/mode/engine/model fields from operator-managed
 * configuration, so application code can pass a purpose without hard-coding a
 * concrete model.
 *
 * Supported key families:
 *
 * - textus.ai.purposes.<purpose>.model-profile
 * - textus.ai.purposes.<purpose>.base-purpose
 * - textus.ai.purposes.<purpose>.provider
 * - textus.ai.purposes.<purpose>.mode
 * - textus.ai.purposes.<purpose>.engine
 * - textus.ai.purposes.<purpose>.model
 * - textus.ai.purposes.<purpose>.tools
 * - textus.ai.purposes.<purpose>.max-output-tokens
 * - textus.ai.purposes.<purpose>.timeout-seconds
 * - textus.ai.purposes.<purpose>.record-retry-limit
 * - textus.ai.purposes.<purpose>.max-concurrent
 * - textus.ai.purposes.<purpose>.output-schema-id
 * - textus.ai.purposes.<purpose>.prompt-contract-id
 * - textus.ai.model-profiles.<profile>.provider
 * - textus.ai.model-profiles.<profile>.mode
 * - textus.ai.model-profiles.<profile>.engine
 * - textus.ai.model-profiles.<profile>.model
 * - textus.ai.model-profiles.<profile>.reasoning-level
 * - textus.ai.model-profiles.<profile>.tools
 * - textus.ai.model-profiles.<profile>.role
 * - textus.ai.model-profiles.<profile>.quality
 * - textus.ai.model-profiles.<profile>.cost
 * - textus.ai.model-profiles.<profile>.latency
 * - textus.ai.execution-classes.<execution-class>.model-profile
 * - textus.ai.generic-purposes.<purpose>.execution-class
 * - textus.ai.levels.<level>.model-profile (migration fallback)
 * - textus.ai.generic-purposes.<purpose>.level (migration fallback)
 *
 * A configured logical level is also an implicit caller purpose. For example,
 * purpose `standard-work` resolves through
 * textus.ai.levels.standard-work.model-profile. Model-profile names are never
 * implicit caller purposes.
 *
 * @since   Jul.  4, 2026
 * @version Jul. 18, 2026
 * @author  ASAMI, Tomoharu
 */
private[textus] final case class AiModelProfile(
  name: String,
  provider: Option[String] = None,
  mode: Option[String] = None,
  engine: Option[String] = None,
  model: Option[String] = None,
  reasoningLevel: Option[String] = None,
  tools: Vector[AiTool] = Vector.empty,
  role: Option[String] = None,
  quality: Option[String] = None,
  cost: Option[String] = None,
  latency: Option[String] = None,
  description: Option[String] = None
)

private[textus] final case class AiPurposeProfile(
  purpose: String,
  basePurpose: Option[String] = None,
  executionClass: Option[String] = None,
  level: Option[String] = None,
  modelProfile: Option[String] = None,
  provider: Option[String] = None,
  mode: Option[String] = None,
  engine: Option[String] = None,
  model: Option[String] = None,
  role: Option[String] = None,
  quality: Option[String] = None,
  cost: Option[String] = None,
  latency: Option[String] = None,
  tools: Vector[AiTool] = Vector.empty,
  maxOutputTokens: Option[String] = None,
  timeoutSeconds: Option[String] = None,
  recordRetryLimit: Option[String] = None,
  maxConcurrent: Option[String] = None,
  outputSchemaId: Option[String] = None,
  promptContractId: Option[String] = None,
  unsupportedPromptPolicy: Option[String] = None
) {
  def applyTo(
    requirement: AiRunnerRequirement,
    modelprofile: Option[AiModelProfile]
  ): AiRunnerRequirement =
    requirement.copy(
      provider = requirement.provider.orElse(provider).orElse(modelprofile.flatMap(_.provider)),
      mode = requirement.mode.orElse(mode).orElse(modelprofile.flatMap(_.mode)),
      engine = requirement.engine.orElse(engine).orElse(modelprofile.flatMap(_.engine)),
      model = requirement.model.orElse(model).orElse(modelprofile.flatMap(_.model)),
      executionClass = requirement.executionClass.orElse(executionClass.flatMap(AiExecutionClass.parse)),
      tools = if (requirement.tools.nonEmpty) requirement.tools else tools match {
        case values if values.nonEmpty => values
        case _ => modelprofile.map(_.tools).getOrElse(Vector.empty)
      }
    )
}

private[textus] final case class AiPurposePolicy(
  maxOutputTokens: Option[Int] = None,
  timeoutSeconds: Option[Long] = None,
  recordRetryLimit: Option[Int] = None,
  maxConcurrent: Option[Int] = None,
  concurrencyScope: Option[ConcurrencyScopeId] = None,
  outputSchemaId: Option[String] = None,
  promptContractId: Option[String] = None
) {
  def requestProperties: Vector[org.goldenport.protocol.Property] =
    timeoutSeconds.map(value => org.goldenport.protocol.Property("ai.timeout-seconds", value.toString, None)).toVector ++
      recordRetryLimit.map(value => org.goldenport.protocol.Property("ai.record.retry-limit", value.toString, None)).toVector

  def validateGenerate(
    properties: Vector[org.goldenport.protocol.Property]
  ): Consequence[Unit] =
    for {
      _ <- _validate_prompt_contract(properties)
      _ <- _reject_structured_output("generate")
    } yield ()

  def validateRecord(
    properties: Vector[org.goldenport.protocol.Property]
  ): Consequence[Unit] =
    for {
      _ <- _validate_prompt_contract(properties)
      _ <- _validate_output_schema(properties)
    } yield ()

  def validateChat(
    properties: Vector[org.goldenport.protocol.Property]
  ): Consequence[Unit] =
    for {
      _ <- _validate_prompt_contract(properties)
      _ <- _reject_structured_output("chat")
    } yield ()

  private def _validate_prompt_contract(
    properties: Vector[org.goldenport.protocol.Property]
  ): Consequence[Unit] =
    _validate_identity(promptContractId, properties, "prompt-contract-id")

  private def _validate_output_schema(
    properties: Vector[org.goldenport.protocol.Property]
  ): Consequence[Unit] =
    _validate_identity(outputSchemaId, properties, "output-schema-id")

  private def _reject_structured_output(
    operation: String
  ): Consequence[Unit] =
    outputSchemaId match {
      case Some(_) =>
        Consequence.configurationInvalid(
          s"AI purpose output-schema-id requires generateRecord, not $operation"
        )
      case None =>
        Consequence.unit
    }

  private def _validate_identity(
    expected: Option[String],
    properties: Vector[org.goldenport.protocol.Property],
    label: String
  ): Consequence[Unit] =
    expected match {
      case Some(value) =>
        AiRequestProperties.string(properties, Vector(
          s"ai.$label",
          s"textus.ai.$label",
          s"cncf.ai.$label"
        )) match {
          case Some(actual) if actual == value => Consequence.unit
          case _ => Consequence.configurationInvalid(s"AI purpose requires $label '$value'")
        }
      case None =>
        Consequence.unit
    }
}

private[textus] final case class AiProfileResolution(
  requirement: AiRunnerRequirement,
  policy: AiPurposePolicy = AiPurposePolicy.empty,
  modelProfile: Option[AiModelProfile] = None,
  genericPurpose: Option[String] = None,
  logicalLevel: Option[String] = None
) {
  def maxTokens(request: Option[Int]): Option[Int] =
    request.orElse(policy.maxOutputTokens)

  def requestProperties(
    properties: Vector[org.goldenport.protocol.Property]
  ): Vector[org.goldenport.protocol.Property] =
    properties ++ policy.requestProperties ++ _codex_execution_profile_property ++ _openai_reasoning_property

  def isCodexProfile: Boolean =
    requirement.provider.exists(_is_codex)

  def controlsOpenAiReasoning: Boolean =
    requirement.provider.exists(_is_openai) && reasoningLevel.nonEmpty

  def codexExecutionProfile: Option[String] =
    Option.when(isCodexProfile)(modelProfile.map(_.name)).flatten

  def reasoningLevel: Option[String] =
    modelProfile.flatMap(_.reasoningLevel)

  def executionMetadata(
    maxTokens: Option[Int],
    properties: Vector[org.goldenport.protocol.Property],
    recordRetryLimit: Option[Int] = None
  ): Map[String, String] = {
    val policyfacts = Vector(
      AiExecutionFacts.POLICY_MAX_OUTPUT_TOKENS -> maxTokens.map(_.toString),
      AiExecutionFacts.POLICY_TIMEOUT_SECONDS -> AiRequestProperties.timeoutSeconds(properties).map(_.toString),
      AiExecutionFacts.POLICY_RECORD_RETRY_LIMIT -> recordRetryLimit.map(_.toString),
      AiExecutionFacts.POLICY_MAX_CONCURRENT -> policy.maxConcurrent.map(_.toString),
      AiExecutionFacts.POLICY_OUTPUT_SCHEMA_ID -> policy.outputSchemaId,
      AiExecutionFacts.POLICY_PROMPT_CONTRACT_ID -> policy.promptContractId,
      AiExecutionFacts.POLICY_MODEL_PROFILE -> codexExecutionProfile,
      AiExecutionFacts.POLICY_REASONING_LEVEL -> reasoningLevel,
      AiExecutionFacts.POLICY_GENERIC_PURPOSE -> genericPurpose,
      AiExecutionFacts.POLICY_LOGICAL_LEVEL -> logicalLevel,
      AiExecutionFacts.ENABLED_TOOLS -> Option.when(codexExecutionProfile.nonEmpty && requirement.tools.nonEmpty)(
        requirement.tools.map(_.id).mkString(",")
      )
    ).collect {
      case (key, Some(value)) if value.trim.nonEmpty => key -> value.trim
    }.toMap
    policyfacts ++ AiExecutionFacts.policySnapshotId(policyfacts).map { value =>
      AiExecutionFacts.POLICY_SNAPSHOT_ID -> value
    }
  }

  private def _codex_execution_profile_property: Vector[org.goldenport.protocol.Property] =
    codexExecutionProfile.map { value =>
      org.goldenport.protocol.Property(AiRequestProperties.CODEX_EXECUTION_PROFILE, value, None)
    }.toVector

  private def _openai_reasoning_property: Vector[org.goldenport.protocol.Property] =
    Option.when(requirement.provider.exists(_is_openai))(reasoningLevel).flatten.map { value =>
      org.goldenport.protocol.Property("ai.openai.reasoning.effort", value, None)
    }.toVector

  private def _is_codex(value: String): Boolean =
    value.trim.equalsIgnoreCase("codex") || value.trim.equalsIgnoreCase("codex-cli")

  private def _is_openai(value: String): Boolean =
    value.trim.equalsIgnoreCase("openai")
}

private[textus] object AiPurposePolicy {
  val empty: AiPurposePolicy = AiPurposePolicy()
}

private[textus] final class AiProfileConfig(
  configuration: Option[ResolvedConfiguration]
) {
  def resolveRequired(
    requirement: AiRunnerRequirement
  ): Consequence[AiProfileResolution] =
    if (requirement.purposeRequired)
      _resolve_required_purpose(requirement)
    else
      _resolve_optional_purpose(requirement)

  def resolvePurpose(
    purpose: String
  ): Option[AiPurposeProfile] =
    val normalized = purpose.trim
    Option.when(normalized.nonEmpty) {
      AiPurposeProfile(
        purpose = normalized,
        basePurpose = _config_string(
          _purpose_keys(normalized, "base-purpose") ++
            _purpose_keys(normalized, "basePurpose")
        ),
        executionClass = _config_string(
          _purpose_keys(normalized, "execution-class") ++
            _purpose_keys(normalized, "executionClass")
        ),
        modelProfile = _config_string(_purpose_keys(normalized, "model-profile") ++ _purpose_keys(normalized, "modelProfile")),
        provider = _config_string(_purpose_keys(normalized, "provider")),
        mode = _config_string(_purpose_keys(normalized, "mode")),
        engine = _config_string(_purpose_keys(normalized, "engine")),
        model = _config_string(_purpose_keys(normalized, "model")),
        role = _config_string(_purpose_keys(normalized, "role")),
        quality = _config_string(_purpose_keys(normalized, "quality")),
        cost = _config_string(_purpose_keys(normalized, "cost")),
        latency = _config_string(_purpose_keys(normalized, "latency")),
        tools = _config_tools(
          _purpose_keys(normalized, "tools") ++
            _purpose_keys(normalized, "enabled-tools") ++
            _purpose_keys(normalized, "enabledTools")
        ),
        maxOutputTokens = _config_string(
          _purpose_keys(normalized, "max-output-tokens") ++
            _purpose_keys(normalized, "maxOutputTokens")
        ),
        timeoutSeconds = _config_string(
          _purpose_keys(normalized, "timeout-seconds") ++
            _purpose_keys(normalized, "timeoutSeconds")
        ),
        recordRetryLimit = _config_string(
          _purpose_keys(normalized, "record-retry-limit") ++
            _purpose_keys(normalized, "recordRetryLimit")
        ),
        maxConcurrent = _config_string(
          _purpose_keys(normalized, "max-concurrent") ++
            _purpose_keys(normalized, "maxConcurrent")
        ),
        outputSchemaId = _config_string(
          _purpose_keys(normalized, "output-schema-id") ++
            _purpose_keys(normalized, "outputSchemaId") ++
            _purpose_keys(normalized, "schema-id") ++
            _purpose_keys(normalized, "schemaId")
        ),
        promptContractId = _config_string(
          _purpose_keys(normalized, "prompt-contract-id") ++
            _purpose_keys(normalized, "promptContractId")
        ),
        unsupportedPromptPolicy = _unsupported_prompt_policy(normalized)
      )
    }.filter(profile =>
      profile.modelProfile.nonEmpty ||
        profile.basePurpose.nonEmpty ||
        profile.executionClass.nonEmpty ||
        profile.provider.nonEmpty ||
        profile.mode.nonEmpty ||
        profile.engine.nonEmpty ||
        profile.model.nonEmpty ||
        profile.role.nonEmpty ||
        profile.quality.nonEmpty ||
        profile.cost.nonEmpty ||
        profile.latency.nonEmpty ||
        profile.tools.nonEmpty ||
        profile.maxOutputTokens.nonEmpty ||
        profile.timeoutSeconds.nonEmpty ||
        profile.recordRetryLimit.nonEmpty ||
        profile.maxConcurrent.nonEmpty ||
        profile.outputSchemaId.nonEmpty ||
        profile.promptContractId.nonEmpty ||
        profile.unsupportedPromptPolicy.nonEmpty
    )

  def resolveGenericPurpose(
    purpose: String
  ): Option[AiPurposeProfile] =
    val normalized = purpose.trim
    Option.when(normalized.nonEmpty) {
      AiPurposeProfile(
        purpose = normalized,
        executionClass = _config_string(
          _generic_purpose_keys(normalized, "execution-class") ++
            _generic_purpose_keys(normalized, "executionClass")
        ),
        level = _config_string(_generic_purpose_keys(normalized, "level")),
        modelProfile = _config_string(_generic_purpose_keys(normalized, "model-profile") ++ _generic_purpose_keys(normalized, "modelProfile")),
        provider = _config_string(_generic_purpose_keys(normalized, "provider")),
        mode = _config_string(_generic_purpose_keys(normalized, "mode")),
        engine = _config_string(_generic_purpose_keys(normalized, "engine")),
        model = _config_string(_generic_purpose_keys(normalized, "model")),
        role = _config_string(_generic_purpose_keys(normalized, "role")),
        quality = _config_string(_generic_purpose_keys(normalized, "quality")),
        cost = _config_string(_generic_purpose_keys(normalized, "cost")),
        latency = _config_string(_generic_purpose_keys(normalized, "latency")),
        tools = _config_tools(_generic_purpose_keys(normalized, "tools")),
        maxOutputTokens = _config_string(_generic_purpose_keys(normalized, "max-output-tokens") ++ _generic_purpose_keys(normalized, "maxOutputTokens")),
        timeoutSeconds = _config_string(_generic_purpose_keys(normalized, "timeout-seconds") ++ _generic_purpose_keys(normalized, "timeoutSeconds")),
        recordRetryLimit = _config_string(_generic_purpose_keys(normalized, "record-retry-limit") ++ _generic_purpose_keys(normalized, "recordRetryLimit")),
        maxConcurrent = _config_string(_generic_purpose_keys(normalized, "max-concurrent") ++ _generic_purpose_keys(normalized, "maxConcurrent")),
        outputSchemaId = _config_string(_generic_purpose_keys(normalized, "output-schema-id") ++ _generic_purpose_keys(normalized, "outputSchemaId")),
        promptContractId = _config_string(_generic_purpose_keys(normalized, "prompt-contract-id") ++ _generic_purpose_keys(normalized, "promptContractId"))
      )
    }.filter { profile =>
      profile.level.nonEmpty ||
        profile.executionClass.nonEmpty ||
        profile.modelProfile.nonEmpty ||
        profile.provider.nonEmpty ||
        profile.mode.nonEmpty ||
        profile.engine.nonEmpty ||
        profile.model.nonEmpty ||
        profile.tools.nonEmpty ||
        profile.maxOutputTokens.nonEmpty ||
        profile.timeoutSeconds.nonEmpty ||
        profile.recordRetryLimit.nonEmpty ||
        profile.maxConcurrent.nonEmpty ||
        profile.outputSchemaId.nonEmpty ||
        profile.promptContractId.nonEmpty
    }

  def resolveModelProfile(
    name: String
  ): Option[AiModelProfile] =
    val normalized = name.trim
    Option.when(normalized.nonEmpty) {
      AiModelProfile(
        name = normalized,
        provider = _config_string(_model_profile_keys(normalized, "provider")),
        mode = _config_string(_model_profile_keys(normalized, "mode")),
        engine = _config_string(_model_profile_keys(normalized, "engine")),
        model = _config_string(_model_profile_keys(normalized, "model")),
        reasoningLevel = _config_string(
          _model_profile_keys(normalized, "reasoning-level") ++
            _model_profile_keys(normalized, "reasoningLevel")
        ),
        tools = _config_tools(
          _model_profile_keys(normalized, "tools") ++
            _model_profile_keys(normalized, "enabled-tools") ++
            _model_profile_keys(normalized, "enabledTools")
        ),
        role = _config_string(_model_profile_keys(normalized, "role")),
        quality = _config_string(_model_profile_keys(normalized, "quality")),
        cost = _config_string(_model_profile_keys(normalized, "cost")),
        latency = _config_string(_model_profile_keys(normalized, "latency")),
        description = _config_string(_model_profile_keys(normalized, "description"))
      )
    }.filter(profile =>
      profile.provider.nonEmpty ||
        profile.mode.nonEmpty ||
        profile.engine.nonEmpty ||
        profile.model.nonEmpty ||
        profile.reasoningLevel.nonEmpty ||
        profile.tools.nonEmpty ||
        profile.role.nonEmpty ||
        profile.quality.nonEmpty ||
        profile.cost.nonEmpty ||
        profile.latency.nonEmpty ||
        profile.description.nonEmpty
    )

  private def _resolve_required_purpose(
    requirement: AiRunnerRequirement
  ): Consequence[AiProfileResolution] =
    requirement.purpose.map(_.trim).filter(_.nonEmpty) match {
      case Some(purpose) =>
        _resolve_named_purpose(requirement.copy(purpose = Some(purpose)), purpose, true)
      case None =>
        Consequence.configurationInvalid("AI purpose is required")
    }

  private def _resolve_optional_purpose(
    requirement: AiRunnerRequirement
  ): Consequence[AiProfileResolution] =
    requirement.purpose.map(_.trim).filter(_.nonEmpty) match {
      case Some(purpose) =>
        if (
          resolvePurpose(purpose).nonEmpty ||
          resolveGenericPurpose(purpose).nonEmpty ||
          _is_configured_logical_level(purpose)
        )
          _resolve_named_purpose(requirement.copy(purpose = Some(purpose)), purpose, false)
        else if (requirement.executionClass.nonEmpty)
          Consequence.configurationInvalid(s"AI purpose profile not configured: $purpose")
        else
          Consequence.success(AiProfileResolution(requirement))
      case None =>
        requirement.executionClass match {
          case Some(executionclass) => _resolve_execution_class_only(requirement, executionclass)
          case None => Consequence.success(AiProfileResolution(requirement))
        }
    }

  private def _resolve_named_purpose(
    requirement: AiRunnerRequirement,
    purpose: String,
    requiresprovider: Boolean
  ): Consequence[AiProfileResolution] =
    resolvePurpose(purpose) match {
      case Some(profile) =>
        profile.basePurpose match {
          case Some(base) => _resolve_inherited_purpose(requirement, profile, base, requiresprovider)
          case None => _resolve_profile(requirement, profile, requiresprovider)
        }
      case None =>
        resolveGenericPurpose(purpose) match {
          case Some(profile) =>
            _materialize_generic_execution_class(requirement, profile).flatMap { materialized =>
              _resolve_profile(
                requirement,
                materialized,
                requiresprovider,
                Some(_ProfileOrigin(Some(profile.purpose), profile.level))
              )
            }
          case None =>
            _resolve_implicit_level_purpose(requirement, purpose, requiresprovider)
        }
    }

  private def _resolve_implicit_level_purpose(
    requirement: AiRunnerRequirement,
    purpose: String,
    requiresprovider: Boolean
  ): Consequence[AiProfileResolution] =
    if (_is_configured_logical_level(purpose)) {
      val profile = AiPurposeProfile(purpose = purpose, level = Some(purpose))
      _materialize_execution_class(requirement, profile).flatMap { materialized =>
        _resolve_profile(
          requirement,
          materialized,
          requiresprovider,
          Some(_ProfileOrigin(None, Some(purpose)))
        )
      }
    } else {
      Consequence.configurationInvalid(s"AI purpose profile not configured: $purpose")
    }

  private def _is_configured_logical_level(level: String): Boolean =
    _config_string(_level_keys(level, "model-profile") ++ _level_keys(level, "modelProfile")).nonEmpty

  private def _resolve_inherited_purpose(
    requirement: AiRunnerRequirement,
    application: AiPurposeProfile,
    basepurpose: String,
    requiresprovider: Boolean
  ): Consequence[AiProfileResolution] =
    resolveGenericPurpose(basepurpose) match {
      case Some(base) =>
        for {
          materializedbase <- _materialize_generic_execution_class(requirement, base)
          merged = _inherit_purpose(materializedbase, application)
          baseresolution <- _resolve_profile(
            AiRunnerRequirement(
              purpose = Some(basepurpose),
              executionClass = requirement.executionClass
            ),
            materializedbase,
            true,
            Some(_ProfileOrigin(Some(basepurpose), base.level))
          )
          applicationresolution <- _resolve_profile(
            requirement,
            merged,
            requiresprovider,
            Some(_ProfileOrigin(Some(basepurpose), base.level))
          )
          _ <- _validate_narrowing(baseresolution, applicationresolution)
        } yield applicationresolution
      case None =>
        Consequence.configurationInvalid(s"AI generic purpose profile not configured: $basepurpose")
    }

  private def _resolve_execution_class_only(
    requirement: AiRunnerRequirement,
    executionclass: AiExecutionClass
  ): Consequence[AiProfileResolution] =
    _resolve_profile(
      requirement,
      AiPurposeProfile(
        purpose = s"execution-class:${executionclass.id}",
        executionClass = Some(executionclass.id)
      ),
      false
    )

  private def _materialize_execution_class(
    requirement: AiRunnerRequirement,
    profile: AiPurposeProfile
  ): Consequence[AiPurposeProfile] =
    for {
      configured <- _configured_execution_class(profile)
      effective <- _effective_execution_class(requirement, profile.purpose, configured)
      materialized <- effective match {
        case Some(executionclass) =>
          _execution_class_model_profile(executionclass).flatMap {
            case Some(modelprofile) if profile.modelProfile.forall(_ == modelprofile) =>
              Consequence.success(profile.copy(
                executionClass = Some(executionclass.id),
                level = None,
                modelProfile = Some(modelprofile)
              ))
            case Some(_) =>
              Consequence.configurationInvalid(
                s"AI execution class model-profile conflicts with purpose: ${profile.purpose}"
              )
            case None =>
              Consequence.configurationInvalid(
                s"AI execution class not configured: ${executionclass.id}"
              )
          }
        case None =>
          Consequence.success(profile)
      }
    } yield materialized

  private def _materialize_generic_execution_class(
    requirement: AiRunnerRequirement,
    profile: AiPurposeProfile
  ): Consequence[AiPurposeProfile] =
    if (profile.executionClass.nonEmpty || profile.level.nonEmpty)
      _materialize_execution_class(requirement, profile)
    else
      Consequence.configurationInvalid(
        s"AI generic purpose requires an execution-class: ${profile.purpose}"
      )

  private def _configured_execution_class(
    profile: AiPurposeProfile
  ): Consequence[Option[AiExecutionClass]] =
    (profile.executionClass, profile.level) match {
      case (Some(executionclass), Some(level)) =>
        for {
          primary <- _parse_execution_class(executionclass, profile.purpose)
          legacy <- _parse_execution_class(level, profile.purpose)
          _ <- if (primary == legacy)
            Consequence.unit
          else
            Consequence.configurationInvalid(
              s"AI purpose execution-class conflicts with legacy level: ${profile.purpose}"
            )
        } yield Some(primary)
      case (Some(executionclass), None) =>
        _parse_execution_class(executionclass, profile.purpose).map(Some(_))
      case (None, Some(level)) =>
        _parse_execution_class(level, profile.purpose).map(Some(_))
      case (None, None) =>
        Consequence.success(None)
    }

  private def _effective_execution_class(
    requirement: AiRunnerRequirement,
    purpose: String,
    configured: Option[AiExecutionClass]
  ): Consequence[Option[AiExecutionClass]] =
    (requirement.executionClass, configured) match {
      case (Some(requested), Some(expected)) if requested != expected =>
        Consequence.configurationInvalid(
          s"AI purpose execution-class is incompatible: $purpose requires ${expected.id}, got ${requested.id}"
        )
      case (Some(requested), _) =>
        Consequence.success(Some(requested))
      case (None, Some(expected)) =>
        Consequence.success(Some(expected))
      case (None, None) =>
        Consequence.success(None)
    }

  private def _parse_execution_class(
    value: String,
    purpose: String
  ): Consequence[AiExecutionClass] =
    AiExecutionClass.parse(value) match {
      case Some(executionclass) => Consequence.success(executionclass)
      case None => Consequence.configurationInvalid(
        s"Invalid AI execution-class '$value' for purpose: $purpose"
      )
    }

  private def _execution_class_model_profile(
    executionclass: AiExecutionClass
  ): Consequence[Option[String]] =
    Consequence.success(_config_string(
      _execution_class_keys(executionclass.id, "model-profile") ++
        _execution_class_keys(executionclass.id, "modelProfile") ++
        _level_keys(executionclass.id, "model-profile") ++
        _level_keys(executionclass.id, "modelProfile")
    ))

  private def _inherit_purpose(
    base: AiPurposeProfile,
    application: AiPurposeProfile
  ): AiPurposeProfile =
    application.copy(
      basePurpose = None,
      executionClass = application.executionClass.orElse(base.executionClass),
      level = None,
      modelProfile = application.modelProfile.orElse(base.modelProfile),
      provider = application.provider.orElse(base.provider),
      mode = application.mode.orElse(base.mode),
      engine = application.engine.orElse(base.engine),
      model = application.model.orElse(base.model),
      role = application.role.orElse(base.role),
      quality = application.quality.orElse(base.quality),
      cost = application.cost.orElse(base.cost),
      latency = application.latency.orElse(base.latency),
      tools = if (application.tools.nonEmpty) application.tools else base.tools,
      maxOutputTokens = application.maxOutputTokens.orElse(base.maxOutputTokens),
      timeoutSeconds = application.timeoutSeconds.orElse(base.timeoutSeconds),
      recordRetryLimit = application.recordRetryLimit.orElse(base.recordRetryLimit),
      maxConcurrent = application.maxConcurrent.orElse(base.maxConcurrent),
      outputSchemaId = application.outputSchemaId.orElse(base.outputSchemaId),
      promptContractId = application.promptContractId.orElse(base.promptContractId),
      unsupportedPromptPolicy = application.unsupportedPromptPolicy.orElse(base.unsupportedPromptPolicy)
    )

  private def _resolve_profile(
    requirement: AiRunnerRequirement,
    profile: AiPurposeProfile,
    requiresprovider: Boolean,
    origin: Option[_ProfileOrigin] = None
  ): Consequence[AiProfileResolution] =
    for {
      materialized <- _materialize_execution_class(requirement, profile)
      modelprofile <- _resolve_model_profile(materialized)
      policy <- _purpose_policy(materialized)
      resolution <- {
        val profileprovider = materialized.provider.orElse(modelprofile.flatMap(_.provider))
        val effective = materialized.applyTo(requirement, modelprofile)
        if (requiresprovider && profileprovider.isEmpty)
          Consequence.configurationInvalid(
            s"AI purpose profile must select a provider: ${materialized.purpose}"
          )
        else
          _validate_codex_profile(requirement, effective, modelprofile).map { _ =>
            AiProfileResolution(
              effective,
              policy,
              modelprofile,
              origin.flatMap(_.genericPurpose),
              origin.flatMap(_.logicalLevel)
            )
          }
      }
    } yield resolution

  private def _validate_narrowing(
    base: AiProfileResolution,
    application: AiProfileResolution
  ): Consequence[Unit] =
    if (!_same_selection(base.requirement, application.requirement))
      Consequence.configurationInvalid("AI application purpose may not broaden an inherited provider/model policy")
    else if (!_same_model_profile(base.modelProfile, application.modelProfile))
      Consequence.configurationInvalid("AI application purpose may not replace an inherited model-profile")
    else if (!application.requirement.tools.toSet.subsetOf(base.requirement.tools.toSet))
      Consequence.configurationInvalid("AI application purpose may not broaden inherited tools")
    else if (!_is_narrower_policy(base.policy, application.policy))
      Consequence.configurationInvalid("AI application purpose may not broaden inherited execution policy")
    else
      Consequence.unit

  private def _same_selection(
    base: AiRunnerRequirement,
    application: AiRunnerRequirement
  ): Boolean = {
    val selectionmatches = Vector(
      base.provider -> application.provider,
      base.mode -> application.mode,
      base.engine -> application.engine,
      base.model -> application.model
    ).forall { case (left, right) => left.map(_.trim.toLowerCase(java.util.Locale.ROOT)) == right.map(_.trim.toLowerCase(java.util.Locale.ROOT)) }
    selectionmatches && base.executionClass == application.executionClass
  }

  private def _same_model_profile(
    base: Option[AiModelProfile],
    application: Option[AiModelProfile]
  ): Boolean =
    base.map(_.name) == application.map(_.name)

  private def _is_narrower_policy(
    base: AiPurposePolicy,
    application: AiPurposePolicy
  ): Boolean =
    _at_most(application.maxOutputTokens, base.maxOutputTokens) &&
      _at_most(application.timeoutSeconds, base.timeoutSeconds) &&
      _at_most(application.recordRetryLimit, base.recordRetryLimit) &&
      _at_most(application.maxConcurrent, base.maxConcurrent) &&
      _same_or_added(application.outputSchemaId, base.outputSchemaId) &&
      _same_or_added(application.promptContractId, base.promptContractId)

  private def _at_most[A: Ordering](application: Option[A], base: Option[A]): Boolean =
    (application, base) match {
      case (_, None) => true
      case (Some(value), Some(limit)) => summon[Ordering[A]].lteq(value, limit)
      case (None, Some(_)) => false
    }

  private def _same_or_added(application: Option[String], base: Option[String]): Boolean =
    base.forall(value => application.contains(value))

  private final case class _ProfileOrigin(
    genericPurpose: Option[String],
    logicalLevel: Option[String]
  )

  private def _resolve_model_profile(
    profile: AiPurposeProfile
  ): Consequence[Option[AiModelProfile]] =
    profile.modelProfile match {
      case Some(name) =>
        resolveModelProfile(name) match {
          case Some(modelprofile) => Consequence.success(Some(modelprofile))
          case None => Consequence.configurationInvalid(s"AI model profile not configured: $name")
        }
      case None =>
        Consequence.success(None)
    }

  private def _validate_codex_profile(
    requested: AiRunnerRequirement,
    effective: AiRunnerRequirement,
    modelprofile: Option[AiModelProfile]
  ): Consequence[Unit] =
    if (!_is_codex(effective.provider))
      Consequence.unit
    else if (requested.model.nonEmpty)
      Consequence.configurationInvalid(
        "Codex model selection must come from an approved purpose model-profile"
      )
    else {
      val tools = effective.tools.toSet
      modelprofile match {
        case Some(profile) if profile.reasoningLevel.exists(value => !_is_codex_reasoning_level(value)) =>
          Consequence.configurationInvalid(
            s"Codex model-profile '${profile.name}' has an unsupported reasoning-level"
          )
        case Some(profile) if !tools.subsetOf(profile.tools.toSet) =>
          Consequence.configurationInvalid(
            s"Codex purpose requests tools outside model-profile '${profile.name}'"
          )
        case None if tools.nonEmpty =>
          Consequence.configurationInvalid(
            "Codex tools require an approved purpose model-profile"
          )
        case _ if tools.contains(AiTool.UrlContext) && !tools.contains(AiTool.WebSearch) =>
          Consequence.configurationInvalid(
            "Codex URL context requires the web_search capability"
          )
        case _ =>
          Consequence.unit
      }
    }

  private def _is_codex(provider: Option[String]): Boolean =
    provider.exists { value =>
      value.trim.equalsIgnoreCase("codex") || value.trim.equalsIgnoreCase("codex-cli")
    }

  private def _is_codex_reasoning_level(value: String): Boolean =
    Set("minimal", "low", "medium", "high", "xhigh").contains(
      value.trim.toLowerCase(java.util.Locale.ROOT)
    )

  private def _purpose_policy(
    profile: AiPurposeProfile
  ): Consequence[AiPurposePolicy] =
    for {
      maxtokens <- _positive_int(profile.maxOutputTokens, "max-output-tokens", profile.purpose)
      timeoutseconds <- _positive_long(profile.timeoutSeconds, "timeout-seconds", profile.purpose)
      retrylimit <- _bounded_int(profile.recordRetryLimit, "record-retry-limit", profile.purpose, 0, 3)
      maxconcurrent <- _positive_int(profile.maxConcurrent, "max-concurrent", profile.purpose)
      concurrencyscope <- _concurrency_scope_c(profile.purpose, maxconcurrent)
      outputschemaid <- _policy_id(profile.outputSchemaId, "output-schema-id", profile.purpose)
      promptcontractid <- _policy_id(profile.promptContractId, "prompt-contract-id", profile.purpose)
      _ <- _validate_prompt_policy(profile)
    } yield AiPurposePolicy(
      maxOutputTokens = maxtokens,
      timeoutSeconds = timeoutseconds,
      recordRetryLimit = retrylimit,
      maxConcurrent = maxconcurrent,
      concurrencyScope = concurrencyscope,
      outputSchemaId = outputschemaid,
      promptContractId = promptcontractid
    )

  private def _concurrency_scope_c(
    purpose: String,
    maxconcurrent: Option[Int]
  ): Consequence[Option[ConcurrencyScopeId]] =
    maxconcurrent match {
      case Some(_) =>
        ConcurrencyScopeId.parseC(purpose).map(Some(_))
      case None =>
        Consequence.success(None)
    }

  private def _positive_int(
    value: Option[String],
    label: String,
    purpose: String
  ): Consequence[Option[Int]] =
    value match {
      case Some(raw) =>
        raw.toIntOption.filter(_ > 0) match {
          case Some(parsed) => Consequence.success(Some(parsed))
          case None => Consequence.configurationInvalid(s"Invalid AI purpose $label for $purpose")
        }
      case None => Consequence.success(None)
    }

  private def _positive_long(
    value: Option[String],
    label: String,
    purpose: String
  ): Consequence[Option[Long]] =
    value match {
      case Some(raw) =>
        raw.toLongOption.filter(_ > 0) match {
          case Some(parsed) => Consequence.success(Some(parsed))
          case None => Consequence.configurationInvalid(s"Invalid AI purpose $label for $purpose")
        }
      case None => Consequence.success(None)
    }

  private def _bounded_int(
    value: Option[String],
    label: String,
    purpose: String,
    minimum: Int,
    maximum: Int
  ): Consequence[Option[Int]] =
    value match {
      case Some(raw) =>
        raw.toIntOption.filter(value => value >= minimum && value <= maximum) match {
          case Some(parsed) => Consequence.success(Some(parsed))
          case None => Consequence.configurationInvalid(s"Invalid AI purpose $label for $purpose")
        }
      case None => Consequence.success(None)
    }

  private def _policy_id(
    value: Option[String],
    label: String,
    purpose: String
  ): Consequence[Option[String]] =
    value match {
      case Some(raw) if raw.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") =>
        Consequence.success(Some(raw))
      case Some(_) =>
        Consequence.configurationInvalid(s"Invalid AI purpose $label for $purpose")
      case None =>
        Consequence.success(None)
    }

  private def _validate_prompt_policy(
    profile: AiPurposeProfile
  ): Consequence[Unit] =
    profile.unsupportedPromptPolicy match {
      case Some(label) =>
        Consequence.configurationInvalid(
          s"AI purpose $label is not supported; configure prompt-contract-id instead: ${profile.purpose}"
        )
      case None =>
        Consequence.unit
    }

  private def _unsupported_prompt_policy(
    purpose: String
  ): Option[String] =
    Vector("prompt", "system-instruction", "source-restrictions", "output-constraints")
      .find(label => _config_string(_purpose_keys(purpose, label)).nonEmpty)

  private def _purpose_keys(
    purpose: String,
    leaf: String
  ): Vector[String] =
    Vector(
      s"textus.ai.purposes.$purpose.$leaf",
      s"textus.ai.purpose.$purpose.$leaf",
      s"textus.runtime.ai.purposes.$purpose.$leaf",
      s"cncf.ai.purposes.$purpose.$leaf",
      s"cncf.runtime.ai.purposes.$purpose.$leaf"
    )

  private def _generic_purpose_keys(
    purpose: String,
    leaf: String
  ): Vector[String] =
    Vector(
      s"textus.ai.generic-purposes.$purpose.$leaf",
      s"textus.ai.genericPurposes.$purpose.$leaf",
      s"textus.runtime.ai.generic-purposes.$purpose.$leaf",
      s"cncf.ai.generic-purposes.$purpose.$leaf",
      s"cncf.runtime.ai.generic-purposes.$purpose.$leaf"
    )

  private def _level_keys(
    level: String,
    leaf: String
  ): Vector[String] =
    Vector(
      s"textus.ai.levels.$level.$leaf",
      s"textus.ai.logical-levels.$level.$leaf",
      s"textus.runtime.ai.levels.$level.$leaf",
      s"cncf.ai.levels.$level.$leaf",
      s"cncf.runtime.ai.levels.$level.$leaf"
    )

  private def _execution_class_keys(
    executionclass: String,
    leaf: String
  ): Vector[String] =
    Vector(
      s"textus.ai.execution-classes.$executionclass.$leaf",
      s"textus.ai.executionClasses.$executionclass.$leaf",
      s"textus.runtime.ai.execution-classes.$executionclass.$leaf",
      s"cncf.ai.execution-classes.$executionclass.$leaf",
      s"cncf.runtime.ai.execution-classes.$executionclass.$leaf"
    )

  private def _model_profile_keys(
    profile: String,
    leaf: String
  ): Vector[String] =
    Vector(
      s"textus.ai.model-profiles.$profile.$leaf",
      s"textus.ai.modelProfiles.$profile.$leaf",
      s"textus.ai.model-profile.$profile.$leaf",
      s"textus.runtime.ai.model-profiles.$profile.$leaf",
      s"cncf.ai.model-profiles.$profile.$leaf",
      s"cncf.runtime.ai.model-profiles.$profile.$leaf"
    )

  private def _config_string(
    keys: Vector[String]
  ): Option[String] =
    configuration.flatMap { resolved =>
      keys.iterator
        .flatMap(key => Try(RuntimeConfig.getString(resolved, key)).toOption.flatten)
        .find(_.trim.nonEmpty)
        .map(_.trim)
    }

  private def _config_tools(
    keys: Vector[String]
  ): Vector[AiTool] =
    _config_string(keys).map(AiTool.parseList).getOrElse(Vector.empty)

  def concurrencyAdmissionC: Consequence[Option[ScopedConcurrencyAdmission]] =
    _concurrency_grants_c.flatMap { grants =>
      if (grants.nonEmpty)
        ScopedConcurrencyAdmission.createC(grants).map(Some(_))
      else
        Consequence.success(None)
    }

  private def _concurrency_grants_c: Consequence[Vector[ConcurrencyGrant]] =
    _concurrency_purposes.foldLeft(Consequence.success(Vector.empty[ConcurrencyGrant])) { (z, purpose) =>
      z.flatMap { grants =>
        _resolve_named_purpose(
          AiRunnerRequirement(purpose = Some(purpose), purposeRequired = true),
          purpose,
          true
        ).map { resolution =>
          (resolution.policy.concurrencyScope, resolution.policy.maxConcurrent) match {
            case (Some(scope), Some(limit)) => grants :+ ConcurrencyGrant(scope, limit)
            case _ => grants
          }
        }
      }
    }

  private def _concurrency_purposes: Vector[String] =
    configuration.toVector
      .flatMap(_.configuration.values.keys)
      .flatMap(_concurrency_purpose)
      .distinct
      .sorted

  private def _concurrency_purpose(key: String): Option[String] =
    _purpose_prefixes.iterator.flatMap { prefix =>
      _concurrency_suffixes.iterator.flatMap { suffix =>
        Option.when(key.startsWith(prefix) && key.endsWith(suffix))(
          key.drop(prefix.length).dropRight(suffix.length)
        )
      }
    }.map(_.trim).find(_.nonEmpty)

  private val _purpose_prefixes = Vector(
    "textus.ai.purposes.",
    "textus.ai.purpose.",
    "textus.runtime.ai.purposes.",
    "cncf.ai.purposes.",
    "cncf.runtime.ai.purposes.",
    "textus.ai.generic-purposes.",
    "textus.ai.genericPurposes.",
    "textus.runtime.ai.generic-purposes.",
    "cncf.ai.generic-purposes.",
    "cncf.runtime.ai.generic-purposes."
  )

  private val _concurrency_suffixes = Vector(".max-concurrent", ".maxConcurrent")
}

private[textus] object AiProfileConfig {
  val empty: AiProfileConfig = new AiProfileConfig(None)

  def fromConfiguration(
    configuration: Option[ResolvedConfiguration]
  ): AiProfileConfig =
    new AiProfileConfig(configuration)
}
