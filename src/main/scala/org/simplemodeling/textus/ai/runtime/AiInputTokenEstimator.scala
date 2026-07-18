package org.simplemodeling.textus.ai.runtime

import java.nio.charset.StandardCharsets

/*
 * Provider-neutral, bounded admission estimate for caller-supplied AI input.
 *
 * @since   Jul. 18, 2026
 * @version Jul. 18, 2026
 * @author  ASAMI, Tomoharu
 */
private[textus] final case class AiInputTokenEstimate(
  tokens: Long,
  payloadBytes: Long,
  messageCount: Int,
  envelopeTokens: Long
) {
  require(tokens >= 0, "AI input estimate must be non-negative")
  require(payloadBytes >= 0, "AI input byte count must be non-negative")
  require(messageCount >= 0, "AI input message count must be non-negative")
  require(envelopeTokens >= 0, "AI input envelope count must be non-negative")

  def usageFacts: AiUsageFacts =
    AiUsageFacts(inputTokens = Some(AiUsageFact(tokens, AiUsageSource.Estimated)))
}

private[textus] object AiInputTokenEstimator {
  val basis: String = "utf8-byte-upper-bound-v1"
  val chatMessageEnvelopeTokens: Long = 16L

  def generate(prompt: String): AiInputTokenEstimate =
    _estimate(Option(prompt).getOrElse(""), 0)

  def record(prompt: String): AiInputTokenEstimate =
    _estimate(Option(prompt).getOrElse(""), 0)

  def chat(normalizedInput: String, messageCount: Int): AiInputTokenEstimate =
    _estimate(Option(normalizedInput).getOrElse(""), messageCount.max(0))

  private def _estimate(input: String, messagecount: Int): AiInputTokenEstimate = {
    val bytes = input.getBytes(StandardCharsets.UTF_8).length.toLong
    val envelope = messagecount.toLong * chatMessageEnvelopeTokens
    AiInputTokenEstimate(bytes + envelope, bytes, messagecount, envelope)
  }
}
