package org.simplemodeling.textus.ai.runtime

import io.circe.Json
import java.nio.charset.StandardCharsets
import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ExtensionPoint, Port, ServiceContract, VariationSelection}
import org.goldenport.cncf.component.builtin.BuiltinComponentIdentity
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.mcp.client.*
import org.goldenport.cncf.operationtool.*
import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.cncf.subsystem.DefaultSubsystemFactory
import org.goldenport.protocol.Property
import org.goldenport.protocol.operation.OperationResponse
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.airuntime.ai.*

/*
 * Executable specification for provider-neutral internal and remote tool
 * function orchestration.
 *
 * @since   Jul. 21, 2026
 * @version Aug. 21, 2026
 * @author  ASAMI, Tomoharu
 */
final class ToolOrchestratorSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "ToolOrchestrator" should {
    "provider-neutral execution" which {
      "execute only an admitted tool and continue the model turn with its result" in {
        Given("one admitted MCP tool and a tool-capable model that requests it")
        given ExecutionContext = ExecutionContext.create()
        val fixture = _fixture()
        val service = new _ToolService

        When("the runtime runs the bounded tool loop")
        val result = ToolOrchestrator.generateC(
          service,
          fixture.invocation,
          "Find the route context.",
          None,
          Some(128),
          Vector.empty,
          "gemma"
        )

        Then("the model receives a runtime-owned tool result and only safe facts escape")
        result.toOption.map(_.text) shouldBe Some("Grounded answer: station context")
        fixture.calls.map(_.toolIdentity.print) shouldBe Vector("research/places.lookup")
        service.tooldefinitions.headOption.exists(_.name.startsWith("mcp_")) shouldBe true
        service.secondturnmessages.lastOption.map(_.toolName) shouldBe service.tooldefinitions.headOption.map(x => Some(x.name))
        result.toOption.flatMap(_.metadata.get("gemma.mcp_calls")) shouldBe Some("1")
        result.toOption.flatMap(_.metadata.get("gemma.mcp_turns")) shouldBe Some("2")
        result.toOption.flatMap(_.metadata.get("gemma.mcp_catalog_digest")) should not be empty
        result.toOption.toVector.flatMap(_.metadata.values).exists(_.contains("station context")) shouldBe false
      }

      "execute admitted CNCF tools through a provider-neutral prompt loop" in {
        Given("one admitted MCP tool and a plain generation provider")
        given ExecutionContext = ExecutionContext.create()
        val fixture = _fixture()
        val service = new _PromptLoopGenerateService(
          Map(
            "openai.usage.input_tokens" -> "3",
            "openai.usage.output_tokens" -> "2",
            "openai.usage.total_tokens" -> "5"
          ),
          Map(
            "openai.usage.input_tokens" -> "4",
            "openai.usage.output_tokens" -> "3",
            "openai.usage.total_tokens" -> "7"
          )
        )

        When("the runtime asks for one question list and synthesizes admitted evidence")
        val result = ToolOrchestrator.generatePromptLoopC(
          service,
          Some(fixture.invocation),
          None,
          "Create a grounded route candidate.",
          None,
          Some(128),
          Vector(
            Property("ai.tools", "web_search", None),
            Property("textus.ai.tools", "web_search", None),
            Property("cncf.ai.tools", "web_search", None),
            Property("tools", "web_search", None)
          ),
          "openai"
        )

        Then("MCP execution stays outside the provider and native tools are not mixed into the loop")
        result.toOption.map(_.text) shouldBe Some("Grounded route candidate")
        fixture.calls.map(_.toolIdentity.print) shouldBe Vector("research/places.lookup")
        service.prompts.size shouldBe 2
        service.prompts(1) should include ("station context")
        val toolpropertynames = Set("ai.tools", "textus.ai.tools", "cncf.ai.tools", "tools")
        service.properties.flatten.exists(property => toolpropertynames.contains(property.name)) shouldBe false
        result.toOption.flatMap(_.metadata.get("openai.prompt_loop_calls")) shouldBe Some("1")
        result.toOption.flatMap(_.metadata.get("openai.mcp_calls")) shouldBe Some("1")
        result.toOption.flatMap(_.metadata.get("openai.usage.input_tokens")) shouldBe Some("7")
        result.toOption.flatMap(_.metadata.get("openai.usage.output_tokens")) shouldBe Some("5")
        result.toOption.flatMap(_.metadata.get("openai.usage.total_tokens")) shouldBe Some("12")
      }

      "compose internal Operation and remote MCP catalogs without identity collapse" in {
        Given("separate admitted internal and remote catalogs containing one tool each")
        given ExecutionContext = ExecutionContext.create()
        val mcpfixture = _fixture()
        val subsystem = DefaultSubsystemFactory.default(Some("textus-ai-operation-tool-composition"))
        val identity = OperationToolIdentity.createC(BuiltinComponentIdentity.ADMIN.name, "system", "ping").toOption.get
        val setid = OperationToolSetId.parseC("builtin-tools").toOption.get
        val limits = OperationToolLimits.createC(4, 4096, 4096, 1).toOption.get
        val admission = OperationToolAdmission.createC(setid, Vector(identity), limits).toOption.get
        val catalog = OperationToolCatalogBuilder.createC(subsystem, admission).toOption.get
        val operationinvocation = new _OperationInvocation(catalog)
        val service = new _TwoSourceToolService

        When("Textus AI projects and executes both sources in one provider-neutral loop")
        val result = ToolOrchestrator.generateC(
          service,
          Some(mcpfixture.invocation),
          Some(operationinvocation),
          "Use both admitted sources.",
          None,
          Some(128),
          Vector.empty,
          "anthropic"
        )

        Then("source-specific names remain distinct and each call uses its owning invocation boundary")
        result.toOption.map(_.text) shouldBe Some("Grounded answer from both sources")
        service.tooldefinitions.map(_.name).exists(_.startsWith("mcp_")) shouldBe true
        service.tooldefinitions.map(_.name).exists(_.startsWith("operation_")) shouldBe true
        service.tooldefinitions.map(_.name).distinct.size shouldBe service.tooldefinitions.size
        operationinvocation.calls.map(_.identity.print) shouldBe Vector("org.goldenport.cncf.Admin.system.ping")
        mcpfixture.calls.map(_.toolIdentity.print) shouldBe Vector("research/places.lookup")
        result.toOption.flatMap(_.metadata.get("anthropic.tool_catalog_digest")) should not be empty
        result.toOption.flatMap(_.metadata.get("anthropic.tool_calls")) shouldBe Some("2")
        result.toOption.flatMap(_.metadata.get("anthropic.mcp_calls")) shouldBe Some("1")
        result.toOption.flatMap(_.metadata.get("anthropic.operation_calls")) shouldBe Some("1")
        subsystem.shutdown()
      }

    }

    "admission and resource bounds" which {
      "reject a model-requested function outside the admitted catalog" in {
        Given("a model response naming an unadmitted function")
        given ExecutionContext = ExecutionContext.create()
        val fixture = _fixture()
        val service = new _UnknownToolService

        When("the runtime resolves the response")
        val result = ToolOrchestrator.generateC(
          service,
          fixture.invocation,
          "Find the route context.",
          None,
          Some(128),
          Vector.empty,
          "gemma"
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
        val result = ToolOrchestrator.generateC(
          service,
          fixture.invocation,
          "Find the route context.",
          None,
          Some(128),
          Vector.empty,
          "gemma"
        )

        Then("the request is rejected without invoking any MCP tool")
        result.isFaillure shouldBe true
        fixture.calls shouldBe Vector.empty
      }

      "derive a conservative admission bound for every possible tool continuation" in {
        Given("a bounded initial prompt and per-turn output policy")
        val initial = AiInputTokenEstimate(10L, 10L, 0, 0L)

        When("the tool-loop admission envelope is calculated")
        val input = ToolOrchestrator.admissionInputEstimate(initial, Some(100))
        val output = ToolOrchestrator.admissionMaxOutputTokensC(Some(100)).toOption.flatten

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
        val result = ToolOrchestrator.generateC(
          service,
          fixture.invocation,
          "Find the route context.",
          None,
          Some(128),
          Vector.empty,
          "gemma"
        )

        Then("the tool message remains valid UTF-8 within its fixed byte limit")
        result.isSuccess shouldBe true
        service.secondturnmessages.last.content.getBytes(StandardCharsets.UTF_8).length should be <= 16384
      }

    }

    "runtime socket integration" which {
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

  private final class _OperationInvocation(
    value: OperationToolCatalog
  ) extends OperationToolInvocation {
    var calls: Vector[OperationToolCall] = Vector.empty

    def catalog: Consequence[OperationToolCatalog] = Consequence.success(value)

    def invoke(call: OperationToolCall)(using ExecutionContext): Consequence[OperationToolResult] = {
      calls = calls :+ call
      Consequence.success(OperationToolResult(OperationResponse.Scalar("internal context")))
    }
  }

  private final class _TwoSourceToolService extends ToolCallingChatService {
    private var _turn = 0
    var tooldefinitions: Vector[ToolDefinition] = Vector.empty

    def chat(req: ChatRequest): Consequence[ChatResponse] =
      Consequence.success(ChatResponse(Message(MessageRole.Assistant, "unused")))

    def chatWithTools(req: ToolChatRequest): Consequence[ToolChatResponse] = {
      _turn += 1
      tooldefinitions = req.tools
      if (_turn == 1) {
        val function = req.tools.find(_.name.startsWith("operation_")).getOrElse(fail("Operation function missing"))
        Consequence.success(ToolChatResponse(
          ToolChatMessage("assistant", toolCalls = Vector(ToolCall(function.name, Json.obj()))),
          Some("fixture")
        ))
      } else if (_turn == 2) {
        val function = req.tools.find(_.name.startsWith("mcp_")).getOrElse(fail("MCP function missing"))
        Consequence.success(ToolChatResponse(
          ToolChatMessage(
            "assistant",
            toolCalls = Vector(ToolCall(function.name, Json.obj("query" -> Json.fromString("Nakano"))))
          ),
          Some("fixture")
        ))
      } else
        Consequence.success(ToolChatResponse(
          ToolChatMessage("assistant", "Grounded answer from both sources"),
          Some("fixture")
        ))
    }
  }

  private final class _ToolService extends ToolCallingChatService {
    private var _turn = 0
    var tooldefinitions: Vector[ToolDefinition] = Vector.empty
    var secondturnmessages: Vector[ToolChatMessage] = Vector.empty

    def chat(req: ChatRequest): Consequence[ChatResponse] =
      Consequence.success(ChatResponse(Message(MessageRole.Assistant, "unused")))

    def chatWithTools(req: ToolChatRequest): Consequence[ToolChatResponse] = {
      _turn += 1
      tooldefinitions = req.tools
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
        secondturnmessages = req.messages
        Consequence.success(ToolChatResponse(
          ToolChatMessage("assistant", "Grounded answer: station context"),
          Some("fixture"),
          Map("gemma.usage.input_tokens" -> "4", "gemma.usage.output_tokens" -> "3", "gemma.usage.total_tokens" -> "7")
        ))
      }
    }
  }

  private final class _PromptLoopGenerateService(
    plannermetadata: Map[String, String],
    resultmetadata: Map[String, String]
  ) extends GenerateService {
    private val _function = "\"name\":\"(mcp_[a-f0-9]+)\"".r
    var prompts: Vector[String] = Vector.empty
    var properties: Vector[Vector[Property]] = Vector.empty

    def generate(req: GenerateRequest): Consequence[GenerateResponse] = {
      prompts = prompts :+ req.prompt
      properties = properties :+ req.properties
      if (prompts.size == 1) {
        val name = _function.findFirstMatchIn(req.prompt).map(_.group(1)).getOrElse(fail("MCP catalog name missing"))
        Consequence.success(GenerateResponse(
          s"{\"questions\":[{\"question\":\"What station context is relevant?\",\"tool\":\"$name\",\"arguments\":{}}]}",
          Some("fixture"),
          plannermetadata
        ))
      } else
        Consequence.success(GenerateResponse("Grounded route candidate", Some("fixture"), resultmetadata))
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
