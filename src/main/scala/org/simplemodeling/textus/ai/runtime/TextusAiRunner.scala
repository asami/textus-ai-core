package org.simplemodeling.textus.ai.runtime

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import io.circe.Json
import io.circe.parser.parse
import org.goldenport.Consequence
import org.goldenport.cncf.admission.{ConcurrencyGrant, ConcurrencyScopeId, ScopedConcurrencyAdmission}
import org.goldenport.cncf.component.Component
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.{SpiContract, SpiProvider, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.*
import org.goldenport.protocol.Property
import org.goldenport.record.Record
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.ai.ai.{ChatRequest, ChatResponse, GenerateRequest, GenerateResponse, Message}

/*
 * CNCF AI runner SPI adapter for the Textus AI runtime bindings.
 *
 * The provider resolves existing generate/chat bindings lazily, so the SPI is
 * an additional publication surface and does not replace the public component
 * operations.
 *
 * @since   Jul.  2, 2026
 * @version Jul. 20, 2026
 * @author  ASAMI, Tomoharu
 */
final class TextusAiRunner(
  provider: TextusAiRunnerProvider,
  selection: SpiSelection,
  profiles: AiProfileConfig = AiProfileConfig.empty
) extends AiRunner {
  def generate(req: AiGenerateRequest)(using ExecutionContext): Consequence[AiGenerateResponse] =
    _effective_resolution(req.requirement).flatMap { resolution =>
      val requirement = resolution.requirement
      val effective = _effective_selection(requirement)
      val properties = _request_properties(req.properties, resolution)
      val maxtokens = resolution.maxTokens(req.maxTokens)
      val inputestimate = AiInputTokenEstimator.generate(req.prompt)
      val policymetadata = resolution.executionMetadata(maxtokens, properties, inputEstimate = Some(inputestimate))
      _with_generate_calltree(req, requirement, maxtokens, policymetadata) {
        for {
          _ <- resolution.policy.validateInputBudget(inputestimate)
          costadmission <- resolution.costAdmissionC(inputestimate, maxtokens)
          _ <- resolution.policy.validateCostBudget(costadmission)
          _ <- resolution.policy.validateGenerate(properties)
          _ <- AiProviderAdmission.validate(effective, properties)
          response <- _with_concurrency_admission_c(resolution) {
            for {
              service <- provider.generateService(effective)
              response <- service.generate(
                GenerateRequest(
                  prompt = req.prompt,
                  temperature = req.temperature,
                  maxTokens = maxtokens,
                  properties = properties
                )
              )
              _ <- AiExecutionFacts.validateMaxOutputTokens(effective, maxtokens, response.metadata)
              _ <- AiExecutionFacts.validateMaxReasoningTokens(
                effective,
                resolution.policy.maxReasoningTokens,
                response.metadata
              )
            } yield response
          }
        } yield _to_ai_generate_response(req, response, requirement, policymetadata, inputestimate, resolution, costadmission)
      }
    }

  def generateRecord(req: AiRecordRequest)(using ExecutionContext): Consequence[AiRecordResponse] =
    _effective_resolution(req.requirement).flatMap { resolution =>
      val requirement = resolution.requirement
      val effective = _effective_selection(requirement)
      val properties = _request_properties(req.properties, resolution)
      val maxtokens = resolution.maxTokens(req.maxTokens)
      val retries = _record_retry_limit(req, resolution)
      val inputestimate = AiInputTokenEstimator.record(req.prompt)
      val policymetadata = resolution.executionMetadata(
        maxtokens,
        properties,
        Some(retries),
        Some(inputestimate)
      )
      val costadmission = resolution.costAdmissionC(inputestimate, maxtokens)
      _with_record_calltree(req, requirement, maxtokens, policymetadata) {
        for {
          _ <- resolution.policy.validateInputBudget(inputestimate)
          admission <- costadmission
          _ <- resolution.policy.validateCostBudget(admission)
          _ <- resolution.policy.validateRecord(properties)
          _ <- AiProviderAdmission.validate(effective, properties)
          response <- _with_concurrency_admission_c(resolution) {
            provider.generateService(effective).flatMap { service =>
              _generate_record_raw_with_retry(
                service,
                req,
                resolution,
                retries
              )
            }
          }
          _ <- AiExecutionFacts.validateMaxOutputTokens(effective, maxtokens, response.metadata)
          _ <- AiExecutionFacts.validateMaxReasoningTokens(
            effective,
            resolution.policy.maxReasoningTokens,
            response.metadata
          )
        } yield response
      } { response =>
        costadmission.flatMap { admission =>
          _normalize_record_response(req, response, requirement, policymetadata, inputestimate, resolution, admission)
        }
      }
    }

  def chat(req: AiChatRequest)(using ExecutionContext): Consequence[AiChatResponse] =
    _effective_resolution(req.requirement).flatMap { resolution =>
      val requirement = resolution.requirement
      val effective = _effective_selection(requirement)
      val properties = _request_properties(req.properties, resolution)
      val maxtokens = resolution.maxTokens(req.maxTokens)
      val inputestimate = AiInputTokenEstimator.chat(_chat_input(req), req.messages.length)
      val policymetadata = resolution.executionMetadata(maxtokens, properties, inputEstimate = Some(inputestimate))
      _with_chat_calltree(req, requirement, maxtokens, policymetadata) {
        for {
          _ <- resolution.policy.validateInputBudget(inputestimate)
          costadmission <- resolution.costAdmissionC(inputestimate, maxtokens)
          _ <- resolution.policy.validateCostBudget(costadmission)
          _ <- resolution.policy.validateChat(properties)
          _ <- AiProviderAdmission.validate(effective, properties)
          response <- _with_concurrency_admission_c(resolution) {
            for {
              service <- provider.chatService(effective)
              response <- service.chat(
                ChatRequest(
                  messages = req.messages.map(_to_textus_message),
                  temperature = req.temperature,
                  maxTokens = maxtokens,
                  properties = properties
                )
              )
              _ <- AiExecutionFacts.validateMaxOutputTokens(effective, maxtokens, response.metadata)
              _ <- AiExecutionFacts.validateMaxReasoningTokens(
                effective,
                resolution.policy.maxReasoningTokens,
                response.metadata
              )
            } yield response
          }
        } yield _to_ai_chat_response(req, response, requirement, policymetadata, inputestimate, resolution, costadmission)
      }
    }

  private def _generate_record_raw_with_retry(
    service: GenerateService,
    req: AiRecordRequest,
    resolution: AiProfileResolution,
    remainingRetries: Int
  )(using ExecutionContext): Consequence[GenerateResponse] = {
    val generated = service.generate(
      GenerateRequest(
        prompt = req.prompt,
        temperature = req.temperature,
        maxTokens = resolution.maxTokens(req.maxTokens),
        properties = _request_properties(req.properties, resolution),
        recordSchema = Some(req.schema)
      )
    )
    generated match {
      case Consequence.Success(response) if response.text.trim.nonEmpty =>
        Consequence.success(response)
      case Consequence.Success(response) if remainingRetries > 0 && _is_record_retryable_empty_response(response) =>
        _generate_record_raw_with_retry(service, req, resolution, remainingRetries - 1)
      case Consequence.Success(response) =>
        Consequence.success(response)
      case Consequence.Failure(conclusion) if remainingRetries > 0 && _is_record_retryable_failure(conclusion) =>
        _generate_record_raw_with_retry(service, req, resolution, remainingRetries - 1)
      case Consequence.Failure(conclusion) =>
        Consequence.Failure(conclusion)
    }
  }

  private def _is_record_retryable_empty_response(
    response: GenerateResponse
  ): Boolean =
    response.text.trim.isEmpty

  private def _is_record_retryable_failure(
    conclusion: org.goldenport.Conclusion
  ): Boolean = {
    val message = conclusion.display.toLowerCase(Locale.ROOT)
    message.contains("timed out") ||
      message.contains("timeout") ||
      message.contains("did not contain model output text") ||
      message.contains("empty output")
  }

  private def _record_retry_limit(
    req: AiRecordRequest,
    resolution: AiProfileResolution
  ): Int =
    req.properties.find(_.name == "ai.record.retry-limit")
      .flatMap(x => Option(x.value).map(_.toString.trim).filter(_.nonEmpty).flatMap(_.toIntOption))
      .orElse(resolution.policy.recordRetryLimit)
      .getOrElse(1)
      .max(0)
      .min(3)

  private def _with_concurrency_admission_c[A](
    resolution: AiProfileResolution
  )(
    body: => Consequence[A]
  ): Consequence[A] =
    provider.withConcurrencyAdmissionC(resolution.policy)(body)

  private def _with_record_calltree(
    req: AiRecordRequest,
    requirement: AiRunnerRequirement,
    maxtokens: Option[Int],
    policymetadata: Map[String, String]
  )(
    body: => Consequence[GenerateResponse]
  )(
    normalize: GenerateResponse => Consequence[_Accounted[AiRecordResponse]]
  )(using ctx: ExecutionContext): Consequence[AiRecordResponse] = {
    val calltree = ctx.observability.callTreeContext
    if (calltree.isEnabled) {
      calltree.enter(
        "provider:textus-ai-runner:generate-record",
        _record_request_calltree_attributes(req, requirement, maxtokens, policymetadata)
      )
      try {
        val generated = body
        generated match {
          case Consequence.Success(rawresponse) =>
            val result = normalize(rawresponse)
            result match {
              case Consequence.Success(accounted) =>
                calltree.leave(_record_response_calltree_attributes(req, rawresponse, accounted.response, accounted.accounting))
                Consequence.success(accounted.response)
              case Consequence.Failure(conclusion) =>
                calltree.leave(
                  _record_failure_calltree_attributes(req, rawresponse) ++ Map(
                    "status" -> conclusion.status.webCode.code.toString
                  )
                )
                Consequence.Failure(conclusion)
            }
          case Consequence.Failure(conclusion) =>
            calltree.leave(Map(
              "outcome" -> "failure",
              "status" -> conclusion.status.webCode.code.toString
            ))
            Consequence.Failure(conclusion)
        }
      } catch {
        case e: Throwable =>
          calltree.leave(Map(
            "outcome" -> "failure",
            "status" -> "500"
          ))
          throw e
      }
    } else {
      body.flatMap(normalize).map(_.response)
    }
  }

  private def _with_generate_calltree(
    req: AiGenerateRequest,
    requirement: AiRunnerRequirement,
    maxtokens: Option[Int],
    policymetadata: Map[String, String]
  )(
    body: => Consequence[_Accounted[AiGenerateResponse]]
  )(using ctx: ExecutionContext): Consequence[AiGenerateResponse] = {
    val calltree = ctx.observability.callTreeContext
    if (calltree.isEnabled) {
      calltree.enter(
        "provider:textus-ai-runner:generate",
        _generate_request_calltree_attributes(req, requirement, maxtokens, policymetadata)
      )
      try {
        val result = body
        result match {
          case Consequence.Success(accounted) =>
            calltree.leave(_generate_response_calltree_attributes(req, accounted.response, accounted.accounting))
            Consequence.success(accounted.response)
          case Consequence.Failure(conclusion) =>
            calltree.leave(Map(
            "outcome" -> "failure",
            "status" -> conclusion.status.webCode.code.toString
            ))
            Consequence.Failure(conclusion)
        }
      } catch {
        case e: Throwable =>
          calltree.leave(Map(
            "outcome" -> "failure",
            "status" -> "500"
          ))
          throw e
      }
    } else {
      body.map(_.response)
    }
  }

  private def _with_chat_calltree(
    req: AiChatRequest,
    requirement: AiRunnerRequirement,
    maxtokens: Option[Int],
    policymetadata: Map[String, String]
  )(
    body: => Consequence[_Accounted[AiChatResponse]]
  )(using ctx: ExecutionContext): Consequence[AiChatResponse] = {
    val calltree = ctx.observability.callTreeContext
    if (calltree.isEnabled) {
      calltree.enter(
        "provider:textus-ai-runner:chat",
        _chat_request_calltree_attributes(req, requirement, maxtokens, policymetadata)
      )
      try {
        val result = body
        result match {
          case Consequence.Success(accounted) =>
            calltree.leave(_chat_response_calltree_attributes(req, accounted.response, accounted.accounting))
            Consequence.success(accounted.response)
          case Consequence.Failure(conclusion) =>
            calltree.leave(Map(
            "outcome" -> "failure",
            "status" -> conclusion.status.webCode.code.toString
            ))
            Consequence.Failure(conclusion)
        }
      } catch {
        case e: Throwable =>
          calltree.leave(Map(
            "outcome" -> "failure",
            "status" -> "500"
          ))
          throw e
      }
    } else {
      body.map(_.response)
    }
  }

  private def _generate_request_calltree_attributes(
    req: AiGenerateRequest,
    requirement: AiRunnerRequirement,
    maxtokens: Option[Int],
    policymetadata: Map[String, String]
  ): Map[String, String] =
    _common_request_calltree_attributes(
      requirement,
      req.temperature,
      maxtokens,
      policymetadata,
      req.trace.promptConfidentiality.label,
      req.trace.responseConfidentiality.label
    ) ++ Map(
      "operation" -> "generate",
      "input_chars" -> req.prompt.length.toString,
      "input_digest" -> AiExecutionFacts.digest(req.prompt)
    )

  private def _chat_request_calltree_attributes(
    req: AiChatRequest,
    requirement: AiRunnerRequirement,
    maxtokens: Option[Int],
    policymetadata: Map[String, String]
  ): Map[String, String] = {
    val prompttext = _chat_input(req)
    _common_request_calltree_attributes(
      requirement,
      req.temperature,
      maxtokens,
      policymetadata,
      req.trace.promptConfidentiality.label,
      req.trace.responseConfidentiality.label
    ) ++ Map(
      "operation" -> "chat",
      "message_count" -> req.messages.length.toString,
      "input_chars" -> prompttext.length.toString,
      "input_digest" -> AiExecutionFacts.digest(prompttext)
    )
  }

  private def _record_request_calltree_attributes(
    req: AiRecordRequest,
    requirement: AiRunnerRequirement,
    maxtokens: Option[Int],
    policymetadata: Map[String, String]
  ): Map[String, String] =
    _common_request_calltree_attributes(
      requirement,
      req.temperature,
      maxtokens,
      policymetadata,
      req.trace.promptConfidentiality.label,
      req.trace.responseConfidentiality.label
    ) ++ Map(
      "operation" -> "generate-record",
      "record_schema_required" -> _schema_required(req.schema).mkString(","),
      "input_chars" -> req.prompt.length.toString,
      "input_digest" -> AiExecutionFacts.digest(req.prompt)
    )

  private def _common_request_calltree_attributes(
    requirement: AiRunnerRequirement,
    temperature: Option[Double],
    maxtokens: Option[Int],
    policymetadata: Map[String, String],
    promptconfidentiality: String,
    responseconfidentiality: String
  ): Map[String, String] = {
    val effective = _effective_selection(requirement)
    Map(
      "calltree_kind" -> "provider-step",
      "provider" -> effective.provider.getOrElse(""),
      "mode" -> effective.mode.getOrElse(""),
      "engine" -> effective.engine.getOrElse(""),
      "purpose" -> requirement.purpose.getOrElse(""),
      "requested_model" -> requirement.model.getOrElse(""),
      "ai_tools" -> requirement.tools.map(_.id).mkString(","),
      "temperature" -> temperature.map(_.toString).getOrElse(""),
      "max_tokens" -> maxtokens.map(_.toString).getOrElse(""),
      "prompt_confidentiality" -> promptconfidentiality,
      "response_confidentiality" -> responseconfidentiality
    ) ++ policymetadata
  }

  private def _generate_response_calltree_attributes(
    req: AiGenerateRequest,
    response: AiGenerateResponse,
    accounting: AiAccountingFacts
  ): Map[String, String] =
    Map(
      "outcome" -> "success",
      "model" -> response.model.getOrElse(""),
      "output_chars" -> response.text.length.toString,
      "output_digest" -> AiExecutionFacts.digest(response.text)
    ) ++ AiExecutionFacts.calltreeMetadata(response.metadata, accounting)

  private def _chat_response_calltree_attributes(
    req: AiChatRequest,
    response: AiChatResponse,
    accounting: AiAccountingFacts
  ): Map[String, String] = {
    val responsetext = response.message.content
    Map(
      "outcome" -> "success",
      "model" -> response.model.getOrElse(""),
      "output_chars" -> responsetext.length.toString,
      "output_digest" -> AiExecutionFacts.digest(responsetext)
    ) ++ AiExecutionFacts.calltreeMetadata(response.metadata, accounting)
  }

  private def _record_response_calltree_attributes(
    req: AiRecordRequest,
    rawresponse: GenerateResponse,
    response: AiRecordResponse,
    accounting: AiAccountingFacts
  ): Map[String, String] =
    Map(
      "outcome" -> "success",
      "model" -> response.model.getOrElse(""),
      "normalization_mode" -> response.metadata.getOrElse(AiExecutionFacts.NORMALIZATION_MODE, ""),
      "output_chars" -> rawresponse.text.length.toString,
      "output_digest" -> AiExecutionFacts.digest(rawresponse.text)
    ) ++ AiExecutionFacts.calltreeMetadata(response.metadata, accounting)

  private def _record_failure_calltree_attributes(
    req: AiRecordRequest,
    rawresponse: GenerateResponse
  ): Map[String, String] =
    Map(
      "outcome" -> "failure",
      "model" -> _effective_model(rawresponse.model).getOrElse(""),
      "output_chars" -> rawresponse.text.length.toString,
      "output_digest" -> AiExecutionFacts.digest(rawresponse.text)
    ) ++ AiExecutionFacts.calltreeMetadata(rawresponse.metadata)

  private def _effective_selection(
    requirement: AiRunnerRequirement
  ): SpiSelection = {
    val requestedprovider = requirement.provider.map(_canonical_provider)
    val selectedprovider = selection.provider.map(_canonical_provider)
    val provider = requestedprovider.orElse(selectedprovider)
    val shouldinherit = requestedprovider.isEmpty || requestedprovider == selectedprovider
    val providername = provider.getOrElse("gemma")
    if (
      requirement.provider.isEmpty &&
      requirement.mode.isEmpty &&
      requirement.engine.isEmpty
    )
      selection.copy(provider = provider)
    else
      SpiSelection(
        provider = provider,
        mode = requirement.mode.
          orElse(if (shouldinherit) selection.mode else None).
          orElse(Some(_default_mode(providername))),
        engine = requirement.engine.
          orElse(if (shouldinherit) selection.engine else None).
          orElse(Some(_default_engine(providername)))
      )
  }

  private def _effective_resolution(
    requirement: AiRunnerRequirement
  ): Consequence[AiProfileResolution] =
    profiles.resolveRequired(requirement)

  private def _request_properties(
    properties: Vector[Property],
    resolution: AiProfileResolution
  ): Vector[Property] = {
    val requirement = resolution.requirement
    val modelproperty = Option.when(!resolution.isManagedCliProfile)(requirement.model).flatten.map { value =>
      Property("ai.model", value, None)
    }
    val purposeproperty = requirement.purpose.map(value => Property("ai.purpose", value, None))
    val toolsproperty =
      Option.when(requirement.tools.nonEmpty)(
        Property("ai.tools", requirement.tools.map(_.id).mkString(","), None)
      )
    val sanitized = AiRequestProperties.withoutInternalCodexProfile(properties)
    val controlled =
      if (resolution.controlsOpenAiReasoning)
        AiRequestProperties.withoutOpenAiReasoningOverride(sanitized)
      else
        sanitized
    resolution.requestProperties(controlled) ++
      modelproperty ++ purposeproperty ++ toolsproperty
  }

  private def _chat_input(req: AiChatRequest): String =
    req.messages.map(message => s"${message.role}: ${message.content}").mkString("\n")

  private def _to_ai_generate_response(
    req: AiGenerateRequest,
    response: GenerateResponse,
    requirement: AiRunnerRequirement,
    policymetadata: Map[String, String],
    inputestimate: AiInputTokenEstimate,
    resolution: AiProfileResolution,
    costadmission: Option[AiCostAdmission]
  ): _Accounted[AiGenerateResponse] = {
    val metadata = AiExecutionFacts.normalize(
        _effective_selection(requirement),
        requirement,
        response.model,
        response.metadata,
        policymetadata = policymetadata,
        estimatedusage = inputestimate.usageFacts
      ) ++ AiExecutionFacts.digestMetadata(req.prompt, response.text)
    val accounting = resolution.accountingFacts(metadata, costadmission)
    _Accounted(AiGenerateResponse(
      response.text,
      _effective_model(response.model),
      AiExecutionFacts.lifecycleLimitations(metadata ++ accounting.responseMetadata, accounting)
    ), accounting)
  }

  private def _to_ai_chat_response(
    req: AiChatRequest,
    response: ChatResponse,
    requirement: AiRunnerRequirement,
    policymetadata: Map[String, String],
    inputestimate: AiInputTokenEstimate,
    resolution: AiProfileResolution,
    costadmission: Option[AiCostAdmission]
  ): _Accounted[AiChatResponse] = {
    val metadata = AiExecutionFacts.normalize(
        _effective_selection(requirement),
        requirement,
        response.model,
        response.metadata,
        policymetadata = policymetadata,
        estimatedusage = inputestimate.usageFacts
      ) ++ AiExecutionFacts.digestMetadata(_chat_input(req), response.message.content)
    val accounting = resolution.accountingFacts(metadata, costadmission)
    _Accounted(AiChatResponse(
      _to_ai_message(response.message),
      _effective_model(response.model),
      AiExecutionFacts.lifecycleLimitations(metadata ++ accounting.responseMetadata, accounting)
    ), accounting)
  }

  private def _normalize_record_response(
    req: AiRecordRequest,
    response: GenerateResponse,
    requirement: AiRunnerRequirement,
    policymetadata: Map[String, String],
    inputestimate: AiInputTokenEstimate,
    resolution: AiProfileResolution,
    costadmission: Option[AiCostAdmission]
  ): Consequence[_Accounted[AiRecordResponse]] =
    if (response.text.trim.isEmpty)
      Consequence.argumentInvalid("AI record response was empty.")
    else
      _record_candidates(response.text)
      .iterator
      .map { candidate =>
        parse(candidate.text).map(json => candidate.copy(json = Some(json)))
      }
      .collectFirst { case Right(candidate) => candidate } match {
      case Some(candidate) =>
        candidate.json match {
          case Some(json) =>
            val record = _json_to_record(json)
            _validate_schema(record, req.schema) match {
              case Right(()) =>
                val metadata = AiExecutionFacts.normalize(
                  _effective_selection(requirement),
                  requirement,
                  response.model,
                  response.metadata,
                  Some(candidate.mode),
                  policymetadata,
                  estimatedusage = inputestimate.usageFacts
                ) ++ AiExecutionFacts.digestMetadata(req.prompt, response.text)
                val accounting = resolution.accountingFacts(metadata, costadmission)
                Consequence.success(_Accounted(
                  AiRecordResponse(
                    record,
                    _effective_model(response.model),
                    AiExecutionFacts.lifecycleLimitations(metadata ++ accounting.responseMetadata, accounting)
                  ),
                  accounting
                ))
              case Left(message) =>
                Consequence.argumentInvalid(s"AI record schema mismatch: $message")
            }
          case None =>
            Consequence.argumentInvalid("AI record normalization failed unexpectedly.")
        }
      case None =>
        Consequence.argumentInvalid("AI record response did not contain usable JSON.")
      }

  private final case class _RecordCandidate(
    mode: String,
    text: String,
    json: Option[Json] = None
  )

  private final case class _Accounted[A](
    response: A,
    accounting: AiAccountingFacts
  )

  private def _record_candidates(text: String): Vector[_RecordCandidate] =
    (Vector(_RecordCandidate("strict-json", text)) ++
      _fenced_record_candidates(text) ++
      _embedded_record_candidates(text))
      .map(candidate => candidate.copy(text = candidate.text.trim))
      .filter(_.text.nonEmpty)
      .foldLeft(Vector.empty[_RecordCandidate]) { (z, candidate) =>
        if (z.exists(_.text == candidate.text)) z else z :+ candidate
      }

  private def _fenced_record_candidates(text: String): Vector[_RecordCandidate] =
    _fenced_code_block.findAllMatchIn(text).flatMap { m =>
      val language = Option(m.group(1)).map(_.trim.toLowerCase(Locale.ROOT)).getOrElse("")
      val body = Option(m.group(2)).getOrElse("").trim
      if (language == "json" || (language.isEmpty && body.startsWith("{")))
        Some(_RecordCandidate(if (language == "json") "fenced-json" else "fenced", body))
      else
        None
    }.toVector

  private def _embedded_record_candidates(text: String): Vector[_RecordCandidate] =
    _balanced_json_objects(text).map(_RecordCandidate("embedded-json", _))

  private val _fenced_code_block =
    """(?is)```[ \t]*(json)?[ \t]*(?:\r?\n)?(.*?)```""".r

  private def _balanced_json_objects(text: String): Vector[String] = {
    val results = Vector.newBuilder[String]
    var start = -1
    var depth = 0
    var instring = false
    var escaped = false
    var i = 0
    while (i < text.length) {
      val c = text.charAt(i)
      if (instring) {
        if (escaped)
          escaped = false
        else if (c == '\\')
          escaped = true
        else if (c == '"')
          instring = false
      } else {
        c match {
          case '"' =>
            instring = true
          case '{' =>
            if (depth == 0)
              start = i
            depth += 1
          case '}' if depth > 0 =>
            depth -= 1
            if (depth == 0 && start >= 0) {
              results += text.substring(start, i + 1)
              start = -1
            }
          case _ =>
        }
      }
      i += 1
    }
    results.result()
  }

  private def _json_to_record(json: Json): Record =
    json.asObject
      .map(obj => Record.dataAuto(obj.toVector.map { case (key, value) => key -> _json_to_value(value) }*))
      .getOrElse(Record.dataAuto("value" -> _json_to_value(json)))

  private def _json_to_value(json: Json): Any =
    json.fold(
      jsonNull = null,
      jsonBoolean = identity,
      jsonNumber = n => n.toInt.getOrElse(n.toLong.getOrElse(n.toDouble)),
      jsonString = identity,
      jsonArray = _.map(_json_to_value).toVector,
      jsonObject = obj => Record.dataAuto(obj.toVector.map { case (key, value) => key -> _json_to_value(value) }*)
    )

  private def _validate_schema(
    record: Record,
    schema: Record
  ): Either[String, Unit] = {
    val missing = _schema_required(schema).filterNot(field => _has_field(record, field))
    if (missing.nonEmpty)
      Left(s"missing required fields: ${missing.mkString(",")}")
    else
      _schema_array_specs(schema).flatMap(spec => _array_schema_error(record, spec)).headOption match {
        case Some(message) => Left(message)
        case None => Right(())
      }
  }

  private final case class _ArraySchema(
    name: String,
    required: Vector[String]
  )

  private def _schema_array_specs(schema: Record): Vector[_ArraySchema] =
    _records(schema.getAny("arrays"))
      .flatMap { record =>
        _string(record.getAny("name"))
          .orElse(_string(record.getAny("field")))
          .map(name => _ArraySchema(name, _schema_required(record)))
      }

  private def _array_schema_error(
    record: Record,
    spec: _ArraySchema
  ): Option[String] =
    record.getAny(spec.name) match {
      case Some(values) =>
        _records(values).zipWithIndex.collectFirst {
          case (item, index) if spec.required.exists(field => !_has_field(item, field)) =>
            val missing = spec.required.filterNot(field => _has_field(item, field))
            s"${spec.name}[$index] missing required fields: ${missing.mkString(",")}"
        }
      case None =>
        Some(s"missing array field: ${spec.name}")
    }

  private def _schema_required(schema: Record): Vector[String] =
    _strings(schema.getAny("required"))

  private def _has_field(
    record: Record,
    field: String
  ): Boolean =
    record.getAny(field).exists {
      case null => false
      case s: String => s.trim.nonEmpty
      case xs: Seq[?] => xs.nonEmpty
      case _ => true
    }

  private def _records(value: Any): Vector[Record] =
    value match {
      case null => Vector.empty
      case r: Record => Vector(r)
      case Some(v) => _records(v)
      case xs: Vector[?] => xs.flatMap(_records)
      case xs: Seq[?] => xs.toVector.flatMap(_records)
      case xs: Array[?] => xs.toVector.flatMap(_records)
      case _ => Vector.empty
    }

  private def _strings(value: Any): Vector[String] =
    value match {
      case null => Vector.empty
      case r: Record => r.getAny("value").toVector.flatMap(_strings)
      case Some(v) => _strings(v)
      case xs: Vector[?] => xs.flatMap(_strings)
      case xs: Seq[?] => xs.toVector.flatMap(_strings)
      case xs: Array[?] => xs.toVector.flatMap(_strings)
      case s: String => s.split("[,\\s]+").toVector.map(_.trim).filter(_.nonEmpty)
      case other => Vector(other.toString)
    }

  private def _string(value: Any): Option[String] =
    _strings(value).headOption

  private def _effective_model(
    model: Option[String]
  ): Option[String] =
    model.orElse(selection.engine).orElse(selection.provider)

  private def _default_mode(provider: String): String =
    provider match {
      case "google" | "openai" | "anthropic" => "remote"
      case _ => "local"
    }

  private def _default_engine(provider: String): String =
    provider match {
      case "google" => "gemini"
      case "openai" => "gpt"
      case "anthropic" => "claude"
      case "codex" => "codex-cli"
      case _ => "ollama"
    }

  private def _canonical_provider(provider: String): String =
    provider.trim.toLowerCase(Locale.ROOT) match {
      case "codex-cli" => "codex"
      case value => value
    }

  private def _to_textus_message(
    message: AiMessage
  ): Message =
    Message(
      role = MessageRole.parse(message.role).getOrElse(MessageRole.User),
      content = message.content
    )

  private def _to_ai_message(
    message: Message
  ): AiMessage =
    AiMessage(
      role = message.role.toString.toLowerCase(java.util.Locale.ROOT),
      content = message.content
    )
}

final class TextusAiRunnerProvider(
  component: Component,
  defaultselection: SpiSelection = SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
  profiles: AiProfileConfig = AiProfileConfig.empty,
  concurrencystate: AiConcurrencyAdmissionState = new AiConcurrencyAdmissionState()
) extends SpiProvider[AiRunner] {
  private val _late_concurrency_admissions =
    new ConcurrentHashMap[ConcurrencyScopeId, ScopedConcurrencyAdmission]()

  private[runtime] def componentScope = component.scopeContext

  /*
   * Application purpose registrations may be bound after the runtime component
   * receives its scope. Preserve their declared limit instead of treating the
   * missing bootstrap admission as a provider failure.
   */
  private[runtime] def withConcurrencyAdmissionC[A](
    policy: AiPurposePolicy
  )(
    body: => Consequence[A]
  ): Consequence[A] =
    policy.concurrencyScope match {
      case Some(scope) =>
        component.scopeContext.scopedConcurrencyAdmissionOption match {
          case Some(admission) if concurrencystate.isBootstrapScope(scope) =>
            admission.withPermitC(scope)(body)
          case _ => _with_late_concurrency_admission_c(scope, policy.maxConcurrent)(body)
        }
      case None => body
    }

  private def _with_late_concurrency_admission_c[A](
    scope: ConcurrencyScopeId,
    limit: Option[Int]
  )(
    body: => Consequence[A]
  ): Consequence[A] =
    limit match {
      case Some(value) => _late_concurrency_admission_c(scope, value).flatMap(_.withPermitC(scope)(body))
      case None => body
    }

  private def _late_concurrency_admission_c(
    scope: ConcurrencyScopeId,
    limit: Int
  ): Consequence[ScopedConcurrencyAdmission] =
    Option(_late_concurrency_admissions.get(scope)) match {
      case Some(value) => Consequence.success(value)
      case None =>
        ScopedConcurrencyAdmission.createC(Vector(ConcurrencyGrant(scope, limit))).map { created =>
          Option(_late_concurrency_admissions.putIfAbsent(scope, created)).getOrElse(created)
        }
    }

  def supports(
    contract: SpiContract[AiRunner],
    selection: SpiSelection
  )(using ExecutionContext): Boolean =
    contract.name == "ai-runner" &&
      contract.runtimeClass == classOf[AiRunner] &&
      _is_success(generateService(selection)) &&
      _is_success(chatService(selection))

  def provide(
    contract: SpiContract[AiRunner],
    selection: SpiSelection
  )(using ExecutionContext): Consequence[AiRunner] = {
    val effective = _effective_selection(selection)
    for {
      generate <- generateService(effective)
      chat <- chatService(effective)
    } yield new TextusAiRunner(this, effective, profiles)
  }

  def generateService(
    selection: SpiSelection
  )(using ExecutionContext): Consequence[GenerateService] =
    component.binding("generate") match {
      case Some(binding: Component.Binding[?, ?]) =>
        binding
          .asInstanceOf[Component.Binding[GenerateRequirement, GenerateService]]
          .bind(_requirement(_effective_selection(selection)))
      case None =>
        Consequence.serviceUnavailable("generate binding not found")
    }

  def chatService(
    selection: SpiSelection
  )(using ExecutionContext): Consequence[ChatService] =
    component.binding("chat") match {
      case Some(binding: Component.Binding[?, ?]) =>
        binding
          .asInstanceOf[Component.Binding[GenerateRequirement, ChatService]]
          .bind(_requirement(_effective_selection(selection)))
      case None =>
        Consequence.serviceUnavailable("chat binding not found")
    }

  private def _effective_selection(
    selection: SpiSelection
  ): SpiSelection = {
    val provider = selection.provider.orElse(defaultselection.provider)
    val inheritsdefault =
      selection.provider.isEmpty ||
      selection.provider == defaultselection.provider
    val providername = provider.getOrElse("gemma")
    SpiSelection(
      provider = provider,
      mode = selection.mode.orElse(if (inheritsdefault) defaultselection.mode else Some(_default_mode(providername))),
      engine = selection.engine.orElse(if (inheritsdefault) defaultselection.engine else Some(_default_engine(providername)))
    )
  }

  private def _requirement(
    selection: SpiSelection
  ): GenerateRequirement = {
    val provider = selection.provider.getOrElse("gemma")
    GenerateRequirement(
      provider = Some(provider),
      mode = selection.mode.orElse(Some(_default_mode(provider))),
      engine = selection.engine.orElse(Some(_default_engine(provider)))
    )
  }

  private def _default_mode(provider: String): String =
    provider match {
      case "google" | "openai" | "anthropic" => "remote"
      case _ => "local"
    }

  private def _default_engine(provider: String): String =
    provider match {
      case "google" => "gemini"
      case "openai" => "gpt"
      case "anthropic" => "claude"
      case _ => "ollama"
    }

  private def _is_success[A](p: Consequence[A]): Boolean =
    p match {
      case Consequence.Success(_) => true
      case Consequence.Failure(_) => false
    }
}

private[ai] final class AiConcurrencyAdmissionState {
  @volatile private var _bootstrap_scopes: Set[ConcurrencyScopeId] = Set.empty

  private[ai] def registerBootstrap(scopes: Set[ConcurrencyScopeId]): Unit =
    _bootstrap_scopes = scopes

  private[ai] def isBootstrapScope(scope: ConcurrencyScopeId): Boolean =
    _bootstrap_scopes.contains(scope)
}
