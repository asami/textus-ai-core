package org.simplemodeling.textus.ai.provider.openai

import java.time.{Instant, ZoneOffset}
import org.goldenport.configuration.{Configuration, ConfigurationOrigin, ConfigurationResolution, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import io.circe.Json

final class OpenAiHostEvidenceVerifierSpec extends AnyWordSpec with Matchers {
  "Host evidence" should {
    "require a host file origin and match current independent group and sizing evidence" in {
      val now = Instant.now()
      val day = now.atZone(ZoneOffset.UTC).toLocalDate
      val from = now.minusSeconds(60)
      val until = now.plusSeconds(300)
      val group = Json.obj(
        "organization" -> Json.fromString("org"), "project" -> Json.fromString("project"),
        "model" -> Json.fromString("model"), "modelPool" -> Json.fromString("group"),
        "day" -> Json.fromString(day.toString), "limitTokens" -> Json.fromLong(1000),
        "externalTokens" -> Json.fromLong(25), "validFrom" -> Json.fromString(from.toString),
        "expiresAt" -> Json.fromString(until.toString), "allocationProvenance" -> Json.fromString("allocation"),
        "externalProvenance" -> Json.fromString("conservative-bound"), "inputTokenCeiling" -> Json.fromLong(100),
        "maxOutputTokens" -> Json.fromInt(20))
      val sizing = Json.obj("endpoint" -> Json.fromString("https://api.openai.com"),
        "model" -> Json.fromString("model"), "format" -> Json.fromString("format"),
        "tokensPerByte" -> Json.fromLong(2), "overheadTokens" -> Json.fromLong(10),
        "provenance" -> Json.fromString("sizing"), "validFrom" -> Json.fromString(from.toString),
        "expiresAt" -> Json.fromString(until.toString))
      val value = ConfigurationValue.StringValue(Json.obj("version" -> Json.fromInt(1),
        "groups" -> Json.arr(group), "sizing" -> Json.arr(sizing)).noSpaces)
      val key = OpenAiHostEvidenceVerifier.configurationKey
      val source = java.nio.file.Path.of(System.getProperty("user.home"), ".textus", "config.json").toString
      def config(origin: ConfigurationOrigin, v: ConfigurationValue = value, path: String = source) =
        ResolvedConfiguration(Configuration(Map(key -> v)), ConfigurationTrace(Map(key ->
          ConfigurationResolution(key, v, origin, Nil, Some("file"), Some(path)))))
      val verifier = OpenAiHostEvidenceVerifier.fromConfiguration(config(ConfigurationOrigin.Home)).get
      def allocation(external: Long = 25, expires: Instant = until) = verifier.verify(
        "org", "project", "model", "group", day, 1000, external, from, expires,
        "allocation", "conservative-bound", 100, 20)
      allocation() shouldBe true
      allocation(external = 24) shouldBe false
      allocation(expires = now.minusSeconds(1)) shouldBe false
      verifier.verify("https://api.openai.com", "model", "format", 2, 10, "sizing") shouldBe true
      verifier.verify("https://api.openai.com", "model", "other", 2, 10, "sizing") shouldBe false
      verifier.verify("https://api.openai.com", "model", "format", 2, 0, "sizing") shouldBe false
      OpenAiHostEvidenceVerifier.fromConfiguration(config(ConfigurationOrigin.Environment)) shouldBe None
      OpenAiHostEvidenceVerifier.fromConfiguration(config(ConfigurationOrigin.Project)) shouldBe None
      OpenAiHostEvidenceVerifier.fromConfiguration(config(ConfigurationOrigin.Home, path = "/tmp/untrusted")) shouldBe None
      OpenAiHostEvidenceVerifier.fromConfiguration(ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty)) shouldBe None
      val expired = Json.obj("version" -> Json.fromInt(1), "groups" -> Json.arr(group),
        "sizing" -> Json.arr(sizing.mapObject(_.add("expiresAt", Json.fromString(now.minusSeconds(1).toString)))))
      val old = OpenAiHostEvidenceVerifier.fromConfiguration(config(ConfigurationOrigin.Home,
        ConfigurationValue.StringValue(expired.noSpaces))).get
      old.verify("https://api.openai.com", "model", "format", 2, 10, "sizing") shouldBe false
      val expiredGroup = group.mapObject(_.add("expiresAt", Json.fromString(now.minusSeconds(1).toString)))
      val stale = Json.obj("version" -> Json.fromInt(1), "groups" -> Json.arr(expiredGroup), "sizing" -> Json.arr(sizing))
      val staleVerifier = OpenAiHostEvidenceVerifier.fromConfiguration(config(ConfigurationOrigin.Home,
        ConfigurationValue.StringValue(stale.noSpaces))).get
      staleVerifier.verify("org", "project", "model", "group", day, 1000, 25, from,
        now.minusSeconds(1), "allocation", "conservative-bound", 100, 20) shouldBe false
      val duplicated = Json.obj("version" -> Json.fromInt(1), "groups" -> Json.arr(group, group), "sizing" -> Json.arr(sizing, sizing))
      val duplicateVerifier = OpenAiHostEvidenceVerifier.fromConfiguration(config(ConfigurationOrigin.Home,
        ConfigurationValue.StringValue(duplicated.noSpaces))).get
      duplicateVerifier.verify("org", "project", "model", "group", day, 1000, 25, from,
        until, "allocation", "conservative-bound", 100, 20) shouldBe false
      duplicateVerifier.verify("https://api.openai.com", "model", "format", 2, 10, "sizing") shouldBe false
      val conflictingGroup = group.mapObject(_.add("externalTokens", Json.fromLong(100))
        .add("limitTokens", Json.fromLong(900)).add("validFrom", Json.fromString(now.minusSeconds(30).toString)))
      val conflictingSizing = sizing.mapObject(_.add("tokensPerByte", Json.fromLong(3))
        .add("overheadTokens", Json.fromLong(20)).add("validFrom", Json.fromString(now.minusSeconds(30).toString)))
      val conflicting = Json.obj("version" -> Json.fromInt(1), "groups" -> Json.arr(group, conflictingGroup),
        "sizing" -> Json.arr(sizing, conflictingSizing))
      val conflictingVerifier = OpenAiHostEvidenceVerifier.fromConfiguration(config(ConfigurationOrigin.Home,
        ConfigurationValue.StringValue(conflicting.noSpaces))).get
      conflictingVerifier.verify("org", "project", "model", "group", day, 1000, 25, from,
        until, "allocation", "conservative-bound", 100, 20) shouldBe false
      conflictingVerifier.verify("https://api.openai.com", "model", "format", 2, 10, "sizing") shouldBe false
      val previousGroup = group.mapObject(_.add("validFrom", Json.fromString(now.minusSeconds(120).toString))
        .add("expiresAt", Json.fromString(now.minusSeconds(90).toString)).add("externalTokens", Json.fromLong(100)))
      val previousSizing = sizing.mapObject(_.add("validFrom", Json.fromString(now.minusSeconds(120).toString))
        .add("expiresAt", Json.fromString(now.minusSeconds(90).toString)).add("tokensPerByte", Json.fromLong(3)))
      val historical = Json.obj("version" -> Json.fromInt(1), "groups" -> Json.arr(group, previousGroup),
        "sizing" -> Json.arr(sizing, previousSizing))
      val historicalVerifier = OpenAiHostEvidenceVerifier.fromConfiguration(config(ConfigurationOrigin.Home,
        ConfigurationValue.StringValue(historical.noSpaces))).get
      historicalVerifier.verify("org", "project", "model", "group", day, 1000, 25, from,
        until, "allocation", "conservative-bound", 100, 20) shouldBe true
      historicalVerifier.verify("https://api.openai.com", "model", "format", 2, 10, "sizing") shouldBe true
      val traced = config(ConfigurationOrigin.Home)
      val replaced = ResolvedConfiguration(Configuration(Map(key -> ConfigurationValue.StringValue("different"))), traced.trace)
      OpenAiHostEvidenceVerifier.fromConfiguration(replaced) shouldBe None
    }
  }
}
