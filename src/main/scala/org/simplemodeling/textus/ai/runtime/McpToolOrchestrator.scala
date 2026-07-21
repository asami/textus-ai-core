package org.simplemodeling.textus.ai.runtime

import java.nio.charset.StandardCharsets
import io.circe.{Json, JsonObject}
import org.goldenport.Consequence
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.mcp.client.*
import org.goldenport.protocol.Property
import org.simplemodeling.textus.ai.ai.*

/*
 * Bounded bridge from an admitted CNCF MCP catalog to a tool-capable model.
 *
 * @since   Jul. 21, 2026
 * @version Jul. 21, 2026
 * @author  ASAMI, Tomoharu
 */
private[textus] object McpToolOrchestrator {
  private val MAXIMUM_TURNS = 4
  private val MAXIMUM_TOOL_CALLS = 8
  private val MAXIMUM_CATALOG_TOOLS = 32
  private val MAXIMUM_FUNCTION_DEFINITION_BYTES = 4096
  private val MAXIMUM_TOOL_ARGUMENT_BYTES = 16384
  private val MAXIMUM_TOOL_RESULT_BYTES = 16384
  private val MAXIMUM_ELAPSED_MILLIS = 120000L

  def admissionInputEstimate(
    initial: AiInputTokenEstimate,
    maxoutputtokens: Option[Int]
  ): AiInputTokenEstimate = {
    val turns = MAXIMUM_TURNS.toLong
    val catalog = MAXIMUM_CATALOG_TOOLS.toLong * MAXIMUM_FUNCTION_DEFINITION_BYTES
    val results = MAXIMUM_TOOL_CALLS.toLong * MAXIMUM_TOOL_RESULT_BYTES
    // Earlier assistant output is replayed as context on later continuation turns.
    val continuation = maxoutputtokens.map(_.toLong).getOrElse(0L) * turns * (turns - 1L) / 2L
    AiInputTokenEstimate(
      tokens = initial.tokens * turns + (catalog + results) * turns + continuation,
      payloadBytes = initial.payloadBytes,
      messageCount = initial.messageCount + MAXIMUM_TURNS,
      envelopeTokens = initial.envelopeTokens + turns * AiInputTokenEstimator.chatMessageEnvelopeTokens
    )
  }

  def admissionMaxOutputTokensC(
    perturn: Option[Int]
  ): Consequence[Option[Int]] = perturn match {
    case Some(value) =>
      try Consequence.success(Some(Math.toIntExact(Math.multiplyExact(value.toLong, MAXIMUM_TURNS.toLong))))
      catch {
        case _: ArithmeticException => Consequence.configurationInvalid(
          "MCP tool-loop output bound exceeds supported integer range"
        )
      }
    case None => Consequence.success(None)
  }

  def generateC(
    service: ToolCallingChatService,
    invocation: McpClientInvocation,
    prompt: String,
    temperature: Option[Double],
    maxTokens: Option[Int],
    properties: Vector[Property]
  )(using ExecutionContext): Consequence[GenerateResponse] =
    for {
      catalog <- invocation.catalog
      bindings <- _bindings_c(catalog)
      result <- _run_c(
        service,
        invocation,
        bindings,
        Vector(ToolChatMessage("user", prompt)),
        temperature,
        maxTokens,
        properties,
        turns = 0,
        calls = 0,
        metadata = Map.empty,
        startedatnanos = System.nanoTime()
      )
    } yield GenerateResponse(
      result.response.message.content,
      result.response.model,
      _aggregate_metadata(result.metadata, result.response.metadata) ++ Map(
        "gemma.mcp_calls" -> result.calls.toString,
        "gemma.mcp_turns" -> result.turns.toString,
        "gemma.mcp_catalog_digest" -> AiExecutionFacts.digest(
          bindings.map(_.tool.identity.print).mkString("\n")
        )
      )
    )

  private final case class _Binding(
    functionname: String,
    tool: McpClientTool,
    definition: ToolDefinition
  )

  private final case class _Result(
    response: ToolChatResponse,
    turns: Int,
    calls: Int,
    metadata: Map[String, String]
  )

  private def _bindings_c(catalog: McpClientCatalog): Consequence[Vector[_Binding]] = {
    if (catalog.tools.size > MAXIMUM_CATALOG_TOOLS)
      Consequence.operationIllegal(
        "ai.mcp-tool-catalog",
        s"MCP resource limit: catalog tool count ${catalog.tools.size} exceeds $MAXIMUM_CATALOG_TOOLS"
      )
    else {
      val bindings = catalog.tools.map { tool =>
        val suffix = AiExecutionFacts.digest(tool.identity.print).stripPrefix("sha256:").take(24)
        val description = _description(tool)
        val definition = ToolDefinition(s"mcp_$suffix", description, _schema_json(tool.inputSchema))
        _Binding(definition.name, tool, definition)
      }
      if (bindings.map(_.functionname).distinct.size != bindings.size)
        Consequence.configurationInvalid("MCP function-name mapping collision")
      else {
        bindings.find(binding => _definition_bytes(binding.definition) > MAXIMUM_FUNCTION_DEFINITION_BYTES) match {
          case Some(binding) => Consequence.operationIllegal(
            "ai.mcp-tool-catalog",
            s"MCP resource limit: function definition exceeds byte bound for ${binding.tool.identity.print}"
          )
          case None => Consequence.success(bindings)
        }
      }
    }
  }

  private def _run_c(
    service: ToolCallingChatService,
    invocation: McpClientInvocation,
    bindings: Vector[_Binding],
    messages: Vector[ToolChatMessage],
    temperature: Option[Double],
    maxTokens: Option[Int],
    properties: Vector[Property],
    turns: Int,
    calls: Int,
    metadata: Map[String, String],
    startedatnanos: Long
  )(using ExecutionContext): Consequence[_Result] =
    if (_elapsed_millis(startedatnanos) > MAXIMUM_ELAPSED_MILLIS)
      Consequence.operationIllegal(
        "ai.mcp-tool-loop",
        s"MCP resource limit: tool loop exceeded elapsed-time bound: $MAXIMUM_ELAPSED_MILLIS ms"
      )
    else if (turns >= MAXIMUM_TURNS)
      Consequence.operationIllegal(
        "ai.mcp-tool-loop",
        s"MCP tool loop exhausted bounded turns: $MAXIMUM_TURNS"
      )
    else {
      val definitions = bindings.map(_.definition)
      service.chatWithTools(ToolChatRequest(messages, definitions, temperature, maxTokens, properties)).flatMap { response =>
        val mergedmetadata = _aggregate_metadata(metadata, response.metadata)
        if (response.message.toolCalls.isEmpty)
          Consequence.success(_Result(response, turns + 1, calls, mergedmetadata))
        else {
          val byname = bindings.map(x => x.functionname -> x).toMap
          _invoke_calls_c(
            invocation,
            byname,
            response.message.toolCalls,
            calls,
            startedatnanos
          ).flatMap { toolmessages =>
            _run_c(
              service,
              invocation,
              bindings,
              messages ++ Vector(response.message) ++ toolmessages,
              temperature,
              maxTokens,
              properties,
              turns + 1,
              calls + response.message.toolCalls.size,
              mergedmetadata,
              startedatnanos
            )
          }
        }
      }
    }

  private def _invoke_calls_c(
    invocation: McpClientInvocation,
    bindings: Map[String, _Binding],
    calls: Vector[ToolCall],
    priorcalls: Int,
    startedatnanos: Long
  )(using ExecutionContext): Consequence[Vector[ToolChatMessage]] =
    if (calls.size + priorcalls > MAXIMUM_TOOL_CALLS)
      Consequence.operationIllegal(
        "ai.mcp-tool-loop",
        s"MCP resource limit: tool-loop call count ${calls.size + priorcalls} exceeds $MAXIMUM_TOOL_CALLS"
      )
    else calls.foldLeft(Consequence.success(Vector.empty[ToolChatMessage])) { case (z, call) =>
      for {
        messages <- z
        _ <- if (_elapsed_millis(startedatnanos) <= MAXIMUM_ELAPSED_MILLIS) Consequence.unit else
          Consequence.operationIllegal(
            "ai.mcp-tool-loop",
            s"MCP resource limit: tool loop exceeded elapsed-time bound: $MAXIMUM_ELAPSED_MILLIS ms"
          )
        binding <- bindings.get(call.name).map(Consequence.success).getOrElse(
          Consequence.configurationInvalid("Model requested an unadmitted MCP function")
        )
        _ <- if (_utf8_bytes(call.arguments) <= MAXIMUM_TOOL_ARGUMENT_BYTES) Consequence.unit else
          Consequence.operationIllegal(
            "ai.mcp-tool-loop",
            s"MCP resource limit: tool arguments exceed byte bound: $MAXIMUM_TOOL_ARGUMENT_BYTES"
          )
        arguments <- _mcp_object_c(call.arguments)
        request <- McpClientCall.createC(binding.tool.identity, arguments)
        result <- invocation.invoke(request)
      } yield messages :+ ToolChatMessage(
        role = "tool",
        toolName = Some(call.name),
        content = _render_result(result)
      )
    }

  private def _schema_json(schema: McpInputSchema): Json = schema match {
    case McpInputSchema.AnyValue => Json.obj()
    case McpInputSchema.NullValue => Json.obj("type" -> Json.fromString("null"))
    case McpInputSchema.StringValue => Json.obj("type" -> Json.fromString("string"))
    case McpInputSchema.BooleanValue => Json.obj("type" -> Json.fromString("boolean"))
    case McpInputSchema.IntegerValue => Json.obj("type" -> Json.fromString("integer"))
    case McpInputSchema.NumberValue => Json.obj("type" -> Json.fromString("number"))
    case McpInputSchema.ArrayValue(items) => Json.obj(
      "type" -> Json.fromString("array"),
      "items" -> _schema_json(items)
    )
    case McpInputSchema.ObjectValue(fields, additional) =>
      val required = fields.filter(_.required).map(_.name.print)
      Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.fromJsonObject(JsonObject.fromIterable(fields.map { field =>
          field.name.print -> _schema_json(field.schema).deepMerge(field.description.map(value =>
            Json.obj("description" -> Json.fromString(value.print))
          ).getOrElse(Json.obj()))
        })),
        "required" -> Json.fromValues(required.map(Json.fromString)),
        "additionalProperties" -> Json.fromBoolean(additional)
      )
  }

  private def _mcp_object_c(value: Json): Consequence[McpValue.ObjectValue] =
    value.asObject.map { fields =>
      fields.toVector.foldLeft(Consequence.success(Vector.empty[(McpFieldName, McpValue)])) {
        case (z, (name, child)) =>
          for {
            values <- z
            key <- McpFieldName.parseC(name)
            converted <- _mcp_value_c(child)
          } yield values :+ (key -> converted)
      }.flatMap(McpValue.objectC)
    }.getOrElse(Consequence.valueInvalid("MCP tool arguments must be a JSON object"))

  private def _mcp_value_c(value: Json): Consequence[McpValue] =
    value.fold(
      Consequence.success(McpValue.NullValue),
      value => Consequence.success(McpValue.BooleanValue(value)),
      number => number.toBigInt.map(value => Consequence.success(McpValue.IntegerValue(value))).getOrElse(
        number.toBigDecimal.map(value => Consequence.success(McpValue.NumberValue(value))).getOrElse(
          Consequence.valueInvalid("MCP tool argument number is not representable")
        )
      ),
      value => Consequence.success(McpValue.StringValue(value)),
      values => values.foldLeft(Consequence.success(Vector.empty[McpValue])) { (z, child) =>
        for values <- z; converted <- _mcp_value_c(child) yield values :+ converted
      }.map(McpValue.ArrayValue.apply),
      fields => _mcp_object_c(Json.fromJsonObject(fields)).map(value => value: McpValue)
    )

  private def _render_result(result: McpClientResult): String = {
    val content = result.content.flatMap {
      case McpClientContent.Text(value, _) => Vector(value)
      case McpClientContent.Structured(value, _) => Vector(_mcp_value_json(value).noSpaces)
      case McpClientContent.EmbeddedTextResource(_, text, _, _) => Vector(text)
      case _ => Vector.empty
    } ++ result.structuredContent.map(value => _mcp_value_json(value).noSpaces)
    _truncate_utf8(content.mkString("\n"), MAXIMUM_TOOL_RESULT_BYTES)
  }

  private def _description(tool: McpClientTool): Option[String] =
    Vector(tool.title, tool.description).flatten.map(_.print).filter(_.nonEmpty).mkString("\n") match {
      case "" => None
      case value => Some(value)
    }

  private def _definition_bytes(definition: ToolDefinition): Int =
    _utf8_bytes(Json.obj(
      "name" -> Json.fromString(definition.name),
      "description" -> definition.description.map(Json.fromString).getOrElse(Json.Null),
      "parameters" -> definition.inputSchema
    ))

  private def _utf8_bytes(value: Json): Int =
    value.noSpaces.getBytes(StandardCharsets.UTF_8).length

  private def _truncate_utf8(value: String, maximum: Int): String = {
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    if (bytes.length <= maximum) value
    else {
      val builder = new java.lang.StringBuilder
      var index = 0
      var used = 0
      while (index < value.length) {
        val codepoint = value.codePointAt(index)
        val text = new String(Character.toChars(codepoint))
        val size = text.getBytes(StandardCharsets.UTF_8).length
        if (used + size > maximum)
          return builder.toString
        builder.appendCodePoint(codepoint)
        used += size
        index += Character.charCount(codepoint)
      }
      builder.toString
    }
  }

  private def _elapsed_millis(startedatnanos: Long): Long =
    (System.nanoTime() - startedatnanos) / 1000000L

  private def _mcp_value_json(value: McpValue): Json = value match {
    case McpValue.NullValue => Json.Null
    case McpValue.StringValue(value) => Json.fromString(value)
    case McpValue.BooleanValue(value) => Json.fromBoolean(value)
    case McpValue.IntegerValue(value) => Json.fromBigInt(value)
    case McpValue.NumberValue(value) => Json.fromBigDecimal(value)
    case McpValue.ArrayValue(values) => Json.fromValues(values.map(_mcp_value_json))
    case McpValue.ObjectValue(fields) => Json.fromJsonObject(JsonObject.fromIterable(fields.map {
      case (name, value) => name.print -> _mcp_value_json(value)
    }))
  }

  private def _aggregate_metadata(
    before: Map[String, String],
    after: Map[String, String]
  ): Map[String, String] = {
    val usagekeys = Set(
      "gemma.usage.input_tokens",
      "gemma.usage.output_tokens",
      "gemma.usage.total_tokens"
    )
    (before.keySet ++ after.keySet).map { key =>
      val value = if (usagekeys.contains(key))
        Vector(before.get(key), after.get(key)).flatten.flatMap(_.toLongOption).sum.toString
      else after.getOrElse(key, before.getOrElse(key, ""))
      key -> value
    }.toMap
  }
}
