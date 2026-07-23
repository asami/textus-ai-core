package org.simplemodeling.textus.ai.runtime

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import io.circe.Json
import io.circe.parser.parse
import org.goldenport.Consequence
import org.goldenport.cncf.admission.{ConcurrencyGrant, ConcurrencyScopeId, ScopedConcurrencyAdmission}
import org.goldenport.cncf.component.Component
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.mcp.client.{McpClientInvocation, McpClientService, McpClientSocket, McpServerSetId}
import org.goldenport.cncf.operationtool.{OperationToolInvocation, OperationToolService, OperationToolSetId, OperationToolSocket}
import org.goldenport.cncf.spi.{SpiContract, SpiProvider, SpiSelection}
import org.goldenport.cncf.spi.ai.runner.*
import org.goldenport.protocol.{Property, Request}
import org.goldenport.protocol.operation.OperationResponse
import org.goldenport.record.Record
import org.slf4j.LoggerFactory
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
 * @version Jul. 23, 2026
 * @author  ASAMI, Tomoharu
 */
final class TextusAiRunner(
  provider: TextusAiRunnerProvider,
  selection: SpiSelection,
  profiles: AiProfileConfig = AiProfileConfig.empty
) extends AiRunner {
  private val _log = LoggerFactory.getLogger(classOf[TextusAiRunner])

  def generate(req: AiGenerateRequest)(using ExecutionContext): Consequence[AiGenerateResponse] =
    _effective_resolution(req.requirement).flatMap { resolution =>
      val maxtokens = resolution.maxTokens(req.maxTokens)
      val inputestimate = AiInputTokenEstimator.generate(req.prompt)
      val primaryproperties = _request_properties(req.properties, resolution)
      val policymetadata = resolution.executionMetadata(
        maxtokens,
        primaryproperties,
        inputEstimate = Some(inputestimate)
      )
      _with_generate_calltree(req, resolution.requirement, maxtokens, policymetadata) {
        for {
          _ <- _validate_external_cncf_evidence_strategy(req, resolution)
          _ <- resolution.policy.validateInputBudget(inputestimate)
          costadmission <- if (resolution.operationalStrategy.isEmpty)
            resolution.costAdmissionC(inputestimate, maxtokens)
          else
            Consequence.success(None)
          _ <- if (resolution.operationalStrategy.isEmpty)
            resolution.policy.validateCostBudget(costadmission)
          else
            Consequence.unit
          _ <- resolution.policy.validateGenerate(primaryproperties)
          response <- _with_concurrency_admission_c(resolution) {
            resolution.operationalStrategy match {
              case Some(strategy) =>
                _generate_with_operational_strategy(
                  req,
                  resolution,
                  strategy,
                  inputestimate
                )
              case None =>
                _generate_once(
                  req,
                  resolution,
                  req.prompt,
                  policymetadata,
                  inputestimate,
                  costadmission
                )
            }
          }
        } yield response
      }
    }

  /* Application-composed CNCF evidence must never re-enter a runner-owned tool loop. */
  private def _validate_external_cncf_evidence_strategy(
    req: AiGenerateRequest,
    resolution: AiProfileResolution
  ): Consequence[Unit] =
    if (
      req.metadata.get("cncf.evidence.composed").contains("true") &&
      resolution.operationalStrategy.exists { strategy =>
        strategy.primary.mcpServerSet.nonEmpty || strategy.primary.operationToolSet.nonEmpty
      }
    )
      Consequence.configurationInvalid(
        "Application-composed CNCF evidence cannot use a Textus AI Operation/MCP tool strategy."
      )
    else
      Consequence.unit

  private def _generate_once(
    req: AiGenerateRequest,
    resolution: AiProfileResolution,
    prompt: String,
    policyMetadata: Map[String, String],
    inputEstimate: AiInputTokenEstimate,
    costAdmission: Option[AiCostAdmission]
  )(using ExecutionContext): Consequence[_Accounted[AiGenerateResponse]] =
    _generate_raw(req, resolution, prompt).map { response =>
      _to_ai_generate_response(
        req,
        response,
        resolution.requirement,
        policyMetadata,
        inputEstimate,
        resolution,
        costAdmission
      )
    }

  private def _generate_raw(
    req: AiGenerateRequest,
    resolution: AiProfileResolution,
    prompt: String
  )(using ExecutionContext): Consequence[GenerateResponse] = {
    val effective = _effective_selection(resolution.requirement)
    val properties = _request_properties(req.properties, resolution)
    for {
      _ <- AiProviderAdmission.validate(effective, properties)
      service <- provider.generateService(effective)
      response <- service.generate(GenerateRequest(
        prompt = prompt,
        temperature = req.temperature,
        maxTokens = resolution.maxTokens(req.maxTokens),
        properties = properties
      ))
      _ <- AiExecutionFacts.validateMaxOutputTokens(
        effective,
        resolution.maxTokens(req.maxTokens),
        response.metadata
      )
      _ <- AiExecutionFacts.validateMaxReasoningTokens(
        effective,
        resolution.policy.maxReasoningTokens,
        response.metadata
      )
    } yield response
  }

  private def _generate_raw_attempt(
    req: AiGenerateRequest,
    resolution: AiProfileResolution,
    prompt: String
  )(using ExecutionContext): Either[(org.goldenport.Conclusion, AiAttemptFailureClass), GenerateResponse] = {
    val effective = _effective_selection(resolution.requirement)
    val properties = _request_properties(req.properties, resolution)
    AiProviderAdmission.validate(effective, properties) match {
      case Consequence.Failure(conclusion) =>
        Left(conclusion -> AiAttemptFailureClass.Capability)
      case Consequence.Success(_) =>
        provider.generateService(effective) match {
          case Consequence.Failure(conclusion) =>
            Left(conclusion -> AiAttemptFailureClass.Capability)
          case Consequence.Success(service) =>
            service.generate(GenerateRequest(
              prompt = prompt,
              temperature = req.temperature,
              maxTokens = resolution.maxTokens(req.maxTokens),
              properties = properties
            )) match {
              case Consequence.Failure(conclusion) =>
                Left(conclusion -> AiAttemptFailureClass.fromConclusion(conclusion))
              case Consequence.Success(response) =>
                val limits = for {
                  _ <- AiExecutionFacts.validateMaxOutputTokens(
                    effective,
                    resolution.maxTokens(req.maxTokens),
                    response.metadata
                  )
                  _ <- AiExecutionFacts.validateMaxReasoningTokens(
                    effective,
                    resolution.policy.maxReasoningTokens,
                    response.metadata
                  )
                } yield ()
                limits match {
                  case Consequence.Success(_) => Right(response)
                  case Consequence.Failure(conclusion) =>
                    Left(conclusion -> AiAttemptFailureClass.ResourceLimit)
                }
            }
        }
    }
  }

  private def _generate_with_operational_strategy(
    req: AiGenerateRequest,
    resolution: AiProfileResolution,
    strategy: AiOperationalStrategy,
    inputEstimate: AiInputTokenEstimate
  )(using ExecutionContext): Consequence[_Accounted[AiGenerateResponse]] = {
    val started = System.nanoTime()
    var attempts = Vector.empty[AiAttemptFact]
    var attemptindex = 0
    var repairs = 0
    var escalationreason: Option[String] = None
    var lastfailure = AiAttemptFailureClass.Unknown

    def _invoke_(
      execution: AiRuntimeExecution,
      stage: String,
      prompt: String,
      rateschedule: Option[AiRateSchedule] = resolution.rateSchedule
    ): Consequence[(GenerateResponse, AiProfileResolution, Option[AiCostAdmission])] = {
      attemptindex += 1
      val executionresolution = resolution.forExecution(execution).copy(rateSchedule = rateschedule)
      val admission = for {
        value <- executionresolution.costAdmissionC(
          inputEstimate,
          executionresolution.maxTokens(req.maxTokens)
        )
        _ <- executionresolution.policy.validateCostBudget(value)
      } yield value
      admission match {
        case Consequence.Failure(conclusion) =>
          lastfailure = AiAttemptFailureClass.Admission
          attempts = attempts :+ AiAttemptFact(attemptindex, execution.provider, stage, "failure")
          Consequence.Failure(conclusion)
        case Consequence.Success(costadmission) => _generate_raw_attempt(req, executionresolution, prompt) match {
          case Right(response) =>
            attempts = attempts :+ AiAttemptFact(attemptindex, execution.provider, stage, "success")
            Consequence.success((response, executionresolution, costadmission))
          case Left((conclusion, failure)) =>
            lastfailure = failure
            val exceptionclass = conclusion.getException
              .map(_.getClass.getName)
              .getOrElse("none")
            val safelog =
              s"AI provider failure: provider=${execution.provider} class=${failure.id} " +
                s"reason=${AiAttemptFailureClass.safeReason(conclusion)} " +
                s"status=${conclusion.status.webCode.code} exception=$exceptionclass"
            if (failure == AiAttemptFailureClass.Unknown)
              _log.warn(safelog)
            else
              _log.debug(safelog)
            attempts = attempts :+ AiAttemptFact(attemptindex, execution.provider, stage, "failure")
            Consequence.Failure(conclusion)
        }
      }
    }

    def _primary_candidate_(
      prompt: String
    ): Consequence[(GenerateResponse, AiProfileResolution, Option[AiCostAdmission])] =
      if (repairs > 0)
        _invoke_(strategy.primary, "repair", prompt)
      else
        strategy.kind match {
          case AiOperationalStrategyKind.Decomposed =>
            _invoke_(
              strategy.primary,
              "decompose",
              s"Decompose the following task into a concise execution plan. Do not answer the task yet.\n\n$prompt"
            ).flatMap { case (plan, _, _) =>
              _invoke_(
                strategy.primary,
                "compose",
                s"Complete the original task using the plan below. Return only the requested final artifact.\n\n" +
                  s"Plan:\n${plan.text}\n\nOriginal task:\n$prompt"
              )
            }
          case AiOperationalStrategyKind.CandidateRanking =>
            for {
              first <- _invoke_(strategy.primary, "candidate-1", prompt)
              second <- _invoke_(strategy.primary, "candidate-2", prompt)
              ranked <- _invoke_(
                strategy.primary,
                "rank",
                s"Choose and improve the better candidate for the original task. Return only the final artifact.\n\n" +
                  s"Candidate 1:\n${first._1.text}\n\nCandidate 2:\n${second._1.text}\n\nOriginal task:\n$prompt"
              )
            } yield ranked
          case AiOperationalStrategyKind.ToolGrounded
              if strategy.primary.mcpServerSet.nonEmpty || strategy.primary.operationToolSet.nonEmpty =>
            _invoke_tool_grounded_(strategy.primary, "tool-grounded", prompt)
          case AiOperationalStrategyKind.ToolGrounded
              if strategy.primary.tools.isEmpty =>
            Consequence.configurationInvalid(
              "Tool-grounded AI strategy requires a runtime-owned Operation tool set or MCP server set."
            )
          case AiOperationalStrategyKind.PromptGrounded
              if strategy.primary.mcpServerSet.nonEmpty || strategy.primary.operationToolSet.nonEmpty =>
            _invoke_prompt_grounded_(strategy.primary, "prompt-grounded", prompt)
          case AiOperationalStrategyKind.PromptGrounded =>
            Consequence.configurationInvalid(
              "Prompt-grounded AI strategy requires a runtime-owned Operation tool set or MCP server set."
            )
          case _ =>
            _invoke_(strategy.primary, "initial", prompt)
        }

    def _invoke_tool_grounded_(
      execution: AiRuntimeExecution,
      stage: String,
      prompt: String
    ): Consequence[(GenerateResponse, AiProfileResolution, Option[AiCostAdmission])] = {
      attemptindex += 1
      val executionresolution = resolution.forExecution(execution)
      val admission = for {
        aggregateoutput <- ToolOrchestrator.admissionMaxOutputTokensC(
          executionresolution.maxTokens(req.maxTokens)
        )
        value <- executionresolution.costAdmissionC(
          ToolOrchestrator.admissionInputEstimate(
            inputEstimate,
            executionresolution.maxTokens(req.maxTokens)
          ),
          aggregateoutput
        )
        _ <- executionresolution.policy.validateCostBudget(value)
      } yield value
      admission match {
        case Consequence.Failure(conclusion) =>
          lastfailure = AiAttemptFailureClass.Admission
          attempts = attempts :+ AiAttemptFact(attemptindex, execution.provider, stage, "failure")
          Consequence.Failure(conclusion)
        case Consequence.Success(costadmission) =>
          (execution.mcpServerSet, execution.operationToolSet) match {
            case (None, None) =>
              lastfailure = AiAttemptFailureClass.Capability
              attempts = attempts :+ AiAttemptFact(attemptindex, execution.provider, stage, "failure")
              Consequence.configurationInvalid("Tool-grounded execution has no admitted tool set")
            case (mcpserverset, operationtoolset) =>
              provider.generateWithToolsC(
                _effective_selection(executionresolution.requirement),
                mcpserverset,
                operationtoolset,
                prompt,
                req.temperature,
                executionresolution.maxTokens(req.maxTokens),
                _request_properties(req.properties, executionresolution)
              ) match {
                case Consequence.Success(response) =>
                  attempts = attempts :+ AiAttemptFact(attemptindex, execution.provider, stage, "success")
                  Consequence.success((response, executionresolution, costadmission))
                case Consequence.Failure(conclusion) =>
                  lastfailure = AiAttemptFailureClass.fromConclusion(conclusion)
                  attempts = attempts :+ AiAttemptFact(attemptindex, execution.provider, stage, "failure")
                  Consequence.Failure(conclusion)
              }
          }
      }
    }

    def _invoke_prompt_grounded_(
      execution: AiRuntimeExecution,
      stage: String,
      prompt: String
    ): Consequence[(GenerateResponse, AiProfileResolution, Option[AiCostAdmission])] = {
      attemptindex += 1
      val executionresolution = resolution.forExecution(execution)
      val admission = for {
        aggregateoutput <- ToolOrchestrator.admissionMaxOutputTokensC(
          executionresolution.maxTokens(req.maxTokens)
        )
        value <- executionresolution.costAdmissionC(
          ToolOrchestrator.admissionInputEstimate(
            inputEstimate,
            executionresolution.maxTokens(req.maxTokens)
          ),
          aggregateoutput
        )
        _ <- executionresolution.policy.validateCostBudget(value)
      } yield value
      admission match {
        case Consequence.Failure(conclusion) =>
          lastfailure = AiAttemptFailureClass.Admission
          attempts = attempts :+ AiAttemptFact(attemptindex, execution.provider, stage, "failure")
          Consequence.Failure(conclusion)
        case Consequence.Success(costadmission) =>
          provider.generateWithPromptLoopC(
            _effective_selection(executionresolution.requirement),
            execution.mcpServerSet,
            execution.operationToolSet,
            prompt,
            req.temperature,
            executionresolution.maxTokens(req.maxTokens),
            _request_properties(req.properties, executionresolution)
          ) match {
            case Consequence.Success(response) =>
              attempts = attempts :+ AiAttemptFact(attemptindex, execution.provider, stage, "success")
              Consequence.success((response, executionresolution, costadmission))
            case Consequence.Failure(conclusion) =>
              lastfailure = AiAttemptFailureClass.fromConclusion(conclusion)
              attempts = attempts :+ AiAttemptFact(attemptindex, execution.provider, stage, "failure")
              Consequence.Failure(conclusion)
          }
      }
    }

    def _finish_(
      response: GenerateResponse,
      finalresolution: AiProfileResolution,
      costadmission: Option[AiCostAdmission]
    ): Consequence[_Accounted[AiGenerateResponse]] = {
      val facts = AiStrategyFacts(
        attempts,
        repairs,
        escalationreason,
        finalresolution.requirement.provider.getOrElse(""),
        (System.nanoTime() - started) / 1000000L
      )
      val finalpolicymetadata = finalresolution.executionMetadata(
        finalresolution.maxTokens(req.maxTokens),
        _request_properties(req.properties, finalresolution),
        inputEstimate = Some(inputEstimate)
      )
      Consequence.success(_to_ai_generate_response(
        req,
        response,
        finalresolution.requirement,
        finalpolicymetadata ++ facts.metadata,
        inputEstimate,
        finalresolution,
        costadmission
      ))
    }

    def _fallback_(
      failure: AiAttemptFailureClass
    ): Consequence[_Accounted[AiGenerateResponse]] = {
      escalationreason = Some(failure.id)
      strategy.fallbackFor(failure) match {
        case None =>
          Consequence.operationIllegal(
            "ai.operational-strategy",
            s"AI strategy terminated without admitted fallback: ${failure.id}"
          )
        case Some(execution) =>
          _invoke_(
            execution,
            "commercial-fallback",
            req.prompt,
            strategy.commercialFallbackRateSchedule
          ) match {
            case Consequence.Failure(_) => Consequence.operationIllegal(
              "ai.operational-strategy",
              s"Commercial fallback execution failed: ${lastfailure.id}"
            )
            case Consequence.Success((response, finalresolution, costadmission)) =>
              provider.evaluateCandidateC(
                strategy.acceptanceOperation,
                resolution.applicationPurpose,
                response.text,
                repairs,
                strategy.maxRepairs
              ).flatMap {
                case acceptance if Set("accept", "confirm").contains(acceptance.decision) =>
                  _finish_(response, finalresolution, costadmission)
                case acceptance =>
                  Consequence.operationIllegal(
                    "ai.operational-strategy",
                    s"Commercial fallback failed acceptance: ${acceptance.decision}"
                  )
              }
          }
      }
    }

    def _run_(prompt: String): Consequence[_Accounted[AiGenerateResponse]] =
      _primary_candidate_(prompt) match {
        case Consequence.Failure(conclusion) =>
          val failureclass = lastfailure
          strategy.fallbackFor(failureclass) match {
            case Some(_) => _fallback_(failureclass)
            case None => Consequence.Failure(conclusion)
          }
        case Consequence.Success((response, finalresolution, costadmission)) =>
          provider.evaluateCandidateC(
            strategy.acceptanceOperation,
            resolution.applicationPurpose,
            response.text,
            repairs,
            strategy.maxRepairs
          ).flatMap {
            case acceptance if acceptance.decision == "accept" =>
              _finish_(response, finalresolution, costadmission)
            case acceptance if acceptance.decision == "repair" && repairs < strategy.maxRepairs =>
              repairs += 1
              _run_(_repair_prompt(req.prompt, response.text, acceptance))
            case acceptance if acceptance.decision == "confirm" =>
              _fallback_(AiAttemptFailureClass.Ambiguity)
            case acceptance if acceptance.decision == "escalate" =>
              _fallback_(AiAttemptFailureClass.fromEscalationReason(
                acceptance.escalationReason.getOrElse("")
              ))
            case acceptance if acceptance.decision == "repair" =>
              _fallback_(AiAttemptFailureClass.DomainValidation)
            case acceptance if acceptance.decision == "reject" =>
              Consequence.operationIllegal(
                "ai.operational-strategy",
                "AI candidate was rejected by the application acceptance policy."
              )
            case acceptance =>
              Consequence.configurationInvalid(
                s"Unsupported AI acceptance decision: ${acceptance.decision}"
              )
          }
      }

    _run_(req.prompt)
  }

  private def _repair_prompt(
    originalPrompt: String,
    candidate: String,
    acceptance: AiCandidateAcceptance
  ): String =
    s"Repair the candidate using only the validation diagnostics and allowed paths below. " +
      s"Return only the corrected final artifact.\n\nOriginal task:\n$originalPrompt\n\n" +
      s"Candidate:\n$candidate\n\nDiagnostics:\n${acceptance.diagnostics}\n\n" +
      s"Allowed repair paths:\n${acceptance.allowedRepairPaths}"

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
    val responsemetadata = AiExecutionFacts.lifecycleLimitations(metadata ++ accounting.responseMetadata, accounting)
    val result = AiGenerateResponse(
      response.text,
      _effective_model(response.model),
      responsemetadata
    )
    _Accounted(result.copy(metadata = result.metadata ++ AiExecutionObservation.runtimeFacts(result)), accounting)
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
    val responsemetadata = AiExecutionFacts.lifecycleLimitations(metadata ++ accounting.responseMetadata, accounting)
    val result = AiChatResponse(
      _to_ai_message(response.message),
      _effective_model(response.model),
      responsemetadata
    )
    _Accounted(result.copy(metadata = result.metadata ++ AiExecutionObservation.runtimeFacts(
      AiGenerateResponse(result.message.content, result.model, result.metadata)
    )), accounting)
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

class TextusAiRunnerProvider(
  component: Component,
  defaultselection: SpiSelection = SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
  profiles: AiProfileConfig = AiProfileConfig.empty,
  concurrencystate: AiConcurrencyAdmissionState = new AiConcurrencyAdmissionState(),
  assembledSubsystem: Option[org.goldenport.cncf.subsystem.Subsystem] = None
) extends SpiProvider[AiRunner] {
  private val _late_concurrency_admissions =
    new ConcurrentHashMap[ConcurrencyScopeId, ScopedConcurrencyAdmission]()

  private[runtime] def componentScope = component.scopeContext

  private[runtime] def evaluateCandidateC(
    operation: Option[String],
    purpose: Option[String],
    candidate: String,
    repairCount: Int,
    maxRepairs: Int
  ): Consequence[AiCandidateAcceptance] =
    operation match {
      case None =>
        Consequence.success(AiCandidateAcceptance("accept", "", "", None))
      case Some(identity) =>
        identity.split("\\.", 3).toVector match {
          case Vector(componentname, service, operationname) =>
            val response = assembledSubsystem.orElse(component.subsystem)
              .map(_.executeOperationResponse(Request.of(
                component = componentname,
                service = service,
                operation = operationname,
                properties = List(
                  Property("purpose", purpose.getOrElse(""), None),
                  Property("candidate", candidate, None),
                  Property("candidateFormat", "yaml", None),
                  Property("repairCount", repairCount.toString, None),
                  Property("maxRepairs", maxRepairs.toString, None)
                )
              )))
              .getOrElse(Consequence.serviceUnavailable(
                "AI acceptance operation requires an assembled subsystem."
              ))
              .recoverWith { _ =>
                Consequence.operationIllegal(
                  "ai.acceptance-operation",
                  "Application acceptance operation invocation failed."
                )
              }
            response.flatMap {
                case OperationResponse.RecordResponse(record) =>
                  record.getString("decision").map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty) match {
                    case Some(decision) => Consequence.success(AiCandidateAcceptance(
                      decision,
                      record.getString("diagnostics").getOrElse(""),
                      record.getString("allowedRepairPaths").getOrElse(""),
                      record.getString("escalationReason").map(_.trim).filter(_.nonEmpty)
                    ))
                    case None => Consequence.configurationInvalid(
                      "Application acceptance operation response has no decision."
                    )
                  }
                case other => Consequence.configurationInvalid(
                  "Application acceptance operation returned a non-record response."
                )
              }
          case _ => Consequence.configurationInvalid(
            s"AI acceptance operation must be Component.Service.operation: $identity"
          )
        }
    }

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

  private[runtime] def generateWithMcpToolsC(
    selection: SpiSelection,
    serverSet: McpServerSetId,
    prompt: String,
    temperature: Option[Double],
    maxTokens: Option[Int],
    properties: Vector[Property]
  )(using ExecutionContext): Consequence[GenerateResponse] =
    generateWithToolsC(selection, Some(serverSet), None, prompt, temperature, maxTokens, properties)

  private[runtime] def generateWithToolsC(
    selection: SpiSelection,
    mcpServerSet: Option[McpServerSetId],
    operationToolSet: Option[OperationToolSetId],
    prompt: String,
    temperature: Option[Double],
    maxTokens: Option[Int],
    properties: Vector[Property]
  )(using ExecutionContext): Consequence[GenerateResponse] =
    for {
      effective <- Consequence.success(_effective_selection(selection))
      _ <- AiProviderAdmission.validate(effective, properties)
      mcpservice <- _mcp_service_c(mcpServerSet)
      operationservice <- _operation_tool_service_c(operationToolSet)
      chat <- chatService(effective)
      toolservice <- chat match {
        case value: ToolCallingChatService => Consequence.success(value)
        case _ => Consequence.configurationInvalid(
          "Selected AI provider does not support the runtime-owned tool function protocol"
        )
      }
      response <- _with_tool_invocations_c(mcpservice, operationservice) { (mcpinvocation, operationinvocation) =>
        ToolOrchestrator.generateC(
          toolservice,
          mcpinvocation,
          operationinvocation,
          prompt,
          temperature,
          maxTokens,
          properties,
          effective.provider.map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty).getOrElse("ai")
        )
      }
      aggregateoutput <- ToolOrchestrator.admissionMaxOutputTokensC(maxTokens)
      _ <- AiExecutionFacts.validateMaxOutputTokens(effective, aggregateoutput, response.metadata)
    } yield response

  private[runtime] def generateWithPromptLoopC(
    selection: SpiSelection,
    mcpServerSet: Option[McpServerSetId],
    operationToolSet: Option[OperationToolSetId],
    prompt: String,
    temperature: Option[Double],
    maxTokens: Option[Int],
    properties: Vector[Property]
  )(using ExecutionContext): Consequence[GenerateResponse] =
    for {
      effective <- Consequence.success(_effective_selection(selection))
      _ <- AiProviderAdmission.validate(effective, properties)
      mcpservice <- _mcp_service_c(mcpServerSet)
      operationservice <- _operation_tool_service_c(operationToolSet)
      generate <- generateService(effective)
      response <- _with_tool_invocations_c(mcpservice, operationservice) { (mcpinvocation, operationinvocation) =>
        ToolOrchestrator.generatePromptLoopC(
          generate,
          mcpinvocation,
          operationinvocation,
          prompt,
          temperature,
          maxTokens,
          properties,
          effective.provider.map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty).getOrElse("ai")
        )
      }
      aggregateoutput <- ToolOrchestrator.admissionMaxOutputTokensC(maxTokens)
      _ <- AiExecutionFacts.validateMaxOutputTokens(effective, aggregateoutput, response.metadata)
    } yield response

  private def _mcp_service_c(
    serverSet: Option[McpServerSetId]
  ): Consequence[Option[McpClientService]] = serverSet match {
    case None => Consequence.success(None)
    case Some(value) =>
      component.port.get[McpClientSocket].map(Consequence.success).getOrElse(
        Consequence.serviceUnavailable("Textus AI MCP client socket is not installed")
      ).flatMap(_.service(value)).map(Some(_))
  }

  private def _operation_tool_service_c(
    toolSet: Option[OperationToolSetId]
  ): Consequence[Option[OperationToolService]] = toolSet match {
    case None => Consequence.success(None)
    case Some(value) =>
      component.port.get[OperationToolSocket].map(Consequence.success).getOrElse(
        Consequence.serviceUnavailable("Textus AI Operation tool socket is not installed")
      ).flatMap(_.service(value)).map(Some(_))
  }

  private def _with_tool_invocations_c[A](
    mcpservice: Option[McpClientService],
    operationservice: Option[OperationToolService]
  )(
    body: (Option[McpClientInvocation], Option[OperationToolInvocation]) => Consequence[A]
  )(using ExecutionContext): Consequence[A] = (mcpservice, operationservice) match {
    case (Some(mcp), Some(operation)) =>
      mcp.withInvocation { mcpinvocation =>
        operation.withInvocation { operationinvocation =>
          body(Some(mcpinvocation), Some(operationinvocation))
        }
      }
    case (Some(mcp), None) => mcp.withInvocation(invocation => body(Some(invocation), None))
    case (None, Some(operation)) => operation.withInvocation(invocation => body(None, Some(invocation)))
    case (None, None) => Consequence.configurationInvalid("Tool-grounded execution has no admitted tool service")
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
