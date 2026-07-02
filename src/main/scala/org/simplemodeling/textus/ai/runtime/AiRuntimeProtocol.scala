package org.simplemodeling.textus.ai.runtime

import org.goldenport.Consequence
import org.goldenport.cncf.component.{PortApi, ServiceContract, VariationPoint, VariationSelection}
import org.goldenport.cncf.context.ExecutionContext
import org.simplemodeling.textus.ai.ai.*

final case class GenerateRequirement(
  provider: Option[String] = Some("gemma"),
  mode: Option[String] = Some("local"),
  engine: Option[String] = Some("ollama")
)

trait GenerateService:
  def generate(req: GenerateRequest): Consequence[GenerateResponse]

trait ChatService:
  def chat(req: ChatRequest): Consequence[ChatResponse]

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
