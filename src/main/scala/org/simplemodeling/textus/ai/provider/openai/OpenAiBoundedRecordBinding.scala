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
  override val limits: AiBoundedRecordLimits,
  groups: Option[() => Option[(OpenAiRuntimeConfig, Vector[OpenAiLedgerBackedAllowance.Group], Int,
    OpenAiGroupAllocationVerifier, Option[OpenAiRequestSizingVerifier])]] = None
) extends AiBoundedRecordBinding {
  override def prepare(request: AiTaskRequest, deadline: AiTaskDeadline)(using context: ExecutionContext)
      : Consequence[(Vector[AiTaskCandidate], AiTaskInvoker)] = {
    if (groups.nonEmpty) {
      val resolved = try groups.get.apply() catch { case NonFatal(_) => None }
      return resolved match {
        case Some(config) => _prepare_groups(request, deadline, config)
        case None => Consequence.success(Vector.empty[AiTaskCandidate] -> OpenAiBoundedRecordBinding.Unavailable)
      }
    }
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

  private def _prepare_groups(request: AiTaskRequest, deadline: AiTaskDeadline,
      config: (OpenAiRuntimeConfig, Vector[OpenAiLedgerBackedAllowance.Group], Int,
        OpenAiGroupAllocationVerifier, Option[OpenAiRequestSizingVerifier]))(using context: ExecutionContext)
      : Consequence[(Vector[AiTaskCandidate], AiTaskInvoker)] = {
    val (connection, allocations, margin, verifier, sizing) = config
    try {
      val members = OpenAiLedgerBackedAllowance.routeMembers(allocations, request, context, verifier)
      val entries = members.flatMap { member =>
        val allocation = member.group.allocation
        val selected = connection.copy(model = allocation.model)
        val authority = new OpenAiLedgerBackedAllowance(selected, allocation, context, margin, sizing, Some(member))
        val post = new OpenAiManagedTaskPost(authority.connection, context)
        if (!_valid_connection(authority.connection) || !post.isQualified || deadline.remainingMillis <= 0) None
        else authority.assess(request, deadline.endNanos).map { candidate =>
          (candidate, member, new OpenAiBoundedTaskInvoker(authority, post, 1024 * 1024, Some(deadline)))
        }
      }
      val invoker = new AiTaskInvoker {
        private def entry(c: AiTaskCandidate) = entries.find(_._1 == c)
        private def attributed(c: AiTaskCandidate, dispatch: AiTaskDispatch): AiTaskDispatch = {
          val group = entry(c).get._2.group
          def receipt(value: AiTaskReceipt): AiTaskReceipt =
            if (!value.reservationBasis.contains("verified-daily-reservation")) value
            else value.copy(modelGroup = Some(group.allocation.modelPool),
              allocationProvenance = group.allocation.daily.map(_.window.provenance),
              externalUsageProvenance = Some(group.externalProvenance), externalUsageTokens = Some(group.externalTokens))
          dispatch match {
            case AiTaskDispatch.Completed(output, r) => AiTaskDispatch.Completed(output, receipt(r))
            case AiTaskDispatch.Declined(reason, r) => AiTaskDispatch.Declined(reason, receipt(r))
            case AiTaskDispatch.Failed(reason, r) => AiTaskDispatch.Failed(reason, receipt(r))
            case AiTaskDispatch.Uncertain(reason, r) => AiTaskDispatch.Uncertain(reason, receipt(r))
          }
        }
        def preflight(r: AiTaskRequest, c: AiTaskCandidate): Either[String, AiTaskInvocationCapability] =
          entry(c).toRight("Candidate unavailable").flatMap(_._3.preflight(r, c))
        override private[ai] def candidatePriority(r: AiTaskRequest, c: AiTaskCandidate): Int =
          if (r == request) entry(c).fold(0)(e =>
            OpenAiBoundedRecordBinding.groupPriority(r.kind, e._2.group.allocation.modelPool)) else 0
        def invoke(r: AiTaskRequest, c: AiTaskCandidate): AiTaskDispatch = attributed(c, entry(c).get._3.invoke(r, c))
        override private[ai] def dispatchAfterPreflight(r: AiTaskRequest, c: AiTaskCandidate,
            d: AiTaskDeadline): AiTaskDispatch = {
          val group = entry(c).get._2.group
          d.selectGroup(AiTaskGroupEvidence(group.allocation.modelPool, group.allocation.daily.get.window.provenance,
            group.externalProvenance, group.externalTokens))
          attributed(c, entry(c).get._3.dispatchAfterPreflight(r, c, d))
        }
        override private[ai] def takeExhaustionAfterDecline(r: AiTaskRequest, previous: AiTaskCandidate,
            next: AiTaskCandidate): Option[AiTaskPreDispatchExhaustion] = {
          if (r != request || previous.provider != "openai" || next.provider != "openai" ||
              previous.model == next.model || entry(next).isEmpty) None
          else entry(previous).flatMap { e =>
            val group = e._2.group
            Option.when(e._2.takeExhausted())(AiTaskPreDispatchExhaustion("openai", previous.model,
              group.allocation.modelPool, group.allocation.daily.get.window.provenance,
              group.externalProvenance, group.externalTokens))
          }
        }
      }
      Consequence.success(entries.map(_._1) -> invoker)
    } catch { case NonFatal(_) => Consequence.success(Vector.empty[AiTaskCandidate] -> OpenAiBoundedRecordBinding.Unavailable) }
  }

  private def _valid_connection(config: OpenAiRuntimeConfig): Boolean =
    config.provider == "openai" && config.mode == "remote" && config.engine == "gpt" &&
      config.endpoint.getScheme == "https" && config.endpoint.getHost != null &&
      config.endpoint.getRawUserInfo == null && config.endpoint.getRawQuery == null &&
      config.endpoint.getRawFragment == null && config.timeoutSeconds > 0 &&
      config.model.trim.nonEmpty && config.apiKey.trim.nonEmpty
}

private[ai] object OpenAiBoundedRecordBinding {
  private[ai] def groupPriority(kind: String, pool: String): Int =
    if (Set("review", "independent-review", "compiler-repair")(kind) && pool == "openai-daily-group-2") -1 else 0

  private object Unavailable extends AiTaskInvoker {
    override def preflight(r: AiTaskRequest, c: AiTaskCandidate): Either[String, AiTaskInvocationCapability] =
      Left("OpenAI connection has no verified allowance for this request")

    override def invoke(r: AiTaskRequest, c: AiTaskCandidate): AiTaskDispatch =
      AiTaskDispatch.Declined("OpenAI connection has no verified allowance for this request",
        AiTaskReceipt(None, None, None, None, AiTaskUsage.Unknown))
  }
}
