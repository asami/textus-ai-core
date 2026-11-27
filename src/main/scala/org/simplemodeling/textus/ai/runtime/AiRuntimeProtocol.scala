package org.simplemodeling.textus.ai.runtime

import org.goldenport.Consequence
import org.goldenport.cncf.component.{PortApi, ServiceContract, VariationPoint, VariationSelection}
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.record.Record
import org.simplemodeling.textus.airuntime.ai.*

/*
 * @since   Jul.  2, 2026
 *  version Aug. 21, 2026
 * @version Nov. 23, 2026
 * @author  ASAMI, Tomoharu
 */

final case class GenerateRequirement(
  provider: Option[String] = Some("gemma"),
  mode: Option[String] = Some("local"),
  engine: Option[String] = Some("ollama")
)

trait GenerateService:
  def generate(req: GenerateRequest): Consequence[GenerateResponse]

trait ChatService:
  def chat(req: ChatRequest): Consequence[ChatResponse]

/** Optional internal capability for bounded runtime-owned function loops. */
trait ToolCallingChatService extends ChatService:
  def chatWithTools(req: ToolChatRequest): Consequence[ToolChatResponse]

trait GeneratePortApi extends PortApi[GenerateRequirement, GenerateService]:
  private val _contract =
    ServiceContract[GenerateService](
      name = "generate-service",
      runtimeClass = classOf[GenerateService]
    )

  override def resolve(req: GenerateRequirement): Consequence[ServiceContract[GenerateService]] =
    Consequence.success(_contract)

trait ChatPortApi extends PortApi[GenerateRequirement, ChatService]:
  private val _contract =
    ServiceContract[ChatService](
      name = "chat-service",
      runtimeClass = classOf[ChatService]
    )

  override def resolve(req: GenerateRequirement): Consequence[ServiceContract[ChatService]] =
    Consequence.success(_contract)

trait GenerateVariationPoint extends VariationPoint[GenerateRequirement]:
  override def current(req: GenerateRequirement)(using ExecutionContext): Consequence[VariationSelection] =
    Consequence.success(
      VariationSelection(
        provider = req.provider,
        mode = req.mode,
        engine = req.engine
      )
    )

  override def inject(
    req: GenerateRequirement,
    selection: VariationSelection
  )(using ExecutionContext): Consequence[GenerateRequirement] =
    Consequence.success(
      req.copy(
        provider = selection.provider.orElse(req.provider),
        mode = selection.mode.orElse(req.mode),
        engine = selection.engine.orElse(req.engine)
      )
    )

/** A bounded consumer-owned package; the reference and provenance are retained in every result. */
final case class AiTaskPackage(reference: String, provenance: String, content: String) {
  require(reference.nonEmpty && provenance.nonEmpty && content.length <= 1000000)
}

final case class AiTaskRequest(
  id: String,
  kind: String,
  input: AiTaskPackage,
  outputSchema: String,
  requiredCapabilities: Set[String],
  requiredTools: Set[String],
  reasoning: String,
  maxLatencyMillis: Long,
  budgetMicrounits: Long,
  preferIncludedQuota: Boolean,
  allowPaidOverage: Boolean,
  maxOutputCharacters: Int = 100000
) {
  require(id.nonEmpty && kind.nonEmpty && outputSchema.nonEmpty && reasoning.nonEmpty)
  require(maxLatencyMillis > 0 && budgetMicrounits >= 0 && maxOutputCharacters > 0)
}

/** The caller attests quota, entitlement and cost bounds for exactly verifiedForRequest. */
final case class AiTaskCandidate(
  provider: String,
  model: String,
  capabilities: Set[String],
  tools: Set[String],
  verifiedIncludedUnits: Option[Long],
  requiredUnits: Long,
  verifiedForRequest: AiTaskRequest,
  verifiedCostUpperBoundMicrounits: Option[Long],
  verifiedPaidEntitlement: Boolean = false
) {
  require(provider.nonEmpty && model.nonEmpty && requiredUnits > 0)
  require(verifiedIncludedUnits.forall(_ >= 0) && verifiedCostUpperBoundMicrounits.forall(_ >= 0))
}

enum AiTaskUsage {
  case Measured(units: Long, costMicrounits: Option[Long])
  case Estimated(units: Long, costMicrounits: Option[Long])
  case Unknown
}

/** Provider-reported facts are optional rather than inferred from selection. */
final case class AiTaskReceipt(
  actualProvider: Option[String],
  actualModel: Option[String],
  providerRequestId: Option[String],
  trace: Option[String],
  usage: AiTaskUsage,
  reservationBasis: Option[String] = None,
  modelGroup: Option[String] = None,
  allocationProvenance: Option[String] = None,
  externalUsageProvenance: Option[String] = None,
  externalUsageTokens: Option[Long] = None,
  preDispatchExhaustions: Vector[AiTaskPreDispatchExhaustion] = Vector.empty
) {
  def this(actualProvider: Option[String], actualModel: Option[String], providerRequestId: Option[String],
      trace: Option[String], usage: AiTaskUsage, reservationBasis: Option[String]) =
    this(actualProvider, actualModel, providerRequestId, trace, usage, reservationBasis,
      None, None, None, None, Vector.empty)

  def copy(actualProvider: Option[String] = this.actualProvider, actualModel: Option[String] = this.actualModel,
      providerRequestId: Option[String] = this.providerRequestId, trace: Option[String] = this.trace,
      usage: AiTaskUsage = this.usage, reservationBasis: Option[String] = this.reservationBasis,
      modelGroup: Option[String] = this.modelGroup, allocationProvenance: Option[String] = this.allocationProvenance,
      externalUsageProvenance: Option[String] = this.externalUsageProvenance,
      externalUsageTokens: Option[Long] = this.externalUsageTokens,
      preDispatchExhaustions: Vector[AiTaskPreDispatchExhaustion] = this.preDispatchExhaustions): AiTaskReceipt =
    new AiTaskReceipt(actualProvider, actualModel, providerRequestId, trace, usage, reservationBasis,
      modelGroup, allocationProvenance, externalUsageProvenance, externalUsageTokens, preDispatchExhaustions)

  def copy(actualProvider: Option[String], actualModel: Option[String], providerRequestId: Option[String],
      trace: Option[String], usage: AiTaskUsage, reservationBasis: Option[String]): AiTaskReceipt =
    new AiTaskReceipt(actualProvider, actualModel, providerRequestId, trace, usage, reservationBasis,
      modelGroup, allocationProvenance, externalUsageProvenance, externalUsageTokens, preDispatchExhaustions)
}

object AiTaskReceipt {
  def apply(actualProvider: Option[String], actualModel: Option[String], providerRequestId: Option[String],
      trace: Option[String], usage: AiTaskUsage, reservationBasis: Option[String]): AiTaskReceipt =
    new AiTaskReceipt(actualProvider, actualModel, providerRequestId, trace, usage, reservationBasis)
}

/** An eligible candidate was refused on verified capacity before provider invocation. */
final case class AiTaskPreDispatchExhaustion(provider: String, model: String, modelGroup: String,
  allocationProvenance: String, externalUsageProvenance: String, externalUsageTokens: Long)

private[ai] final case class AiTaskGroupEvidence(group: String, allocation: String,
  externalProvenance: String, externalTokens: Long)

enum AiTaskDispatch {
  case Completed(output: Record, receipt: AiTaskReceipt)
  case Declined(reason: String, receipt: AiTaskReceipt)
  case Failed(reason: String, receipt: AiTaskReceipt)
  case Uncertain(reason: String, receipt: AiTaskReceipt)
}

final case class AiTaskAttempt(index: Int, selected: AiTaskCandidate, dispatch: AiTaskDispatch)

enum AiTaskResult {
  case Declined(request: AiTaskRequest, reason: String)
  case Completed(request: AiTaskRequest, attempt: AiTaskAttempt)
  case ProviderDeclined(request: AiTaskRequest, attempt: AiTaskAttempt)
  case Failed(request: AiTaskRequest, attempt: AiTaskAttempt)
  case Uncertain(request: AiTaskRequest, attempt: AiTaskAttempt)
}

/** A provider adapter's pre-dispatch attestation, bound to the exact request and selection.
 * The adapter must enforce these limits, including an end-to-end deadline, and make
 * exactly one transport attempt. A timeout after possible dispatch is Uncertain.
 * An adapter unable to guarantee any of these facts must decline preflight.
 */
final case class AiTaskInvocationCapability(
  request: AiTaskRequest,
  candidate: AiTaskCandidate,
  maxInputCharacters: Int,
  maxOutputCharacters: Int,
  maxCostMicrounits: Long,
  deadlineMillis: Long,
  exactModel: Boolean,
  singleAttempt: Boolean,
  endToEndDeadline: Boolean
)

trait AiTaskInvoker {
  /** Must not send a provider request. */
  def preflight(request: AiTaskRequest, candidate: AiTaskCandidate): Either[String, AiTaskInvocationCapability]
  /** Invoke once; return Uncertain if dispatch may have occurred without a conclusive result. */
  def invoke(request: AiTaskRequest, candidate: AiTaskCandidate): AiTaskDispatch

  private[ai] def candidatePriority(request: AiTaskRequest, candidate: AiTaskCandidate): Int = 0

  /** Only an exact, known pre-dispatch exhaustion may authorize another candidate. */
  private[ai] def takeExhaustionAfterDecline(request: AiTaskRequest, previous: AiTaskCandidate,
      next: AiTaskCandidate): Option[AiTaskPreDispatchExhaustion] = None

  private[ai] def dispatchAfterPreflight(request: AiTaskRequest, candidate: AiTaskCandidate,
      deadline: AiTaskDeadline): AiTaskDispatch = {
    val unknown = AiTaskReceipt(None, None, None, None, AiTaskUsage.Unknown)
    if (!deadline.beginDispatch(unknown)) AiTaskDispatch.Declined("Deadline expired before dispatch", unknown)
    else invoke(request, candidate)
  }
}

/** One monotonic time window shared by selection, reservation and dispatch. */
final class AiTaskDeadline private (val endNanos: Long, readNanos: () => Long) {
  private var closed = false
  private var receipt: Option[AiTaskReceipt] = None
  private var groupEvidence: Option[AiTaskGroupEvidence] = None
  private var preDispatchExhaustions = Vector.empty[AiTaskPreDispatchExhaustion]
  private[ai] def recordExhaustion(value: AiTaskPreDispatchExhaustion): Unit = synchronized {
    if (!closed && receipt.isEmpty) preDispatchExhaustions :+= value
  }
  private[ai] def retainedExhaustions: Vector[AiTaskPreDispatchExhaustion] = synchronized(preDispatchExhaustions)
  private[ai] def selectGroup(value: AiTaskGroupEvidence): Unit = synchronized {
    if (!closed && receipt.isEmpty) groupEvidence = Some(value)
  }
  private def attributed(value: AiTaskReceipt): AiTaskReceipt =
    if (!value.reservationBasis.contains("verified-daily-reservation") && value.actualProvider.isEmpty) value
    else groupEvidence.fold(value.copy(preDispatchExhaustions = preDispatchExhaustions))(g =>
      value.copy(modelGroup = Some(g.group), allocationProvenance = Some(g.allocation),
        externalUsageProvenance = Some(g.externalProvenance), externalUsageTokens = Some(g.externalTokens),
        preDispatchExhaustions = preDispatchExhaustions))
  def remainingMillis: Long =
    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(math.max(0L, endNanos - readNanos()))
  private[ai] def beginDispatch(initial: AiTaskReceipt): Boolean = synchronized {
    if (closed || remainingMillis <= 0 || receipt.nonEmpty) false
    else { receipt = Some(initial); true }
  }
  private[ai] def observeReceipt(value: AiTaskReceipt): Unit = synchronized {
    if (!closed && receipt.nonEmpty) receipt = Some(value)
  }
  private[ai] def close(): Option[AiTaskReceipt] = synchronized {
    closed = true
    receipt
  }
  private[ai] def closeForReporting(): Option[AiTaskReceipt] = synchronized {
    closed = true
    receipt.map(attributed)
  }
}

object AiTaskDeadline {
  private[ai] val maxMillis = 86400000L
  private val executor = new java.util.concurrent.ThreadPoolExecutor(4, 4, 30L,
    java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue[Runnable](64),
    (r: Runnable) => { val t = new Thread(r, "textus-ai-bounded-task"); t.setDaemon(true); t },
    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
  executor.allowCoreThreadTimeOut(true)

  /** Left contains the last receipt if dispatch may have begun. */
  private[ai] def await[A](deadline: AiTaskDeadline)(body: => A): Either[Option[AiTaskReceipt], A] = {
    val task = new java.util.concurrent.FutureTask[A](new java.util.concurrent.Callable[A] {
      def call(): A = body
    })
    try {
      executor.execute(task)
      val remaining = deadline.remainingMillis
      if (remaining <= 0) throw new java.util.concurrent.TimeoutException()
      val result = task.get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
      if (deadline.remainingMillis <= 0) Left(deadline.closeForReporting()) else Right(result)
    } catch {
      case _: InterruptedException =>
        val observed = deadline.closeForReporting(); task.cancel(true); executor.remove(task)
        Thread.currentThread().interrupt(); Left(observed)
      case _: java.util.concurrent.TimeoutException =>
        val observed = deadline.closeForReporting(); task.cancel(true); executor.remove(task); Left(observed)
      case _: java.util.concurrent.RejectedExecutionException => Left(deadline.closeForReporting())
      case _: java.util.concurrent.ExecutionException => Left(deadline.closeForReporting())
    }
  }

  private[ai] def after(startNanos: Long, durationMillis: Long, readNanos: () => Long = () => System.nanoTime())
      : AiTaskDeadline = {
    require(durationMillis > 0 && durationMillis <= maxMillis)
    new AiTaskDeadline(startNanos + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(durationMillis), readNanos)
  }
}

/** Installed on the provider component by a host with a qualified usage policy.
  * Constructing this binding grants no provider effect. Preparation establishes
  * request-bound candidates without dispatch and supplies their bounded invoker.
  */
trait AiBoundedRecordBinding {
  def limits: AiBoundedRecordLimits
  def prepare(request: AiTaskRequest, deadline: AiTaskDeadline)(using ExecutionContext)
      : Consequence[(Vector[AiTaskCandidate], AiTaskInvoker)]
}

object AiBoundedRecordBinding {
  private[ai] def combine(bindings: Vector[AiBoundedRecordBinding]): Option[AiBoundedRecordBinding] =
    bindings match {
      case Vector() => None
      case Vector(binding) => Some(binding)
      case values => Some(new AiBoundedRecordBinding {
        override val limits = AiBoundedRecordLimits(
          values.map(_.limits.maxLatencyMillis).min,
          values.map(_.limits.budgetMicrounits).min,
          values.map(_.limits.maxOutputCharacters).min,
          preferIncludedQuota = values.exists(_.limits.preferIncludedQuota),
          allowPaidOverage = values.forall(_.limits.allowPaidOverage)
        )

        override def prepare(request: AiTaskRequest, deadline: AiTaskDeadline)(using ExecutionContext)
            : Consequence[(Vector[AiTaskCandidate], AiTaskInvoker)] = {
          val prepared = values.foldLeft(Consequence.success(Vector.empty[(AiTaskCandidate, AiTaskInvoker)])) {
            (previous, binding) => for {
              accumulated <- previous
              entry <- binding.prepare(request, deadline)
            } yield accumulated ++ entry._1.map(_ -> entry._2)
          }
          prepared.map { entries =>
            val byCandidate = entries.toMap
            val invoker = new AiTaskInvoker {
              override private[ai] def candidatePriority(r: AiTaskRequest, candidate: AiTaskCandidate): Int =
                byCandidate.get(candidate).fold(0)(_.candidatePriority(r, candidate))
              def preflight(r: AiTaskRequest, candidate: AiTaskCandidate): Either[String, AiTaskInvocationCapability] =
                byCandidate.get(candidate).toRight("Provider was absent from this bounded selection").flatMap(_.preflight(r, candidate))

              def invoke(r: AiTaskRequest, candidate: AiTaskCandidate): AiTaskDispatch =
                byCandidate(candidate).invoke(r, candidate)

              override private[ai] def takeExhaustionAfterDecline(r: AiTaskRequest, previous: AiTaskCandidate,
                  next: AiTaskCandidate): Option[AiTaskPreDispatchExhaustion] =
                byCandidate.get(previous).flatMap(_.takeExhaustionAfterDecline(r, previous, next))

              override private[ai] def dispatchAfterPreflight(r: AiTaskRequest, candidate: AiTaskCandidate,
                  deadline: AiTaskDeadline): AiTaskDispatch =
                byCandidate(candidate).dispatchAfterPreflight(r, candidate, deadline)
            }
            entries.map(_._1) -> invoker
          }
        }
      })
    }
}

final case class AiBoundedRecordLimits(maxLatencyMillis: Long, budgetMicrounits: Long,
  maxOutputCharacters: Int, preferIncludedQuota: Boolean = true, allowPaidOverage: Boolean = false) {
  require(maxLatencyMillis > 0 && maxLatencyMillis <= AiTaskDeadline.maxMillis &&
    budgetMicrounits >= 0 && maxOutputCharacters > 0)
}

/** Host-provided reservation. Acquisition must be atomic across all users of the
 * same provider allowance, request-bound and bounded by the supplied deadline.
 * The authority must reject reuse of the same request across invoker instances;
 * a reservation remains consumed after a possible dispatch, including timeout.
 * Implementations must guarantee the reserved bound cannot incur paid overage
 * when the request disallows it; estimates and observed balances are not proof.
 */
final case class AiTaskAllowance(
  request: AiTaskRequest,
  candidate: AiTaskCandidate,
  maxOutputTokens: Int,
  maxCostMicrounits: Long,
  reservationBasis: Option[String] = None,
  validUntil: Option[java.time.Instant] = None
)
trait AiTaskAllowanceAuthority {
  def reserve(request: AiTaskRequest, candidate: AiTaskCandidate, deadlineNanos: Long): Either[String, AiTaskAllowance]
}

/** Supplied only by a host that owns and qualifies the actual bounded interpreter.
 * The implementation must make one POST, disallow redirects, bound the complete
 * response body, and respect the supplied remaining total timeout. A failure
 * after entry is conservatively treated as possibly dispatched.
 */
sealed trait AiTaskManagedPostResult
object AiTaskManagedPostResult {
  case object NotDispatched extends AiTaskManagedPostResult
  case object PossiblyDispatched extends AiTaskManagedPostResult
  final case class Response(status: Int, requestId: Option[String], body: String) extends AiTaskManagedPostResult
}

trait AiTaskManagedPost {
  def isQualified: Boolean = false
  def post(body: io.circe.Json, timeoutMillis: Long, maxResponseBytes: Int): Either[String, (Int, Option[String], String)]
  def postObserved(body: io.circe.Json, timeoutMillis: Long, maxResponseBytes: Int): AiTaskManagedPostResult =
    post(body, timeoutMillis, maxResponseBytes) match {
      case Left(_) => AiTaskManagedPostResult.PossiblyDispatched
      case Right((status, requestId, response)) => AiTaskManagedPostResult.Response(status, requestId, response)
    }
}
