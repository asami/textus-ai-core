package org.simplemodeling.textus.ai.runtime

import scala.util.Try
import org.goldenport.Consequence
import org.goldenport.cncf.admission.{ConcurrencyGrant, ConcurrencyScopeId, ScopedConcurrencyAdmission}
import org.goldenport.cncf.config.RuntimeConfig
import org.goldenport.cncf.spi.ai.runner.{AiExecutionClass, AiRunnerApplicationPurpose, AiRunnerApplicationPurposePolicy, AiRunnerApplicationPurposeRegistration, AiRunnerApplicationPurposeRegistrationSocketSet, AiRunnerRequirement, AiTool}
import org.goldenport.configuration.ResolvedConfiguration

/*
 * Runtime profile and application-purpose resolution for Textus AI.
 *
 * Applications select a purpose only. Textus AI resolves it through the
 * selected runtime profile, its standard execution class, and any narrowing
 * application-purpose policy. Provider, model, and tool selection are runtime
 * owned and cannot be supplied by application callers.
 *
 * Canonical configuration:
 * - textus.ai.profile
 * - textus.ai.execution-classes.<execution-class>.*
 * - textus.ai.application-purposes.<application-purpose>.<policy>
 *
 * @since   Jul.  4, 2026
 * @version Jul. 19, 2026
 * @author  ASAMI, Tomoharu
 */
/**
 * A concrete, runtime-owned binding for one standard execution class.
 *
 * Applications never construct this value. The selected runtime profile and
 * merged CNCF configuration determine the provider-facing selection.
 */
private[textus] final case class AiRuntimeExecution(
  executionClass: AiExecutionClass,
  provider: String,
  mode: String,
  engine: String,
  model: String,
  reasoningLevel: Option[String] = None,
  tools: Vector[AiTool] = Vector.empty
) {
  def codexExecutionProfile: String = s"runtime-${executionClass.id}"
}

private[textus] final case class AiRuntimeProfile(
  name: String,
  executions: Map[AiExecutionClass, AiRuntimeExecution]
) {
  def execution(executionclass: AiExecutionClass): Option[AiRuntimeExecution] =
    executions.get(executionclass)
}

/** Textus AI's shipped runtime profiles. */
private[textus] object AiRuntimeProfileCatalog {
  private def _codex(
    executionclass: AiExecutionClass,
    reasoning: String
  ): AiRuntimeExecution =
    AiRuntimeExecution(
      executionclass,
      provider = "codex",
      mode = "local",
      engine = "codex-cli",
      model = "gpt-5-codex",
      reasoningLevel = Some(reasoning)
    )

  private def _gemini(
    executionclass: AiExecutionClass,
    model: String
  ): AiRuntimeExecution =
    AiRuntimeExecution(
      executionclass,
      provider = "google",
      mode = "remote",
      engine = "gemini",
      model = model
    )

  val codexCli: AiRuntimeProfile = AiRuntimeProfile(
    "codex-cli",
    Map(
      AiExecutionClass.SimpleWork -> _codex(AiExecutionClass.SimpleWork, "minimal"),
      AiExecutionClass.StandardWork -> _codex(AiExecutionClass.StandardWork, "low"),
      AiExecutionClass.StandardConsideration -> _codex(AiExecutionClass.StandardConsideration, "high"),
      AiExecutionClass.DeepConsideration -> _codex(AiExecutionClass.DeepConsideration, "xhigh")
    )
  )

  val gemini: AiRuntimeProfile = AiRuntimeProfile(
    "gemini",
    Map(
      AiExecutionClass.SimpleWork -> _gemini(AiExecutionClass.SimpleWork, "gemini-2.5-flash"),
      AiExecutionClass.StandardWork -> _gemini(AiExecutionClass.StandardWork, "gemini-2.5-flash"),
      AiExecutionClass.StandardConsideration -> _gemini(AiExecutionClass.StandardConsideration, "gemini-2.5-pro"),
      AiExecutionClass.DeepConsideration -> _gemini(AiExecutionClass.DeepConsideration, "gemini-2.5-pro")
    )
  )

  val profiles: Map[String, AiRuntimeProfile] = Vector(codexCli, gemini).map { profile =>
    profile.name -> profile
  }.toMap

  private val _standard_purposes: Map[String, AiExecutionClass] = Map(
    "software-analysis" -> AiExecutionClass.StandardConsideration,
    "software-design" -> AiExecutionClass.DeepConsideration,
    "software-implementation" -> AiExecutionClass.StandardWork,
    "command-execution" -> AiExecutionClass.SimpleWork,
    "web-analysis" -> AiExecutionClass.DeepConsideration,
    "structured-extraction" -> AiExecutionClass.StandardWork
  )

  private val _execution_class_purposes: Map[String, AiExecutionClass] =
    AiExecutionClass.values.map(value => value.id -> value).toMap

  val standardPurposes: Map[String, AiExecutionClass] =
    _standard_purposes ++ _execution_class_purposes

  def profile(name: String): Option[AiRuntimeProfile] =
    profiles.get(name.trim.toLowerCase(java.util.Locale.ROOT))

  def standardPurpose(name: String): Option[AiExecutionClass] =
    standardPurposes.get(name.trim.toLowerCase(java.util.Locale.ROOT))

  def implicitExecutionClass(name: String): Option[AiExecutionClass] =
    AiExecutionClass.parse(name.trim)
}

private[textus] final case class AiPurposeProfile(
  purpose: String,
  executionClass: Option[String] = None,
  provider: Option[String] = None,
  mode: Option[String] = None,
  engine: Option[String] = None,
  model: Option[String] = None,
  tools: Vector[AiTool] = Vector.empty,
  maxInputTokens: Option[String] = None,
  maxOutputTokens: Option[String] = None,
  maxReasoningTokens: Option[String] = None,
  maxCostMicrounits: Option[String] = None,
  rateSchedule: Option[String] = None,
  timeoutSeconds: Option[String] = None,
  recordRetryLimit: Option[String] = None,
  maxConcurrent: Option[String] = None,
  outputSchemaId: Option[String] = None,
  promptContractId: Option[String] = None
) {
  def applyTo(requirement: AiRunnerRequirement): AiRunnerRequirement =
    requirement.copy(
      provider = requirement.provider.orElse(provider),
      mode = requirement.mode.orElse(mode),
      engine = requirement.engine.orElse(engine),
      model = requirement.model.orElse(model),
      executionClass = requirement.executionClass.orElse(executionClass.flatMap(AiExecutionClass.parse)),
      tools = if (requirement.tools.nonEmpty) requirement.tools else tools
    )
}

private[textus] object AiPurposeProfile {
  def fromRegistration(
    purpose: String,
    policy: AiRunnerApplicationPurposePolicy
  ): AiPurposeProfile =
    AiPurposeProfile(
      purpose = purpose,
      maxInputTokens = policy.maxInputTokens.map(_.toString),
      maxOutputTokens = policy.maxOutputTokens.map(_.toString),
      maxReasoningTokens = policy.maxReasoningTokens.map(_.toString),
      maxCostMicrounits = policy.maxCostMicrounits.map(_.toString),
      rateSchedule = policy.rateSchedule,
      timeoutSeconds = policy.timeoutSeconds.map(_.toString),
      recordRetryLimit = policy.recordRetryLimit.map(_.toString),
      maxConcurrent = policy.maxConcurrent.map(_.toString),
      outputSchemaId = policy.outputSchemaId,
      promptContractId = policy.promptContractId
    )
}

private[textus] final case class AiPurposePolicy(
  maxInputTokens: Option[Int] = None,
  maxOutputTokens: Option[Int] = None,
  maxReasoningTokens: Option[Int] = None,
  maxCostMicrounits: Option[Long] = None,
  rateSchedule: Option[String] = None,
  timeoutSeconds: Option[Long] = None,
  recordRetryLimit: Option[Int] = None,
  maxConcurrent: Option[Int] = None,
  concurrencyScope: Option[ConcurrencyScopeId] = None,
  outputSchemaId: Option[String] = None,
  promptContractId: Option[String] = None
) {
  def validateInputBudget(estimate: AiInputTokenEstimate): Consequence[Unit] =
    maxInputTokens match {
      case Some(limit) if estimate.tokens > limit =>
        Consequence.operationIllegal(
          "ai.input-budget",
          s"AI input budget exceeded: limit=$limit estimated=${estimate.tokens} basis=${AiInputTokenEstimator.basis}"
        )
      case _ =>
        Consequence.unit
    }

  def validateCostBudget(admission: Option[AiCostAdmission]): Consequence[Unit] =
    (maxCostMicrounits, admission) match {
      case (Some(limit), Some(estimate)) if estimate.upperBoundMicrounits > limit =>
        Consequence.operationIllegal(
          "ai.cost-budget",
          s"AI cost budget exceeded: limit=$limit estimated=${estimate.upperBoundMicrounits} basis=${AiCostAccounting.admissionBasis}"
        )
      case (Some(_), None) =>
        Consequence.configurationInvalid("AI cost budget has no admissible rate schedule estimate")
      case _ =>
        Consequence.unit
    }

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
  rateSchedule: Option[AiRateSchedule] = None,
  applicationPurpose: Option[String] = None,
  effectiveStandardPurpose: Option[String] = None,
  runtimeProfile: Option[String] = None,
  effectiveExecutionClass: Option[AiExecutionClass] = None,
  runtimeExecution: Option[AiRuntimeExecution] = None
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
    Option.when(isCodexProfile)(runtimeExecution.map(_.codexExecutionProfile)).flatten

  def reasoningLevel: Option[String] =
    runtimeExecution.flatMap(_.reasoningLevel)

  def costAdmissionC(
    input: AiInputTokenEstimate,
    maxOutputTokens: Option[Int]
  ): Consequence[Option[AiCostAdmission]] =
    (policy.maxCostMicrounits, rateSchedule) match {
      case (None, _) => Consequence.success(None)
      case (Some(_), Some(schedule)) =>
        for {
          output <- _cost_bound_c(maxOutputTokens, schedule.outputMicrounitsPerMillion, "max-output-tokens")
          reasoning <- _cost_bound_c(policy.maxReasoningTokens, schedule.reasoningMicrounitsPerMillion, "max-reasoning-tokens")
          admission <- AiCostAccounting.admissionC(schedule, input, output, reasoning)
        } yield Some(admission)
      case (Some(_), None) =>
        Consequence.configurationInvalid("AI cost budget requires an operator rate schedule")
    }

  def accountingFacts(
    metadata: Map[String, String],
    admission: Option[AiCostAdmission]
  ): AiAccountingFacts =
    rateSchedule match {
      case Some(schedule) =>
        _reported_cost_c(schedule, metadata) match {
          case Consequence.Success(cost) => AiAccountingFacts(
            rateScheduleId = Some(schedule.id),
            costMicrounits = Some(cost),
            costBasis = Some(AiCostAccounting.measuredBasis)
          )
          case Consequence.Failure(_) => admission match {
            case Some(estimate) => AiAccountingFacts(
              rateScheduleId = Some(schedule.id),
              costMicrounits = Some(estimate.upperBoundMicrounits),
              costBasis = Some(AiCostAccounting.admissionBasis),
              limitations = Vector("cost_estimated")
            )
            case None => AiAccountingFacts(
              rateScheduleId = Some(schedule.id),
              limitations = Vector("cost_unavailable")
            )
          }
        }
      case None => AiAccountingFacts.empty
    }

  def executionMetadata(
    maxTokens: Option[Int],
    properties: Vector[org.goldenport.protocol.Property],
    recordRetryLimit: Option[Int] = None,
    inputEstimate: Option[AiInputTokenEstimate] = None
  ): Map[String, String] = {
    val policyfacts = Vector(
      AiExecutionFacts.POLICY_MAX_INPUT_TOKENS -> policy.maxInputTokens.map(_.toString),
      AiExecutionFacts.POLICY_INPUT_BUDGET_BASIS -> Option.when(policy.maxInputTokens.nonEmpty)(AiInputTokenEstimator.basis),
      AiExecutionFacts.POLICY_MAX_OUTPUT_TOKENS -> maxTokens.map(_.toString),
      AiExecutionFacts.POLICY_MAX_REASONING_TOKENS -> policy.maxReasoningTokens.map(_.toString),
      AiExecutionFacts.POLICY_MAX_COST_MICROUNITS -> policy.maxCostMicrounits.map(_.toString),
      AiExecutionFacts.POLICY_TIMEOUT_SECONDS -> AiRequestProperties.timeoutSeconds(properties).map(_.toString),
      AiExecutionFacts.POLICY_RECORD_RETRY_LIMIT -> recordRetryLimit.map(_.toString),
      AiExecutionFacts.POLICY_MAX_CONCURRENT -> policy.maxConcurrent.map(_.toString),
      AiExecutionFacts.POLICY_OUTPUT_SCHEMA_ID -> policy.outputSchemaId,
      AiExecutionFacts.POLICY_PROMPT_CONTRACT_ID -> policy.promptContractId,
      AiExecutionFacts.POLICY_APPLICATION_PURPOSE -> applicationPurpose,
      AiExecutionFacts.POLICY_EFFECTIVE_STANDARD_PURPOSE -> effectiveStandardPurpose,
      AiExecutionFacts.POLICY_RUNTIME_PROFILE -> runtimeProfile,
      AiExecutionFacts.POLICY_EFFECTIVE_EXECUTION_CLASS -> effectiveExecutionClass.map(_.id),
      AiExecutionFacts.POLICY_REASONING_LEVEL -> reasoningLevel,
      AiExecutionFacts.ENABLED_TOOLS -> Option.when(codexExecutionProfile.nonEmpty && requirement.tools.nonEmpty)(
        requirement.tools.map(_.id).mkString(",")
      ),
      AiExecutionFacts.INPUT_PAYLOAD_BYTES -> inputEstimate.map(_.payloadBytes.toString),
      AiExecutionFacts.INPUT_ENVELOPE_TOKENS -> inputEstimate.map(_.envelopeTokens.toString)
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

  private def _required_cost_bound(value: Option[Int], label: String): Consequence[Int] =
    value match {
      case Some(bound) => Consequence.success(bound)
      case None => Consequence.configurationInvalid(s"AI cost budget requires $label")
    }

  private def _cost_bound_c(
    value: Option[Int],
    rate: Long,
    label: String
  ): Consequence[Int] =
    if (rate == 0) Consequence.success(0)
    else _required_cost_bound(value, label)

  private def _reported_cost_c(
    schedule: AiRateSchedule,
    metadata: Map[String, String]
  ): Consequence[Long] =
    for {
      input <- _reported_usage_c(metadata, AiExecutionFacts.INPUT_TOKENS, schedule.inputMicrounitsPerMillion > 0 || schedule.cachedInputMicrounitsPerMillion > 0)
      cached <- _reported_usage_c(metadata, AiExecutionFacts.CACHED_INPUT_TOKENS, schedule.cachedInputMicrounitsPerMillion > 0)
      output <- _reported_usage_c(metadata, AiExecutionFacts.OUTPUT_TOKENS, schedule.outputMicrounitsPerMillion > 0)
      reasoning <- _reported_usage_c(metadata, AiExecutionFacts.REASONING_TOKENS, schedule.reasoningMicrounitsPerMillion > 0)
      cost <- AiCostAccounting.measuredC(schedule, input, cached, output, reasoning)
    } yield cost

  private def _reported_usage_c(
    metadata: Map[String, String],
    key: String,
    required: Boolean
  ): Consequence[Long] =
    metadata.get(key).flatMap(_.toLongOption).filter(_ >= 0) match {
      case Some(value) if metadata.get(s"${key}_source").contains(AiUsageSource.Reported.id) =>
        Consequence.success(value)
      case None if !required => Consequence.success(0L)
      case _ => Consequence.configurationInvalid(s"AI provider did not report required usage: $key")
    }
}

private[textus] object AiPurposePolicy {
  val empty: AiPurposePolicy = AiPurposePolicy()
}

private[textus] final case class AiApplicationPurposeDefinition(
  name: String,
  standardPurpose: String,
  defaultPolicy: AiRunnerApplicationPurposePolicy
)

/*
 * Registration catalog assembled from application Component.Port outputs.
 * It stays live over the CNCF socket set, whose members are installed during
 * subsystem bootstrap before the runner accepts application requests.
 */
private[textus] trait AiApplicationPurposeCatalog {
  def findC(name: String): Consequence[Option[AiApplicationPurposeDefinition]]
  def definitionsC: Consequence[Vector[AiApplicationPurposeDefinition]]
}

private[textus] object AiApplicationPurposeCatalog {
  val empty: AiApplicationPurposeCatalog = fromRegistrations(Vector.empty)

  def fromSocket(
    socket: AiRunnerApplicationPurposeRegistrationSocketSet
  ): AiApplicationPurposeCatalog =
    new AiApplicationPurposeCatalog {
      def findC(name: String): Consequence[Option[AiApplicationPurposeDefinition]] =
        _definitions_c(socket.registrations).map(_.get(_normalize(name)))

      def definitionsC: Consequence[Vector[AiApplicationPurposeDefinition]] =
        _definitions_c(socket.registrations).map(_.values.toVector.sortBy(_.name))
    }

  def fromRegistrations(
    registrations: Vector[AiRunnerApplicationPurposeRegistration]
  ): AiApplicationPurposeCatalog =
    new AiApplicationPurposeCatalog {
      def findC(name: String): Consequence[Option[AiApplicationPurposeDefinition]] =
        _definitions_c(registrations).map(_.get(_normalize(name)))

      def definitionsC: Consequence[Vector[AiApplicationPurposeDefinition]] =
        _definitions_c(registrations).map(_.values.toVector.sortBy(_.name))
    }

  private def _definitions_c(
    registrations: Vector[AiRunnerApplicationPurposeRegistration]
  ): Consequence[Map[String, AiApplicationPurposeDefinition]] = {
    val definitions = registrations.flatMap(_.purposes).map(_definition)
    definitions.find(value => !_is_valid_name(value.name)) match {
      case Some(value) =>
        Consequence.configurationInvalid(s"Invalid AI application-purpose registration name: ${value.name}")
      case None =>
        definitions.groupBy(_.name).collectFirst { case (name, values) if values.size > 1 => name } match {
          case Some(name) =>
            Consequence.configurationInvalid(s"Duplicate AI application-purpose registration: $name")
          case None =>
            Consequence.success(definitions.map(value => value.name -> value).toMap)
        }
    }
  }

  private def _definition(
    registration: AiRunnerApplicationPurpose
  ): AiApplicationPurposeDefinition =
    AiApplicationPurposeDefinition(
      _normalize(registration.name),
      _normalize(registration.defaultStandardPurpose),
      registration.defaultPolicy
    )

  private def _normalize(value: String): String =
    Option(value).map(_.trim.toLowerCase(java.util.Locale.ROOT)).getOrElse("")

  private def _is_valid_name(value: String): Boolean =
    value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
}

private[textus] final class AiProfileConfig(
  configuration: Option[ResolvedConfiguration],
  catalog: AiApplicationPurposeCatalog = AiApplicationPurposeCatalog.empty
) {
  def resolveRequired(
    requirement: AiRunnerRequirement
  ): Consequence[AiProfileResolution] =
    if (configuration.isEmpty && !requirement.purposeRequired)
      Consequence.success(AiProfileResolution(requirement))
    else
      _resolve_runtime_requirement(requirement)

  def defaultSelectionC: Consequence[org.goldenport.cncf.spi.SpiSelection] =
    _runtime_profile_c.flatMap { profile =>
      profile.execution(AiExecutionClass.StandardWork) match {
        case Some(execution) => Consequence.success(org.goldenport.cncf.spi.SpiSelection(
          provider = Some(execution.provider),
          mode = Some(execution.mode),
          engine = Some(execution.engine)
        ))
        case None => Consequence.configurationInvalid(s"AI runtime profile has no standard-work binding: ${profile.name}")
      }
    }

  def codexExecutionsC: Consequence[Map[String, AiRuntimeExecution]] =
    for {
      _ <- _reject_legacy_configuration
      profile <- _runtime_profile_c
      executions <- profile.executions.keys.toVector.foldLeft(
        Consequence.success(Map.empty[String, AiRuntimeExecution])
      ) { (z, executionclass) =>
        z.flatMap { values =>
          _runtime_execution_c(profile, executionclass).map { execution =>
            if (_is_codex(Some(execution.provider)))
              values.updated(execution.codexExecutionProfile, execution)
            else
              values
          }
        }
      }
    } yield executions

  private def _resolve_runtime_requirement(
    requirement: AiRunnerRequirement
  ): Consequence[AiProfileResolution] =
    for {
      _ <- _reject_legacy_configuration
      _ <- _validate_application_configuration_c
      _ <- _reject_caller_selection(requirement)
      purpose <- requirement.purpose.map(_.trim).filter(_.nonEmpty) match {
        case Some(value) => Consequence.success(value)
        case None => Consequence.configurationInvalid("AI purpose is required")
      }
      runtimeprofile <- _runtime_profile_c
      identity <- _purpose_identity_c(purpose)
      execution <- _runtime_execution_c(runtimeprofile, identity.executionClass)
      baseprofile = _runtime_purpose_profile(identity, execution)
      basepolicy <- _purpose_policy(baseprofile)
      registrationprofile = identity.registration.map { value =>
        AiPurposeProfile.fromRegistration(identity.applicationPurpose, value.defaultPolicy)
      }.getOrElse(AiPurposeProfile(identity.applicationPurpose))
      registeredprofile = _inherit_purpose(baseprofile, registrationprofile)
      registeredpolicy <- _purpose_policy(registeredprofile)
      _ <- _validate_narrowing(
        AiProfileResolution(AiRunnerRequirement(), basepolicy),
        AiProfileResolution(AiRunnerRequirement(), registeredpolicy)
      )
      applicationprofile <- _application_purpose_profile_c(identity.applicationPurpose)
      _ <- _reject_application_selection(applicationprofile)
      effectiveprofile = _inherit_purpose(registeredprofile, applicationprofile)
      policy <- _purpose_policy(effectiveprofile)
      _ <- _validate_narrowing(
        AiProfileResolution(AiRunnerRequirement(), registeredpolicy),
        AiProfileResolution(AiRunnerRequirement(), policy)
      )
      rateschedule <- _rate_schedule_c(policy.rateSchedule)
      _ <- _validate_cost_policy(policy, rateschedule)
      effective = effectiveprofile.applyTo(requirement).copy(
        executionClass = Some(identity.executionClass)
      )
      _ <- _validate_runtime_execution(execution, effective)
    } yield AiProfileResolution(
      effective,
      policy,
      rateSchedule = rateschedule,
      applicationPurpose = Some(identity.applicationPurpose),
      effectiveStandardPurpose = Some(identity.standardPurpose),
      runtimeProfile = Some(runtimeprofile.name),
      effectiveExecutionClass = Some(identity.executionClass),
      runtimeExecution = Some(execution)
    )

  private final case class _PurposeIdentity(
    applicationPurpose: String,
    standardPurpose: String,
    executionClass: AiExecutionClass,
    registration: Option[AiApplicationPurposeDefinition]
  )

  private def _purpose_identity_c(purpose: String): Consequence[_PurposeIdentity] = {
    val normalized = purpose.trim.toLowerCase(java.util.Locale.ROOT)
    catalog.findC(normalized).flatMap {
      case Some(registration) =>
        val standard = registration.standardPurpose
        AiRuntimeProfileCatalog.standardPurpose(standard) match {
          case Some(executionclass) =>
            Consequence.success(_PurposeIdentity(
              registration.name,
              standard,
              executionclass,
              Some(registration)
            ))
          case None =>
            Consequence.configurationInvalid(s"AI application purpose selects an unknown standard purpose: $standard")
        }
      case None =>
        AiRuntimeProfileCatalog.standardPurpose(normalized)
          .map(value => Consequence.success(_PurposeIdentity(normalized, normalized, value, None)))
          .getOrElse(Consequence.configurationInvalid(s"AI application purpose is not registered: $purpose"))
    }
  }

  private def _runtime_profile_c: Consequence[AiRuntimeProfile] =
    _config_string(Vector("textus.ai.profile")) match {
      case Some(name) => AiRuntimeProfileCatalog.profile(name) match {
        case Some(profile) => Consequence.success(profile)
        case None => Consequence.configurationInvalid(s"AI runtime profile is not supported: $name")
      }
      case None => Consequence.configurationInvalid("AI runtime profile is required: textus.ai.profile")
    }

  private def _runtime_execution_c(
    profile: AiRuntimeProfile,
    executionclass: AiExecutionClass
  ): Consequence[AiRuntimeExecution] =
    profile.execution(executionclass) match {
      case Some(default) =>
        val provider = _config_string(_execution_class_keys(executionclass.id, "provider")).getOrElse(default.provider)
        val mode = _config_string(_execution_class_keys(executionclass.id, "mode")).getOrElse(default.mode)
        val engine = _config_string(_execution_class_keys(executionclass.id, "engine")).getOrElse(default.engine)
        val model = _config_string(_execution_class_keys(executionclass.id, "model")).getOrElse(default.model)
        val reasoning = _config_string(
          _execution_class_keys(executionclass.id, "reasoning-level") ++
            _execution_class_keys(executionclass.id, "reasoningLevel")
        ).orElse(default.reasoningLevel)
        val tools = _config_string(
          _execution_class_keys(executionclass.id, "tools") ++
            _execution_class_keys(executionclass.id, "enabled-tools")
        ).map(AiTool.parseList).getOrElse(default.tools)
        Consequence.success(default.copy(
          provider = provider,
          mode = mode,
          engine = engine,
          model = model,
          reasoningLevel = reasoning,
          tools = tools
        ))
      case None =>
        Consequence.configurationInvalid(s"AI runtime profile '${profile.name}' has no execution class: ${executionclass.id}")
    }

  private def _runtime_purpose_profile(
    identity: _PurposeIdentity,
    execution: AiRuntimeExecution
  ): AiPurposeProfile =
    AiPurposeProfile(
      purpose = identity.standardPurpose,
      executionClass = Some(identity.executionClass.id),
      provider = Some(execution.provider),
      mode = Some(execution.mode),
      engine = Some(execution.engine),
      model = Some(execution.model),
      tools = execution.tools,
      maxInputTokens = _config_string(_execution_class_keys(identity.executionClass.id, "max-input-tokens")),
      maxOutputTokens = _config_string(_execution_class_keys(identity.executionClass.id, "max-output-tokens")),
      maxReasoningTokens = _config_string(_execution_class_keys(identity.executionClass.id, "max-reasoning-tokens")),
      maxCostMicrounits = _config_string(_execution_class_keys(identity.executionClass.id, "max-cost-microunits")),
      rateSchedule = _config_string(_execution_class_keys(identity.executionClass.id, "rate-schedule")),
      timeoutSeconds = _config_string(_execution_class_keys(identity.executionClass.id, "timeout-seconds")),
      recordRetryLimit = _config_string(_execution_class_keys(identity.executionClass.id, "record-retry-limit")),
      maxConcurrent = _config_string(_execution_class_keys(identity.executionClass.id, "max-concurrent"))
    )

  private def _application_purpose_profile_c(
    purpose: String
  ): Consequence[AiPurposeProfile] =
    Consequence.success(AiPurposeProfile(
      purpose = purpose,
      maxInputTokens = _config_string(_application_purpose_keys(purpose, "max-input-tokens")),
      maxOutputTokens = _config_string(_application_purpose_keys(purpose, "max-output-tokens")),
      maxReasoningTokens = _config_string(_application_purpose_keys(purpose, "max-reasoning-tokens")),
      maxCostMicrounits = _config_string(_application_purpose_keys(purpose, "max-cost-microunits")),
      rateSchedule = _config_string(_application_purpose_keys(purpose, "rate-schedule")),
      timeoutSeconds = _config_string(_application_purpose_keys(purpose, "timeout-seconds")),
      recordRetryLimit = _config_string(_application_purpose_keys(purpose, "record-retry-limit")),
      maxConcurrent = _config_string(_application_purpose_keys(purpose, "max-concurrent")),
      outputSchemaId = _config_string(_application_purpose_keys(purpose, "output-schema-id")),
      promptContractId = _config_string(_application_purpose_keys(purpose, "prompt-contract-id"))
    ))

  private def _reject_caller_selection(requirement: AiRunnerRequirement): Consequence[Unit] =
    if (
      requirement.provider.nonEmpty ||
      requirement.mode.nonEmpty ||
      requirement.engine.nonEmpty ||
      requirement.model.nonEmpty ||
      requirement.executionClass.nonEmpty ||
      requirement.tools.nonEmpty
    )
      Consequence.configurationInvalid("AI callers may select only an application purpose")
    else
      Consequence.unit

  private def _reject_application_selection(profile: AiPurposeProfile): Consequence[Unit] =
    if (
      profile.provider.nonEmpty ||
      profile.mode.nonEmpty ||
      profile.engine.nonEmpty ||
      profile.model.nonEmpty ||
      profile.executionClass.nonEmpty ||
      profile.tools.nonEmpty
    )
      Consequence.configurationInvalid("AI application purpose may not select runtime execution settings")
    else
      Consequence.unit

  private def _validate_runtime_execution(
    execution: AiRuntimeExecution,
    requirement: AiRunnerRequirement
  ): Consequence[Unit] =
    if (_is_codex(Some(execution.provider)) && !execution.reasoningLevel.forall(_is_codex_reasoning_level))
      Consequence.configurationInvalid(s"Codex runtime profile has an unsupported reasoning-level: ${execution.executionClass.id}")
    else if (
      _is_codex(Some(execution.provider)) &&
      execution.tools.contains(AiTool.UrlContext) &&
      !execution.tools.contains(AiTool.WebSearch)
    )
      Consequence.configurationInvalid("Codex URL context requires the web_search capability")
    else if (requirement.provider.isEmpty)
      Consequence.configurationInvalid("AI runtime profile did not select a provider")
    else
      Consequence.unit

  private def _reject_legacy_configuration: Consequence[Unit] = {
    val keys = configuration.toVector.flatMap(_.configuration.values.keys)
    val legacy = keys.find { key =>
      Vector(
        "textus.ai.model-profiles.",
        "textus.ai.model-profile.",
        "textus.ai.modelProfiles.",
        "textus.runtime.ai.model-profiles.",
        "textus.runtime.ai.modelProfiles.",
        "cncf.ai.model-profiles.",
        "cncf.runtime.ai.model-profiles.",
        "textus.ai.generic-purposes.",
        "textus.ai.genericPurposes.",
        "textus.runtime.ai.generic-purposes.",
        "textus.runtime.ai.genericPurposes.",
        "cncf.ai.generic-purposes.",
        "cncf.runtime.ai.generic-purposes.",
        "textus.ai.levels.",
        "textus.ai.logical-levels.",
        "textus.runtime.ai.levels.",
        "textus.runtime.ai.logical-levels.",
        "cncf.ai.levels.",
        "cncf.runtime.ai.levels."
      ).exists(key.startsWith) ||
        key.contains(".model-profile") ||
        key.contains(".modelProfile") ||
        key.contains(".base-purpose") ||
        key.contains(".basePurpose") ||
        key.endsWith(".level")
    }
    val standardpurpose = keys.find(_.startsWith("textus.ai.purposes."))
    val invalidapplication = keys.find(_is_invalid_application_purpose_key)
    legacy match {
      case Some(key) => Consequence.configurationInvalid(s"Legacy AI configuration is not supported: $key")
      case None => standardpurpose match {
        case Some(key) => Consequence.configurationInvalid(s"Standard AI purposes are runtime-owned and may not be configured: $key")
        case None => invalidapplication match {
          case Some(key) => Consequence.configurationInvalid(s"AI application purpose may not configure this key: $key")
          case None => Consequence.unit
        }
      }
    }
  }

  private def _is_invalid_application_purpose_key(key: String): Boolean = {
    val prefix = "textus.ai.application-purposes."
    val permitted = Set(
      "max-input-tokens",
      "max-output-tokens",
      "max-reasoning-tokens",
      "max-cost-microunits",
      "rate-schedule",
      "timeout-seconds",
      "record-retry-limit",
      "max-concurrent",
      "output-schema-id",
      "prompt-contract-id"
    )
    key.startsWith(prefix) && !permitted.contains(key.drop(prefix.length).split("\\.").lastOption.getOrElse(""))
  }

  private def _validate_application_configuration_c: Consequence[Unit] =
    catalog.definitionsC.flatMap { definitions =>
      val registered = definitions.map(_.name).toSet
      _configured_application_purposes.find(name => !registered.contains(name)) match {
        case Some(name) =>
          Consequence.configurationInvalid(
            s"AI application-purpose configuration does not register a purpose: $name"
          )
        case None =>
          Consequence.unit
      }
    }

  private def _inherit_purpose(
    base: AiPurposeProfile,
    application: AiPurposeProfile
  ): AiPurposeProfile =
    application.copy(
      executionClass = application.executionClass.orElse(base.executionClass),
      provider = application.provider.orElse(base.provider),
      mode = application.mode.orElse(base.mode),
      engine = application.engine.orElse(base.engine),
      model = application.model.orElse(base.model),
      tools = if (application.tools.nonEmpty) application.tools else base.tools,
      maxInputTokens = application.maxInputTokens.orElse(base.maxInputTokens),
      maxOutputTokens = application.maxOutputTokens.orElse(base.maxOutputTokens),
      maxReasoningTokens = application.maxReasoningTokens.orElse(base.maxReasoningTokens),
      maxCostMicrounits = application.maxCostMicrounits.orElse(base.maxCostMicrounits),
      rateSchedule = application.rateSchedule.orElse(base.rateSchedule),
      timeoutSeconds = application.timeoutSeconds.orElse(base.timeoutSeconds),
      recordRetryLimit = application.recordRetryLimit.orElse(base.recordRetryLimit),
      maxConcurrent = application.maxConcurrent.orElse(base.maxConcurrent),
      outputSchemaId = application.outputSchemaId.orElse(base.outputSchemaId),
      promptContractId = application.promptContractId.orElse(base.promptContractId)
    )

  private def _validate_narrowing(
    base: AiProfileResolution,
    application: AiProfileResolution
  ): Consequence[Unit] =
    if (!_is_narrower_policy(base.policy, application.policy))
      Consequence.configurationInvalid("AI application purpose may not broaden inherited execution policy")
    else
      Consequence.unit

  private def _is_narrower_policy(
    base: AiPurposePolicy,
    application: AiPurposePolicy
  ): Boolean =
    _at_most(application.maxInputTokens, base.maxInputTokens) &&
      _at_most(application.maxOutputTokens, base.maxOutputTokens) &&
      _at_most(application.maxReasoningTokens, base.maxReasoningTokens) &&
      _at_most(application.maxCostMicrounits, base.maxCostMicrounits) &&
      _same_or_added(application.rateSchedule, base.rateSchedule) &&
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

  private def _is_codex(provider: Option[String]): Boolean =
    provider.exists { value =>
      value.trim.equalsIgnoreCase("codex") || value.trim.equalsIgnoreCase("codex-cli")
    }

  private def _is_codex_reasoning_level(value: String): Boolean =
    Set("minimal", "low", "medium", "high", "xhigh").contains(
      value.trim.toLowerCase(java.util.Locale.ROOT)
    )

  private def _rate_schedule_c(
    id: Option[String]
  ): Consequence[Option[AiRateSchedule]] =
    id match {
      case Some(value) => _resolve_rate_schedule_c(value).map(Some(_))
      case None => Consequence.success(None)
    }

  private def _resolve_rate_schedule_c(id: String): Consequence[AiRateSchedule] =
    for {
      input <- _rate_value_c(id, "input-microunits-per-million-tokens")
      cached <- _rate_value_c(id, "cached-input-microunits-per-million-tokens")
      output <- _rate_value_c(id, "output-microunits-per-million-tokens")
      reasoning <- _rate_value_c(id, "reasoning-microunits-per-million-tokens")
    } yield AiRateSchedule(id, input, cached, output, reasoning)

  private def _rate_value_c(id: String, leaf: String): Consequence[Long] =
    _config_string(_rate_schedule_keys(id, leaf)) match {
      case Some(raw) if raw.toLongOption.exists(_ >= 0) => Consequence.success(raw.toLong)
      case _ => Consequence.configurationInvalid(s"Invalid AI rate schedule $leaf: $id")
    }

  private def _validate_cost_policy(
    policy: AiPurposePolicy,
    schedule: Option[AiRateSchedule]
  ): Consequence[Unit] =
    (policy.maxCostMicrounits, schedule) match {
      case (Some(_), None) =>
        Consequence.configurationInvalid("AI cost budget requires an operator rate schedule")
      case _ => Consequence.unit
    }

  private def _purpose_policy(
    profile: AiPurposeProfile
  ): Consequence[AiPurposePolicy] =
    for {
      maxinputtokens <- _positive_int(profile.maxInputTokens, "max-input-tokens", profile.purpose)
      maxtokens <- _positive_int(profile.maxOutputTokens, "max-output-tokens", profile.purpose)
      maxreasoningtokens <- _positive_int(profile.maxReasoningTokens, "max-reasoning-tokens", profile.purpose)
      maxcostmicrounits <- _positive_long(profile.maxCostMicrounits, "max-cost-microunits", profile.purpose)
      rateschedule <- _policy_id(profile.rateSchedule, "rate-schedule", profile.purpose)
      timeoutseconds <- _positive_long(profile.timeoutSeconds, "timeout-seconds", profile.purpose)
      retrylimit <- _bounded_int(profile.recordRetryLimit, "record-retry-limit", profile.purpose, 0, 3)
      maxconcurrent <- _positive_int(profile.maxConcurrent, "max-concurrent", profile.purpose)
      concurrencyscope <- _concurrency_scope_c(profile.purpose, maxconcurrent)
      outputschemaid <- _policy_id(profile.outputSchemaId, "output-schema-id", profile.purpose)
      promptcontractid <- _policy_id(profile.promptContractId, "prompt-contract-id", profile.purpose)
    } yield AiPurposePolicy(
      maxInputTokens = maxinputtokens,
      maxOutputTokens = maxtokens,
      maxReasoningTokens = maxreasoningtokens,
      maxCostMicrounits = maxcostmicrounits,
      rateSchedule = rateschedule,
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

  private def _application_purpose_keys(
    purpose: String,
    leaf: String
  ): Vector[String] =
    Vector(
      s"textus.ai.application-purposes.$purpose.$leaf"
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

  private def _rate_schedule_keys(
    schedule: String,
    leaf: String
  ): Vector[String] =
    Vector(
      s"textus.ai.rate-schedules.$schedule.$leaf",
      s"textus.ai.rateSchedules.$schedule.$leaf",
      s"textus.runtime.ai.rate-schedules.$schedule.$leaf",
      s"cncf.ai.rate-schedules.$schedule.$leaf",
      s"cncf.runtime.ai.rate-schedules.$schedule.$leaf"
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


  def concurrencyAdmissionC: Consequence[Option[ScopedConcurrencyAdmission]] =
    concurrencyAdmissionWithScopesC.map(_.map(_._1))

  private[textus] def concurrencyAdmissionWithScopesC: Consequence[Option[(ScopedConcurrencyAdmission, Set[ConcurrencyScopeId])]] =
    _runtime_concurrency_grants_c.flatMap { grants =>
      if (grants.nonEmpty)
        ScopedConcurrencyAdmission.createC(grants).map { admission =>
          Some(admission -> grants.map(_.scope).toSet)
        }
      else
        Consequence.success(None)
    }

  private def _runtime_concurrency_grants_c: Consequence[Vector[ConcurrencyGrant]] =
    _application_concurrency_purposes_c.flatMap { purposes =>
      purposes.foldLeft(Consequence.success(Vector.empty[ConcurrencyGrant])) { (z, purpose) =>
        z.flatMap { grants =>
          _resolve_runtime_requirement(AiRunnerRequirement(purpose = Some(purpose), purposeRequired = true)).map { resolution =>
            (resolution.policy.concurrencyScope, resolution.policy.maxConcurrent) match {
              case (Some(scope), Some(limit)) => grants :+ ConcurrencyGrant(scope, limit)
              case _ => grants
            }
          }
        }
      }
    }

  private def _application_concurrency_purposes_c: Consequence[Vector[String]] =
    catalog.definitionsC.map { definitions =>
      (definitions.collect {
        case value if value.defaultPolicy.maxConcurrent.nonEmpty => value.name
      } ++ _configured_application_concurrency_purposes).distinct.sorted
    }

  private def _configured_application_purposes: Vector[String] =
    configuration.toVector
      .flatMap(_.configuration.values.keys)
      .flatMap { key =>
        val prefix = "textus.ai.application-purposes."
        Option.when(key.startsWith(prefix)) {
          key.drop(prefix.length).split("\\.").dropRight(1).mkString(".").trim.toLowerCase(java.util.Locale.ROOT)
        }
      }
      .filter(_.nonEmpty)
      .distinct
      .sorted

  private def _configured_application_concurrency_purposes: Vector[String] =
    configuration.toVector
      .flatMap(_.configuration.values.keys)
      .flatMap { key =>
        val prefix = "textus.ai.application-purposes."
        val suffix = ".max-concurrent"
        Option.when(key.startsWith(prefix) && key.endsWith(suffix)) {
          key.drop(prefix.length).dropRight(suffix.length).trim.toLowerCase(java.util.Locale.ROOT)
        }
      }
      .filter(_.nonEmpty)
      .distinct
      .sorted


}

private[textus] object AiProfileConfig {
  val empty: AiProfileConfig = new AiProfileConfig(None)

  def fromConfiguration(
    configuration: Option[ResolvedConfiguration],
    catalog: AiApplicationPurposeCatalog = AiApplicationPurposeCatalog.empty
  ): AiProfileConfig =
    new AiProfileConfig(configuration, catalog)
}
