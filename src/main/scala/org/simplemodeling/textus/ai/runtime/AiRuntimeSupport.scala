package org.simplemodeling.textus.ai.runtime

import java.net.URI

import io.circe.Json
import io.circe.parser.parse
import org.goldenport.Consequence
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.SpiSelection
import org.goldenport.cncf.spi.ai.runner.AiTool
import org.goldenport.cncf.unitofwork.UnitOfWorkOp
import org.goldenport.protocol.Property

private[textus] object AiRequestProperties:
  def model(
    properties: Vector[Property],
    provider: String
  ): Option[String] = {
    val normalized = provider.trim.toLowerCase(java.util.Locale.ROOT)
    val providernames = normalized match {
      case "codex" | "codex-cli" => Vector("codex", "codex-cli")
      case value => Vector(value)
    }
    _property_string(properties, providernames.flatMap { name => Vector(
      s"ai.$name.model",
      s"textus.ai.$name.model",
      s"cncf.ai.$name.model"
    ) } ++ Vector(
      "ai.model",
      "textus.ai.model",
      "cncf.ai.model",
      "model"
    ))
  }

  def effectiveModel(
    configured: String,
    properties: Vector[Property],
    provider: String
  ): String =
    model(properties, provider).getOrElse(configured)

  def requireNoModelOverride(
    provider: String,
    properties: Vector[Property]
  ): Consequence[Unit] =
    if (model(properties, provider).nonEmpty)
      Consequence.configurationInvalid(s"AI model override is not supported by provider '$provider'")
    else
      Consequence.unit

  def timeoutSeconds(
    properties: Vector[Property]
  ): Option[Long] =
    _property_string(properties, Vector(
      "ai.timeout-seconds",
      "textus.ai.timeout-seconds",
      "cncf.ai.timeout-seconds",
      "textus.ai.google.timeout-seconds",
      "cncf.ai.google.timeout-seconds",
      "textus.ai.openai.timeout-seconds",
      "cncf.ai.openai.timeout-seconds",
      "timeout-seconds",
      "timeoutSeconds"
    )).flatMap(_.toLongOption).filter(_ > 0)

  def effectiveTimeoutSeconds(
    configured: Long,
    properties: Vector[Property]
  ): Long =
    timeoutSeconds(properties).getOrElse(configured)

  def string(
    properties: Vector[Property],
    names: Vector[String]
  ): Option[String] =
    _property_string(properties, names)

  def boolean(
    properties: Vector[Property],
    names: Vector[String]
  ): Option[Boolean] =
    _property_string(properties, names).flatMap { value =>
      value.toLowerCase(java.util.Locale.ROOT) match
        case "true" | "yes" | "on" | "1" => Some(true)
        case "false" | "no" | "off" | "0" => Some(false)
        case _ => None
    }

  def tools(
    properties: Vector[Property]
  ): Vector[AiTool] =
    _tool_parse_result(properties).map(_.tools).getOrElse(Vector.empty)

  def validateTools(
    properties: Vector[Property]
  ): Consequence[Vector[AiTool]] =
    _tool_parse_result(properties) match
      case Some(result) if result.unknown.nonEmpty =>
        Consequence.configurationInvalid(s"Unknown AI tools: ${result.unknown.mkString(",")}")
      case Some(result) =>
        Consequence.success(result.tools)
      case None =>
        Consequence.success(Vector.empty)

  private def _tool_parse_result(
    properties: Vector[Property]
  ): Option[AiTool.ParseResult] =
    _property_string(properties, Vector(
      "ai.tools",
      "textus.ai.tools",
      "cncf.ai.tools",
      "tools"
    )).map(AiTool.parseResult)

  def requireNoUnsupportedTools(
    provider: String,
    properties: Vector[Property]
  ): Consequence[Unit] =
    validateTools(properties).flatMap { requested =>
      if (requested.isEmpty)
        Consequence.success(())
      else
        Consequence.configurationInvalid(
          s"AI tools are not supported by provider '$provider': ${requested.map(_.id).mkString(",")}"
        )
    }

  private def _property_string(
    properties: Vector[Property],
    names: Vector[String]
  ): Option[String] =
    val keys = names.map(_.toLowerCase(java.util.Locale.ROOT)).toSet
    properties.collectFirst {
      case Property(name, value, _) if keys.contains(name.toLowerCase(java.util.Locale.ROOT)) =>
        String.valueOf(value).trim
    }.filter(_.nonEmpty)

private[textus] object AiProviderAdmission:
  def validate(
    selection: SpiSelection,
    properties: Vector[Property]
  ): Consequence[Unit] =
    for {
      tools <- AiRequestProperties.validateTools(properties)
      _ <- _validate_tools(_provider(selection), tools)
      _ <- _validate_model_override(_provider(selection), properties)
    } yield ()

  private def _provider(selection: SpiSelection): String =
    selection.provider
      .map(_.trim.toLowerCase(java.util.Locale.ROOT))
      .filter(_.nonEmpty)
      .getOrElse("gemma") match {
      case "codex-cli" => "codex"
      case value => value
    }

  private def _validate_tools(
    provider: String,
    tools: Vector[AiTool]
  ): Consequence[Unit] =
    if (tools.isEmpty || provider == "google" || provider == "openai")
      Consequence.unit
    else
      Consequence.configurationInvalid(
        s"AI tools are not supported by provider '$provider': ${tools.map(_.id).mkString(",")}"
      )

  private def _validate_model_override(
    provider: String,
    properties: Vector[Property]
  ): Consequence[Unit] =
    if (provider == "codex")
      AiRequestProperties.requireNoModelOverride(provider, properties)
    else
      Consequence.unit

private[textus] object HttpSupport:
  private val _sensitive_query_parameters =
    Vector("key", "api_key", "apiKey", "token", "access_token", "authorization")

  def redactSensitive(value: String): String =
    _sensitive_query_parameters.foldLeft(Option(value).getOrElse("")) { (z, key) =>
      z.replaceAll(s"(?i)([?&]${java.util.regex.Pattern.quote(key)}=)[^&\\s]+", "$1<redacted>")
    }

  def post(
    endpoint: URI,
    path: String,
    body: Json,
    timeoutseconds: Long,
    headers: Vector[(String, String)] = Vector.empty,
    properties: Vector[Property] = Vector.empty
  )(using ctx: ExecutionContext): Consequence[Json] = {
    val url = endpoint.resolve(path).toString
    val effectiveheaders = headers.toMap + ("Content-Type" -> "application/json")
    val effectiveproperties =
      properties :+ Property("http.timeout-seconds", timeoutseconds.toString, None)
    ctx.runtime.unitOfWorkInterpreter(UnitOfWorkOp.HttpPost(url, Some(body.noSpaces), effectiveheaders, effectiveproperties)).flatMap { response =>
      if response.code / 100 != 2 then
        Consequence.serviceUnavailable(
          _failure_message(response.code, response.getString.getOrElse(""))
        )
      else
        parse(response.getString.getOrElse("")) match
          case Left(e) => Consequence.valueInvalid(s"Invalid JSON response: ${e.getMessage}")
          case Right(json) => Consequence.success(json)
    }
  }

  private def _failure_message(status: Int, body: String): String =
    s"AI provider request failed: category=${failureCategory(status, body)} status=$status"

  def failureCategory(status: Int, body: String): String = {
    val normalized = Option(body).getOrElse("").toLowerCase(java.util.Locale.ROOT)
    status match {
      case 400 => "invalid_request"
      case 401 | 403 => "authentication_failed"
      case 404 => "model_unavailable"
      case 408 | 504 => "timeout"
      case 429 if normalized.contains("quota") || normalized.contains("resource_exhausted") => "quota_exhausted"
      case 429 => "rate_limited"
      case code if code >= 500 => "unavailable"
      case _ => "provider_rejected"
    }
  }
