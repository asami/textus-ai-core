package org.simplemodeling.textus.ai.provider.openai

import org.goldenport.Consequence
import org.goldenport.cncf.context.ExecutionContext
import org.simplemodeling.textus.ai.runtime.*
import scala.util.control.NonFatal

/** Host authority for one pinned OpenAI connection. Selection may read trusted
  * allowance state but must never dispatch inference. The same authority must
  * reserve the full bound atomically, against this connection, before any POST.
  *
  * @since Nov. 23, 2026
  */
trait OpenAiTaskAllowanceAuthority extends AiTaskAllowanceAuthority {
  def connection: OpenAiRuntimeConfig
  def assess(request: AiTaskRequest, deadlineNanos: Long): Option[AiTaskCandidate]
}

private[ai] final class OpenAiBoundedRecordBinding(
  authority: ExecutionContext => Option[OpenAiTaskAllowanceAuthority],
  override val limits: AiBoundedRecordLimits
) extends AiBoundedRecordBinding {
  override def prepare(request: AiTaskRequest, deadline: AiTaskDeadline)(using context: ExecutionContext)
      : Consequence[(Vector[AiTaskCandidate], AiTaskInvoker)] = {
    val selected = try {
      for {
        _ <- Option.when(deadline.remainingMillis > 0)(())
        verified <- authority(context)
        config = verified.connection
        if _valid_connection(config)
        post = new OpenAiManagedTaskPost(config, context)
        if post.isQualified
        if deadline.remainingMillis > 0
        candidate <- verified.assess(request, deadline.endNanos)
        if deadline.remainingMillis > 0 && candidate.verifiedForRequest == request &&
          candidate.provider == "openai" && candidate.model == config.model
      } yield candidate -> new OpenAiBoundedTaskInvoker(verified, post, 1024 * 1024, Some(deadline))
    } catch { case NonFatal(_) => None }
    selected match {
      case Some((candidate, invoker)) => Consequence.success(Vector(candidate) -> invoker)
      case None => Consequence.success(Vector.empty[AiTaskCandidate] -> OpenAiBoundedRecordBinding.Unavailable)
    }
  }

  private def _valid_connection(config: OpenAiRuntimeConfig): Boolean =
    config.provider == "openai" && config.mode == "remote" && config.engine == "gpt" &&
      config.endpoint.getScheme == "https" && config.endpoint.getHost != null &&
      config.endpoint.getRawUserInfo == null && config.endpoint.getRawQuery == null &&
      config.endpoint.getRawFragment == null && config.timeoutSeconds > 0 &&
      config.model.trim.nonEmpty && config.apiKey.trim.nonEmpty
}

private[ai] object OpenAiBoundedRecordBinding {
  private object Unavailable extends AiTaskInvoker {
    override def preflight(r: AiTaskRequest, c: AiTaskCandidate): Either[String, AiTaskInvocationCapability] =
      Left("OpenAI connection has no verified allowance for this request")

    override def invoke(r: AiTaskRequest, c: AiTaskCandidate): AiTaskDispatch =
      AiTaskDispatch.Declined("OpenAI connection has no verified allowance for this request",
        AiTaskReceipt(None, None, None, None, AiTaskUsage.Unknown))
  }
}
