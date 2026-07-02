package org.simplemodeling.textus.ai.runtime

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration

import io.circe.Json
import io.circe.parser.parse
import org.goldenport.Consequence

private[textus] object HttpSupport:
  def client(timeoutSeconds: Long): HttpClient =
    HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(timeoutSeconds))
      .build()

  def post(
    client: HttpClient,
    endpoint: URI,
    path: String,
    body: Json,
    timeoutSeconds: Long,
    headers: Vector[(String, String)] = Vector.empty
  ): Consequence[Json] =
    val builder =
      HttpRequest.newBuilder(endpoint.resolve(path))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(timeoutSeconds))
    val request =
      headers.foldLeft(builder) { case (z, (k, v)) => z.header(k, v) }
        .POST(HttpRequest.BodyPublishers.ofString(body.noSpaces, StandardCharsets.UTF_8))
        .build()
    try
      val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      if response.statusCode() / 100 != 2 then
        Consequence.serviceUnavailable(s"HTTP ${response.statusCode()} for ${endpoint.resolve(path)}: ${response.body()}")
      else
        parse(response.body()) match
          case Left(e) => Consequence.valueInvalid(s"Invalid JSON response: ${e.getMessage}")
          case Right(json) => Consequence.success(json)
    catch
      case e: Exception => Consequence.serviceUnavailable(e.getMessage)
