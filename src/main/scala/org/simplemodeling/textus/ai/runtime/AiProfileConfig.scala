package org.simplemodeling.textus.ai.runtime

import scala.util.Try
import org.goldenport.Consequence
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
  tools: Vector[AiTool] = Vector.empty
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

private[textus] final class AiProfileConfig(
  configuration: Option[ResolvedConfiguration]
) {
  def resolve(
    requirement: AiRunnerRequirement
  ): AiRunnerRequirement =
    requirement.purpose.map(resolvePurpose).getOrElse(None) match {
      case Some(purposeprofile) =>
        purposeprofile.applyTo(requirement, purposeprofile.modelProfile.flatMap(resolveModelProfile))
      case None =>
        requirement
    }

  def resolveRequired(
    requirement: AiRunnerRequirement
  ): Consequence[AiRunnerRequirement] =
    if (requirement.purposeRequired)
      _resolve_required_purpose(requirement)
    else
      Consequence.success(resolve(requirement))

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
        )
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
        profile.tools.nonEmpty
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
  ): Consequence[AiRunnerRequirement] =
    requirement.purpose.map(_.trim).filter(_.nonEmpty) match {
      case Some(purpose) =>
        resolvePurpose(purpose) match {
          case Some(profile) =>
            val modelprofile = profile.modelProfile.flatMap(resolveModelProfile)
            val profileprovider = profile.provider.orElse(modelprofile.flatMap(_.provider))
            profileprovider match {
              case Some(_) =>
                Consequence.success(
                  profile.applyTo(requirement.copy(purpose = Some(purpose)), modelprofile)
                )
              case None =>
                Consequence.configurationInvalid(
                  s"AI purpose profile must select a provider: $purpose"
                )
            }
          case None =>
            Consequence.configurationInvalid(s"AI purpose profile not configured: $purpose")
        }
      case None =>
        Consequence.configurationInvalid("AI purpose is required")
    }

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
}

private[textus] object AiProfileConfig {
  val empty: AiProfileConfig = new AiProfileConfig(None)

  def fromConfiguration(
    configuration: Option[ResolvedConfiguration]
  ): AiProfileConfig =
    new AiProfileConfig(configuration)
}
