package org.simplemodeling.textus.ai.runtime

import scala.util.Try
import org.goldenport.Consequence
import org.goldenport.cncf.admission.{ConcurrencyGrant, ConcurrencyScopeId, ScopedConcurrencyAdmission}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.spi.ai.runner.{AiRunnerRequirement, AiTool}
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
 * - textus.ai.model-profiles.<profile>.role
 * - textus.ai.model-profiles.<profile>.quality
 * - textus.ai.model-profiles.<profile>.cost
 * - textus.ai.model-profiles.<profile>.latency
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
  role: Option[String] = None,
  quality: Option[String] = None,
  cost: Option[String] = None,
  latency: Option[String] = None,
  description: Option[String] = None
)

private[textus] final case class AiPurposeProfile(
  purpose: String,
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
      tools = if (requirement.tools.nonEmpty) requirement.tools else tools
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
  policy: AiPurposePolicy = AiPurposePolicy.empty
) {
  def maxTokens(request: Option[Int]): Option[Int] =
    request.orElse(policy.maxOutputTokens)

  def requestProperties(
    properties: Vector[org.goldenport.protocol.Property]
  ): Vector[org.goldenport.protocol.Property] =
    properties ++ policy.requestProperties

  def executionMetadata(
    maxTokens: Option[Int],
    properties: Vector[org.goldenport.protocol.Property],
    recordRetryLimit: Option[Int] = None
  ): Map[String, String] =
    Vector(
      AiExecutionFacts.POLICY_MAX_OUTPUT_TOKENS -> maxTokens.map(_.toString),
      AiExecutionFacts.POLICY_TIMEOUT_SECONDS -> AiRequestProperties.timeoutSeconds(properties).map(_.toString),
      AiExecutionFacts.POLICY_RECORD_RETRY_LIMIT -> recordRetryLimit.map(_.toString),
      AiExecutionFacts.POLICY_MAX_CONCURRENT -> policy.maxConcurrent.map(_.toString),
      AiExecutionFacts.POLICY_OUTPUT_SCHEMA_ID -> policy.outputSchemaId,
      AiExecutionFacts.POLICY_PROMPT_CONTRACT_ID -> policy.promptContractId
    ).collect {
      case (key, Some(value)) if value.trim.nonEmpty => key -> value.trim
    }.toMap
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
        resolvePurpose(purpose) match {
          case Some(profile) =>
            _resolve_profile(requirement.copy(purpose = Some(purpose)), profile, true)
          case None =>
            Consequence.configurationInvalid(s"AI purpose profile not configured: $purpose")
        }
      case None =>
        Consequence.configurationInvalid("AI purpose is required")
    }

  private def _resolve_optional_purpose(
    requirement: AiRunnerRequirement
  ): Consequence[AiProfileResolution] =
    requirement.purpose.map(_.trim).filter(_.nonEmpty).flatMap(resolvePurpose) match {
      case Some(profile) =>
        _resolve_profile(requirement.copy(purpose = Some(profile.purpose)), profile, false)
      case None =>
        Consequence.success(AiProfileResolution(requirement))
    }

  private def _resolve_profile(
    requirement: AiRunnerRequirement,
    profile: AiPurposeProfile,
    requiresprovider: Boolean
  ): Consequence[AiProfileResolution] =
    for {
      modelprofile <- _resolve_model_profile(profile)
      policy <- _purpose_policy(profile)
      resolution <- {
        val profileprovider = profile.provider.orElse(modelprofile.flatMap(_.provider))
        if (requiresprovider && profileprovider.isEmpty)
          Consequence.configurationInvalid(
            s"AI purpose profile must select a provider: ${profile.purpose}"
          )
        else
          Consequence.success(AiProfileResolution(profile.applyTo(requirement, modelprofile), policy))
      }
    } yield resolution

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
        resolvePurpose(purpose) match {
          case Some(profile) =>
            _purpose_policy(profile).map { policy =>
              (policy.concurrencyScope, policy.maxConcurrent) match {
                case (Some(scope), Some(limit)) => grants :+ ConcurrencyGrant(scope, limit)
                case _ => grants
              }
            }
          case None =>
            Consequence.success(grants)
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
    "cncf.runtime.ai.purposes."
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
