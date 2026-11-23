package org.simplemodeling.textus.ai.runtime

import java.net.URI
import io.circe.Json
import org.goldenport.Consequence
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.http.UrlConnectionHttpDriver
import org.goldenport.cncf.observability.ConclusionDiagnostics
import org.goldenport.protocol.Property
import scala.util.control.NonFatal

/** Executes one bounded POST through the host's CNCF interpreter. The
  * destination still requires an explicit HTTP grant on that host.
  *
  * @since Nov. 23, 2026
  */
private[ai] final class AiTaskManagedHttpPost(
  endpoint: URI,
  path: String,
  headers: Vector[(String, String)],
  context: ExecutionContext
) extends AiTaskManagedPost {
  private lazy val qualifiedContext = try {
    Option.when(context.unitOfWork.httpDriver.isInstanceOf[UrlConnectionHttpDriver])(context)
  } catch { case NonFatal(_) => None }

  override def isQualified: Boolean = qualifiedContext.nonEmpty

  override def post(body: Json, timeoutMillis: Long, maxResponseBytes: Int): Either[String, (Int, Option[String], String)] =
    postObserved(body, timeoutMillis, maxResponseBytes) match {
      case AiTaskManagedPostResult.Response(status, requestId, response) => Right((status, requestId, response))
      case _ => Left("Managed HTTP did not yield a response")
    }

  override def postObserved(body: Json, timeoutMillis: Long, maxResponseBytes: Int): AiTaskManagedPostResult =
    qualifiedContext match {
      case None => AiTaskManagedPostResult.NotDispatched
      case Some(managed) =>
        given ExecutionContext = managed
        val properties = Vector(
          Property("http.total-timeout-ms", timeoutMillis.toString, None),
          Property("http.max-response-bytes", maxResponseBytes.toString, None),
          Property("http.follow-redirects", "false", None)
        )
        val timeoutSeconds = (timeoutMillis + 999L) / 1000L
        HttpSupport.postResponse(endpoint, path, body, timeoutSeconds, headers, properties) match {
          case Consequence.Success(response) =>
            AiTaskManagedPostResult.Response(response.code, response.headerValue("x-request-id"), response.getString.getOrElse(""))
          case Consequence.Failure(conclusion) =>
            val policy = ConclusionDiagnostics.classify(conclusion).policy
            if (policy.exists(Set("http.execution.unconfigured", "http.execution.component", "http.execution.grant",
              "http.execution.method", "http.execution.destination", "http.execution.limits", "http.execution.redirect")))
              AiTaskManagedPostResult.NotDispatched
            else AiTaskManagedPostResult.PossiblyDispatched
        }
    }
}
