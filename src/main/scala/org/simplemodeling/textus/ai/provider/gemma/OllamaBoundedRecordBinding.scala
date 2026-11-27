package org.simplemodeling.textus.ai.provider.gemma

import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import io.circe.{HCursor, Json}
import io.circe.parser.parse
import org.goldenport.Consequence
import org.goldenport.cncf.context.ExecutionContext
import org.simplemodeling.textus.ai.runtime.*
import scala.util.control.NonFatal

/** Bounded structured tasks on a host-registered native Ollama daemon.
  *
  * @since Nov. 23, 2026
  */
private[ai] final class OllamaBoundedRecordBinding(config: GemmaRuntimeConfig)
    extends AiBoundedRecordBinding {
  require(OllamaBoundedRecordBinding.isNativeLoopback(config))

  override val limits = AiBoundedRecordLimits(config.timeoutSeconds * 1000L, 0, 65536)

  override def prepare(request: AiTaskRequest, deadline: AiTaskDeadline)(using context: ExecutionContext)
      : Consequence[(Vector[AiTaskCandidate], AiTaskInvoker)] = {
    // The admission unit here is one local invocation. Token usage comes from Ollama.
    val candidate = AiTaskCandidate("ollama", config.model, Set("structured-record"), Set.empty,
      Some(1L), 1L, request, Some(0L))
    val post = new AiTaskManagedHttpPost(config.endpoint, "/api/chat", Vector.empty, context)
    Consequence.success(Vector(candidate) -> new OllamaBoundedTaskInvoker(candidate, post, limits, deadline))
  }
}

private[ai] object OllamaBoundedRecordBinding {
  def isNativeLoopback(config: GemmaRuntimeConfig): Boolean =
    config.mode == "local" && config.engine == "ollama" && config.runtime == "native" &&
      config.bootstrap.isEmpty && config.fallbackEndpoint.isEmpty && config.configurationError.isEmpty &&
      config.model.trim.nonEmpty && config.timeoutSeconds > 0 && config.timeoutSeconds <= 3600 &&
      _is_loopback_origin(config.endpoint)

  private def _is_loopback_origin(origin: URI): Boolean =
    origin.getScheme == "http" && origin.getHost == "127.0.0.1" &&
      origin.getPort >= 1 && origin.getPort <= 65535 &&
      origin.getRawUserInfo == null && origin.getRawQuery == null && origin.getRawFragment == null &&
      Set("", "/").contains(Option(origin.getRawPath).getOrElse(""))
}

private final class OllamaBoundedTaskInvoker(
  selected: AiTaskCandidate,
  transport: AiTaskManagedPost,
  limits: AiBoundedRecordLimits,
  deadline: AiTaskDeadline
) extends AiTaskInvoker {
  private val used = new AtomicBoolean()
  private val maxInputCharacters = 100000
  private val maxOutputTokens = 2048
  private val maxResponseBytes = 1024 * 1024
  private val unknown = AiTaskReceipt(None, None, None, None, AiTaskUsage.Unknown)

  override def preflight(request: AiTaskRequest, candidate: AiTaskCandidate): Either[String, AiTaskInvocationCapability] =
    _schema(request, candidate).map { _ =>
      AiTaskInvocationCapability(request, candidate, maxInputCharacters, request.maxOutputCharacters,
        0, request.maxLatencyMillis, exactModel = true, singleAttempt = true, endToEndDeadline = true)
    }

  override private[ai] def dispatchAfterPreflight(request: AiTaskRequest, candidate: AiTaskCandidate,
      inherited: AiTaskDeadline): AiTaskDispatch = invoke(request, candidate)

  override def invoke(request: AiTaskRequest, candidate: AiTaskCandidate): AiTaskDispatch = {
    if (!used.compareAndSet(false, true))
      return AiTaskDispatch.Uncertain("Local inference invoker already consumed", unknown)
    val schema = _schema(request, candidate) match {
      case Right(value) => value
      case Left(reason) => return AiTaskDispatch.Declined(reason, unknown)
    }
    val body = Json.obj(
      "model" -> Json.fromString(candidate.model),
      "messages" -> Json.arr(Json.obj(
        "role" -> Json.fromString("user"),
        "content" -> Json.fromString(request.input.content)
      )),
      "format" -> schema,
      "stream" -> Json.False,
      "options" -> Json.obj("num_predict" -> Json.fromInt(maxOutputTokens))
    )
    val timeout = deadline.remainingMillis
    if (timeout <= 0) return AiTaskDispatch.Declined("Deadline expired before local dispatch", unknown)
    if (!deadline.beginDispatch(unknown)) return AiTaskDispatch.Declined("Deadline expired before local dispatch", unknown)
    val response = try transport.postObserved(body, timeout, maxResponseBytes) catch {
      case NonFatal(_) => AiTaskManagedPostResult.PossiblyDispatched
    }
    response match {
      case AiTaskManagedPostResult.NotDispatched => AiTaskDispatch.Declined("Local managed HTTP admission declined", unknown)
      case AiTaskManagedPostResult.PossiblyDispatched => AiTaskDispatch.Uncertain("Local managed HTTP outcome is uncertain", unknown)
      case AiTaskManagedPostResult.Response(status, requestId, raw) =>
        deadline.observeReceipt(AiTaskReceipt(Some("ollama"), None, requestId, None, AiTaskUsage.Unknown))
        val cursor = parse(raw).toOption.map(_.hcursor)
        val receipt = AiTaskReceipt(Some("ollama"), cursor.flatMap(_.get[String]("model").toOption),
          requestId, None, cursor.map(_usage).getOrElse(AiTaskUsage.Unknown))
        deadline.observeReceipt(receipt)
        if (status / 100 != 2) AiTaskDispatch.Failed(s"Local provider returned HTTP $status", receipt)
        else if (!receipt.actualModel.contains(candidate.model))
          AiTaskDispatch.Failed("Local provider model missing or different", receipt)
        else {
          val result = for {
            decoded <- cursor.toRight("Invalid local provider response")
            _ <- Either.cond(decoded.get[Boolean]("done").contains(true), (), "Local inference did not finish")
            content <- decoded.downField("message").get[String]("content").toOption
              .filter(_.length <= request.maxOutputCharacters).toRight("Missing or oversized local output")
            json <- parse(content).left.map(_ => "Invalid local output JSON")
            record <- TextusAiRecordProjection.project(json, schema)
          } yield record
          if (deadline.remainingMillis <= 0) AiTaskDispatch.Uncertain("Deadline expired after local dispatch", receipt)
          else result.fold(AiTaskDispatch.Failed(_, receipt), AiTaskDispatch.Completed(_, receipt))
        }
    }
  }

  private def _schema(request: AiTaskRequest, candidate: AiTaskCandidate): Either[String, Json] =
    for {
      _ <- Either.cond(candidate == selected && candidate.verifiedForRequest == request &&
        request.requiredTools.isEmpty && request.maxLatencyMillis <= limits.maxLatencyMillis &&
        request.maxOutputCharacters <= limits.maxOutputCharacters &&
        request.input.content.length <= maxInputCharacters && request.outputSchema.length <= maxInputCharacters &&
        transport.isQualified && deadline.remainingMillis > 0, (), "Local bounded model or request is unavailable")
      schema <- parse(request.outputSchema).toOption.filter(_.isObject).toRight("Invalid local output schema")
    } yield schema

  private def _usage(cursor: HCursor): AiTaskUsage =
    val count = for {
      input <- cursor.get[Long]("prompt_eval_count").toOption.filter(_ >= 0)
      output <- cursor.get[Long]("eval_count").toOption.filter(value => value >= 0 && value <= Long.MaxValue - input)
    } yield input + output
    count.map(AiTaskUsage.Measured(_, Some(0L))).getOrElse(AiTaskUsage.Unknown)
}
