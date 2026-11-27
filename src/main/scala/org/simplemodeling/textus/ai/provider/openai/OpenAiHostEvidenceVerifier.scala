package org.simplemodeling.textus.ai.provider.openai

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.util.Try
import scala.util.control.NonFatal
import io.circe.{Json, parser}
import org.goldenport.configuration.{ConfigurationOrigin, ConfigurationValue, ResolvedConfiguration}

/** Evidence is captured from the owning host's home configuration, never from
  * request claims. The host is responsible for maintaining its conservative
  * external-use and sizing evidence and protecting this configuration file.
  */
private[ai] final class OpenAiHostEvidenceVerifier private (
  evidence: Json,
  clock: () => Instant = () => Instant.now()
) extends OpenAiGroupAllocationVerifier with OpenAiRequestSizingVerifier {
  private def document: Option[Json] = Some(evidence)
  private def fields(j: Json, names: Set[String]): Boolean = j.asObject.exists(_.keys.toSet == names)
  private def str(j: Json, name: String): Option[String] = j.hcursor.get[String](name).toOption.filter(s =>
    s.nonEmpty && s == s.trim && s.length <= 512 && !s.exists(_.isControl))
  private def num(j: Json, name: String): Option[Long] = j.hcursor.get[Long](name).toOption
  private def instant(j: Json, name: String): Option[Instant] = str(j, name).flatMap(s => Try(Instant.parse(s)).toOption)
  private def active(from: Instant, until: Instant, now: Instant): Boolean =
    from.isBefore(until) && !now.isBefore(from) && now.isBefore(until)

  def verify(organization: String, project: String, model: String, modelPool: String,
      day: LocalDate, limitTokens: Long, externalTokens: Long, validFrom: Instant, expiresAt: Instant,
      allocationProvenance: String, externalProvenance: String, inputTokenCeiling: Long,
      maxOutputTokens: Int): Boolean = try {
    val now = clock()
    val subjects = document.toVector.flatMap(_.hcursor.get[Vector[Json]]("groups").toOption.toVector.flatten).filter { j =>
      str(j, "organization").contains(organization) && str(j, "project").contains(project) &&
      str(j, "model").contains(model) && str(j, "modelPool").contains(modelPool) &&
      str(j, "day").flatMap(s => Try(LocalDate.parse(s)).toOption).contains(day)
    }
    def window(j: Json): Option[Boolean] = for {
      from <- instant(j, "validFrom")
      until <- instant(j, "expiresAt")
      if from.isBefore(until)
    } yield active(from, until, now)
    val current = subjects.filter(j => window(j).contains(true))
    subjects.forall(j => window(j).isDefined) && current.size == 1 && current.exists { j =>
      fields(j, Set("organization", "project", "model", "modelPool", "day", "limitTokens", "externalTokens",
        "validFrom", "expiresAt", "allocationProvenance", "externalProvenance", "inputTokenCeiling", "maxOutputTokens")) &&
      day == now.atZone(ZoneOffset.UTC).toLocalDate && limitTokens > 0 && externalTokens >= 0 &&
      externalTokens <= limitTokens && inputTokenCeiling > 0 && maxOutputTokens > 0 &&
      num(j, "limitTokens").contains(limitTokens) && num(j, "externalTokens").contains(externalTokens) &&
      num(j, "inputTokenCeiling").contains(inputTokenCeiling) && num(j, "maxOutputTokens").contains(maxOutputTokens.toLong) &&
      instant(j, "validFrom").contains(validFrom) && instant(j, "expiresAt").contains(expiresAt) &&
      active(validFrom, expiresAt, now) &&
      str(j, "allocationProvenance").contains(allocationProvenance) && str(j, "externalProvenance").contains(externalProvenance)
    }
  } catch { case NonFatal(_) => false }

  def verify(endpoint: String, model: String, format: String, tokensPerByte: Long,
      overheadTokens: Long, provenance: String): Boolean = try {
    val now = clock()
    val subjects = document.toVector.flatMap(_.hcursor.get[Vector[Json]]("sizing").toOption.toVector.flatten).filter { j =>
      str(j, "endpoint").contains(endpoint) && str(j, "model").contains(model) && str(j, "format").contains(format)
    }
    def window(j: Json): Option[Boolean] = for {
      from <- instant(j, "validFrom")
      until <- instant(j, "expiresAt")
      if from.isBefore(until)
    } yield active(from, until, now)
    val current = subjects.filter(j => window(j).contains(true))
    subjects.forall(j => window(j).isDefined) && current.size == 1 && current.exists { j =>
      fields(j, Set("endpoint", "model", "format", "tokensPerByte", "overheadTokens", "provenance", "validFrom", "expiresAt")) &&
      tokensPerByte > 0 && overheadTokens > 0 && num(j, "tokensPerByte").contains(tokensPerByte) &&
      num(j, "overheadTokens").contains(overheadTokens) && str(j, "provenance").contains(provenance) &&
      (for { from <- instant(j, "validFrom"); until <- instant(j, "expiresAt") } yield active(from, until, now)).contains(true)
    }
  } catch { case NonFatal(_) => false }
}

private[ai] object OpenAiHostEvidenceVerifier {
  val configurationKey = "textus.ai.bounded-task.openai-host-evidence"

  /** Accept only a resolved value from an absolute home configuration file.
    * Do not recover an earlier trusted value after a later override.
    */
  def fromConfiguration(configuration: ResolvedConfiguration): Option[OpenAiHostEvidenceVerifier] = try {
    for {
      resolution <- configuration.trace.get(configurationKey)
      if configuration.configuration.values.get(configurationKey).contains(resolution.finalValue)
      if resolution.origin == ConfigurationOrigin.Home && resolution.sourceType.contains("file")
      source <- resolution.sourceId
      path <- Try(java.nio.file.Path.of(source).toAbsolutePath.normalize()).toOption
      home <- Option(System.getProperty("user.home")).flatMap(s => Try(java.nio.file.Path.of(s).toAbsolutePath.normalize()).toOption)
      if java.nio.file.Path.of(source).isAbsolute && path.startsWith(home.resolve(".textus"))
      raw <- resolution.finalValue match { case ConfigurationValue.StringValue(v) => Some(v); case _ => None }
      if raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 65536
      json <- parser.parse(raw).toOption
      obj <- json.asObject
      if obj.keys.toSet == Set("version", "groups", "sizing")
      if json.hcursor.get[Int]("version").toOption.contains(1)
      groups <- json.hcursor.get[Vector[Json]]("groups").toOption
      sizing <- json.hcursor.get[Vector[Json]]("sizing").toOption
      if groups.size <= 64 && sizing.size <= 64
    } yield new OpenAiHostEvidenceVerifier(json)
  } catch { case NonFatal(_) => None }
}
