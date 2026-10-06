# 2026-10-06 AI Evaluation Replay from AI Runtime Audit

The AI Runtime audit design is now also an input source for Textus AI Evaluation / Replay Benchmark.

An AI execution can later become an EvaluationCase when enough authoritative information is retained to reproduce or meaningfully replay the problem: input, output, engine/model and relevant parameters, execution context references, and correlation to later outcomes.

The runtime does not need to execute multiple engines online. Its responsibility is to preserve an adequate audit/observation contract so that textus-experiment can replay a selected historical case later against arbitrary Thinking Engines.

Evaluation data should refer to authoritative audit records rather than copying complete input/output payloads into every corpus or experiment record.

This supports:
- retrospective comparison of local LLMs and provider models,
- testing newly introduced models against old real tasks,
- deterministic/outcome/judge evaluation,
- eventual Task Class x Engine capability measurements for Engine Router decisions.

This extends the existing AI execution observation and audit-correlation work. No new implementation phase is introduced yet; required audit contract additions should be defined after the cross-component EvaluationCase contract is stabilized.
