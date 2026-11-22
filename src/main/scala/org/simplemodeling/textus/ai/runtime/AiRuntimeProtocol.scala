package org.simplemodeling.textus.ai.runtime

import org.goldenport.Consequence
import org.goldenport.cncf.component.{PortApi, ServiceContract, VariationPoint, VariationSelection}
import org.goldenport.cncf.context.ExecutionContext
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
  usage: AiTaskUsage
)

enum AiTaskDispatch {
  case Completed(output: String, receipt: AiTaskReceipt)
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
}
