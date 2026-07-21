package org.simplemodeling.textus.ai.runtime

import io.circe.Json
import java.nio.charset.StandardCharsets
import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ExtensionPoint, Port, ServiceContract, VariationSelection}
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.mcp.client.*
import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.protocol.Property
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.ai.ai.*

/*
 * Executable specification for provider-neutral MCP function orchestration.
 *
 * @since   Jul. 21, 2026
 * @version Jul. 21, 2026
 * @author  ASAMI, Tomoharu
 */
final class McpToolOrchestratorSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "McpToolOrchestrator" should {
    "execute only an admitted tool and continue the model turn with its result" in {
      Given("one admitted MCP tool and a tool-capable model that requests it")
      given ExecutionContext = ExecutionContext.create()
      val fixture = _fixture()
      val service = new _ToolService

      When("the runtime runs the bounded tool loop")
      val result = McpToolOrchestrator.generateC(
        service,
        fixture.invocation,
        "Find the route context.",
        None,
        Some(128),
        Vector.empty
      )

      Then("the model receives a runtime-owned tool result and only safe facts escape")
      result.toOption.map(_.text) shouldBe Some("Grounded answer: station context")
      fixture.calls.map(_.toolIdentity.print) shouldBe Vector("research/places.lookup")
      service.toolDefinitions.headOption.exists(_.name.startsWith("mcp_")) shouldBe true
      service.secondTurnMessages.lastOption.map(_.toolName) shouldBe service.toolDefinitions.headOption.map(x => Some(x.name))
      result.toOption.flatMap(_.metadata.get("gemma.mcp_calls")) shouldBe Some("1")
      result.toOption.flatMap(_.metadata.get("gemma.mcp_turns")) shouldBe Some("2")
      result.toOption.flatMap(_.metadata.get("gemma.mcp_catalog_digest")) should not be empty
      result.toOption.toVector.flatMap(_.metadata.values).exists(_.contains("station context")) shouldBe false
    }

    "reject a model-requested function outside the admitted catalog" in {
      Given("a model response naming an unadmitted function")
      given ExecutionContext = ExecutionContext.create()
      val fixture = _fixture()
      val service = new _UnknownToolService

      When("the runtime resolves the response")
      val result = McpToolOrchestrator.generateC(
        service,
        fixture.invocation,
        "Find the route context.",
        None,
        Some(128),
        Vector.empty
      )

      Then("the function is rejected before MCP invocation")
      result.isFaillure shouldBe true
      fixture.calls shouldBe Vector.empty
    }

    "enforce the bounded tool-call budget before invoking MCP" in {
      Given("a model response requesting more calls than the runtime allows")
      given ExecutionContext = ExecutionContext.create()
      val fixture = _fixture()
      val service = new _TooManyCallsService

      When("the bounded loop processes the first model turn")
      val result = McpToolOrchestrator.generateC(
        service,
        fixture.invocation,
        "Find the route context.",
        None,
        Some(128),
        Vector.empty
      )

      Then("the request is rejected without invoking any MCP tool")
      result.isFaillure shouldBe true
      fixture.calls shouldBe Vector.empty
    }

    "derive a conservative admission bound for every possible tool continuation" in {
      Given("a bounded initial prompt and per-turn output policy")
      val initial = AiInputTokenEstimate(10L, 10L, 0, 0L)

      When("the tool-loop admission envelope is calculated")
      val input = McpToolOrchestrator.admissionInputEstimate(initial, Some(100))
      val output = McpToolOrchestrator.admissionMaxOutputTokensC(Some(100)).toOption.flatten

      Then("all possible turns, catalog definitions, tool results, and continuation output are covered")
      input.tokens shouldBe 1049216L
      output shouldBe Some(400)
    }

    "truncate tool results on UTF-8 boundaries before provider continuation" in {
      Given("an MCP result whose multi-byte text exceeds the runtime byte bound")
      given ExecutionContext = ExecutionContext.create()
      val fixture = _fixture("地".repeat(6000))
      val service = new _ToolService

      When("the model continues after the admitted tool call")
      val result = McpToolOrchestrator.generateC(
        service,
        fixture.invocation,
        "Find the route context.",
        None,
        Some(128),
        Vector.empty
      )

      Then("the tool message remains valid UTF-8 within its fixed byte limit")
      result.isSuccess shouldBe true
      service.secondTurnMessages.last.content.getBytes(StandardCharsets.UTF_8).length should be <= 16384
    }

    "resolve tool execution only through the MCP socket installed by runtime assembly" in {
      Given("a Textus AI component and a CNCF registry for one logical server set")
      given ExecutionContext = ExecutionContext.create()
      val server = McpServerId.parseC("research").toOption.get
      val name = McpToolName.parseC("places.lookup").toOption.get
      val setid = McpServerSetId.parseC("research-tools").toOption.get
      val query = McpFieldName.parseC("query").toOption.get
      val tool = McpClientTool.createC(
        McpToolIdentity(server, name),
        McpInputSchema.objectC(Vector(
          McpInputField.createC(query, McpInputSchema.StringValue, required = true).toOption.get
        )).toOption.get
      ).toOption.get
      val serverset = McpClientServerSet.createC(
        setid,
        Vector(McpClientServer.createC(server, Vector(name)).toOption.get)
      ).toOption.get
      val socket = McpClientSocket.createC(Vector(McpClientRequirement(setid))).toOption.get
      val model = new _ToolService
      val component = new Component() {}
        .withBinding("chat", _tool_chat_binding(model))
        .withPort(Component.Port.input(socket))
      val transport = new _Transport(Vector(tool))
      val registry = McpClientRuntimeRegistry.createC(
        Vector(serverset),
        _transport_binding(transport)
      ).toOption.get
      val provider = new TextusAiRunnerProvider(component)

      When("the provider starts a tool-grounded generation")
      val result = try {
        registry.install(component).flatMap { _ =>
          provider.generateWithMcpToolsC(
            SpiSelection(provider = Some("gemma"), mode = Some("local"), engine = Some("ollama")),
            setid,
            "Find the route context.",
            None,
            Some(128),
            Vector.empty
          )
        }
      } finally registry.close()

      Then("the provider uses the installed socket rather than a caller supplied endpoint or tool")
      val response = result match {
        case Consequence.Success(value) => value
        case Consequence.Failure(conclusion) => fail(conclusion.display)
      }
      response.text shouldBe "Grounded answer: station context"
      transport.calls.map(_.toolIdentity.print) shouldBe Vector("research/places.lookup")
    }
  }

  private final case class _Fixture(
    invocation: _Invocation
  ) {
    def calls: Vector[McpClientCall] = invocation.calls
  }

  private def _fixture(
    resulttext: String = "station context"
  ): _Fixture = {
    val server = McpServerId.parseC("research").toOption.get
    val name = McpToolName.parseC("places.lookup").toOption.get
    val field = McpFieldName.parseC("query").toOption.get
    val schema = McpInputSchema.objectC(Vector(
      McpInputField.createC(field, McpInputSchema.StringValue, required = true).toOption.get
    )).toOption.get
    val tool = McpClientTool.createC(McpToolIdentity(server, name), schema).toOption.get
    val setid = McpServerSetId.parseC("research-tools").toOption.get
    val serverset = McpClientServerSet.createC(
      setid,
      Vector(McpClientServer.createC(server, Vector(name)).toOption.get)
    ).toOption.get
    _Fixture(new _Invocation(McpClientCatalog.createC(serverset, Vector(tool)).toOption.get, resulttext))
  }

  private final class _Invocation(
    value: McpClientCatalog,
    resulttext: String
  ) extends McpClientInvocation {
    var calls: Vector[McpClientCall] = Vector.empty

    def catalog(using ExecutionContext): Consequence[McpClientCatalog] = Consequence.success(value)

    def invoke(call: McpClientCall)(using ExecutionContext): Consequence[McpClientResult] = {
      calls = calls :+ call
      Consequence.success(McpClientResult(
        Vector(McpClientContent.Text(resulttext)),
        None
      ))
    }
  }

  private final class _ToolService extends ToolCallingChatService {
    private var _turn = 0
    var toolDefinitions: Vector[ToolDefinition] = Vector.empty
    var secondTurnMessages: Vector[ToolChatMessage] = Vector.empty

    def chat(req: ChatRequest): Consequence[ChatResponse] =
      Consequence.success(ChatResponse(Message(MessageRole.Assistant, "unused")))

    def chatWithTools(req: ToolChatRequest): Consequence[ToolChatResponse] = {
      _turn += 1
      toolDefinitions = req.tools
      if (_turn == 1)
        Consequence.success(ToolChatResponse(
          ToolChatMessage(
            "assistant",
            toolCalls = Vector(ToolCall(req.tools.head.name, Json.obj("query" -> Json.fromString("Nakano"))))
          ),
          Some("fixture"),
          Map("gemma.usage.input_tokens" -> "3", "gemma.usage.output_tokens" -> "2", "gemma.usage.total_tokens" -> "5")
        ))
      else {
        secondTurnMessages = req.messages
        Consequence.success(ToolChatResponse(
          ToolChatMessage("assistant", "Grounded answer: station context"),
          Some("fixture"),
          Map("gemma.usage.input_tokens" -> "4", "gemma.usage.output_tokens" -> "3", "gemma.usage.total_tokens" -> "7")
        ))
      }
    }
  }

  private final class _UnknownToolService extends ToolCallingChatService {
    def chat(req: ChatRequest): Consequence[ChatResponse] =
      Consequence.success(ChatResponse(Message(MessageRole.Assistant, "unused")))

    def chatWithTools(req: ToolChatRequest): Consequence[ToolChatResponse] =
      Consequence.success(ToolChatResponse(
        ToolChatMessage("assistant", toolCalls = Vector(ToolCall("not_admitted", Json.obj()))),
        Some("fixture")
      ))
  }

  private def _tool_chat_binding(
    service: ToolCallingChatService
  ): Component.Binding[GenerateRequirement, ChatService] =
    Component.Binding(Port(
      api = new ChatPortApi {},
      spi = Vector(new ExtensionPoint[ChatService] {
        def supports(
          contract: ServiceContract[ChatService],
          variation: VariationSelection
        )(using ExecutionContext): Boolean =
          contract.name == "chat-service" && variation.provider.contains("gemma")

        def provide(
          contract: ServiceContract[ChatService],
          variation: VariationSelection
        )(using ExecutionContext): Consequence[ChatService] = Consequence.success(service)
      }),
      variation = new GenerateVariationPoint {}
    ))

  private def _transport_binding(
    transport: McpClientTransport
  ): Component.Binding[McpClientTransportRequirement, McpClientTransport] =
    Component.Binding(Port(
      api = McpClientTransportPortApi,
      spi = Vector(new ExtensionPoint[McpClientTransport] {
        def supports(
          contract: ServiceContract[McpClientTransport],
          variation: VariationSelection
        )(using ExecutionContext): Boolean = variation == VariationSelection()

        def provide(
          contract: ServiceContract[McpClientTransport],
          variation: VariationSelection
        )(using ExecutionContext): Consequence[McpClientTransport] = Consequence.success(transport)
      }),
      variation = McpClientTransportSelectionPoint
    ))

  private final class _Transport(
    tools: Vector[McpClientTool]
  ) extends McpClientTransport {
    var calls: Vector[McpClientCall] = Vector.empty

    def initialize(server: McpClientServer, limits: McpClientLimits)(using ExecutionContext): Consequence[Unit] = Consequence.unit

    def listTools(server: McpClientServer, limits: McpClientLimits)(using ExecutionContext): Consequence[Vector[McpClientTool]] =
      Consequence.success(tools)

    def callTool(
      server: McpClientServer,
      call: McpClientCall,
      limits: McpClientLimits
    )(using ExecutionContext): Consequence[McpClientResult] = {
      calls = calls :+ call
      Consequence.success(McpClientResult(Vector(McpClientContent.Text("station context")), None))
    }
  }

  private final class _TooManyCallsService extends ToolCallingChatService {
    def chat(req: ChatRequest): Consequence[ChatResponse] =
      Consequence.success(ChatResponse(Message(MessageRole.Assistant, "unused")))

    def chatWithTools(req: ToolChatRequest): Consequence[ToolChatResponse] =
      Consequence.success(ToolChatResponse(
        ToolChatMessage(
          "assistant",
          toolCalls = Vector.fill(9)(ToolCall(req.tools.head.name, Json.obj("query" -> Json.fromString("Nakano"))))
        ),
        Some("fixture")
      ))
  }
}
