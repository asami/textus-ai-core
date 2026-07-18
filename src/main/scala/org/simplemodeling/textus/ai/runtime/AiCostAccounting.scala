package org.simplemodeling.textus.ai.runtime

import org.goldenport.Consequence

/*
 * Operator-owned AI rate schedule and deterministic cost accounting.
 *
 * @since   Jul. 18, 2026
 * @version Jul. 18, 2026
 * @author  ASAMI, Tomoharu
 */
private[textus] final case class AiRateSchedule(
  id: String,
  inputMicrounitsPerMillion: Long,
  cachedInputMicrounitsPerMillion: Long,
  outputMicrounitsPerMillion: Long,
  reasoningMicrounitsPerMillion: Long
) {
  require(id.nonEmpty, "AI rate schedule identity is required")
  require(Vector(
    inputMicrounitsPerMillion,
    cachedInputMicrounitsPerMillion,
    outputMicrounitsPerMillion,
    reasoningMicrounitsPerMillion
  ).forall(_ >= 0), "AI rate schedule values must be non-negative")

  def inputUpperBoundCostC(tokens: Long): Consequence[Long] =
    AiCostAccounting.microunitsC(tokens, inputMicrounitsPerMillion.max(cachedInputMicrounitsPerMillion))

  def outputCostC(tokens: Long): Consequence[Long] =
    AiCostAccounting.microunitsC(tokens, outputMicrounitsPerMillion)

  def reasoningCostC(tokens: Long): Consequence[Long] =
    AiCostAccounting.microunitsC(tokens, reasoningMicrounitsPerMillion)
}

private[textus] final case class AiCostAdmission(
  upperBoundMicrounits: Long,
  inputMicrounits: Long,
  outputMicrounits: Long,
  reasoningMicrounits: Long
)

private[textus] object AiCostAccounting {
  val microunitsPerMillion: Long = 1000000L
  val admissionBasis: String = "configured-upper-bound-v1"
  val measuredBasis: String = "provider-reported-v1"

  def admissionC(
    schedule: AiRateSchedule,
    input: AiInputTokenEstimate,
    maxOutputTokens: Int,
    maxReasoningTokens: Int
  ): Consequence[AiCostAdmission] =
    for {
      inputcost <- schedule.inputUpperBoundCostC(input.tokens)
      outputcost <- schedule.outputCostC(maxOutputTokens.toLong)
      reasoningcost <- schedule.reasoningCostC(maxReasoningTokens.toLong)
      total <- _sum_c(Vector(inputcost, outputcost, reasoningcost))
    } yield AiCostAdmission(total, inputcost, outputcost, reasoningcost)

  def measuredC(
    schedule: AiRateSchedule,
    inputTokens: Long,
    cachedInputTokens: Long,
    outputTokens: Long,
    reasoningTokens: Long
  ): Consequence[Long] =
    if (cachedInputTokens > inputTokens)
      Consequence.configurationInvalid("AI provider cached input tokens exceed input tokens")
    else
      for {
        inputcost <- microunitsC(inputTokens - cachedInputTokens, schedule.inputMicrounitsPerMillion)
        cachedcost <- microunitsC(cachedInputTokens, schedule.cachedInputMicrounitsPerMillion)
        outputcost <- microunitsC(outputTokens, schedule.outputMicrounitsPerMillion)
        reasoningcost <- microunitsC(reasoningTokens, schedule.reasoningMicrounitsPerMillion)
        total <- _sum_c(Vector(inputcost, cachedcost, outputcost, reasoningcost))
      } yield total

  def microunitsC(tokens: Long, ratePerMillion: Long): Consequence[Long] =
    if (tokens < 0 || ratePerMillion < 0)
      Consequence.configurationInvalid("AI cost inputs must be non-negative")
    else {
      val numerator = BigInt(tokens) * BigInt(ratePerMillion)
      val result = (numerator + microunitsPerMillion - 1) / microunitsPerMillion
      if (result > BigInt(Long.MaxValue))
        Consequence.configurationInvalid("AI cost calculation exceeds supported range")
      else
        Consequence.success(result.toLong)
    }

  private def _sum_c(values: Vector[Long]): Consequence[Long] = {
    val total = values.foldLeft(BigInt(0))((z, x) => z + x)
    if (total > BigInt(Long.MaxValue))
      Consequence.configurationInvalid("AI cost calculation exceeds supported range")
    else
      Consequence.success(total.toLong)
  }
}
