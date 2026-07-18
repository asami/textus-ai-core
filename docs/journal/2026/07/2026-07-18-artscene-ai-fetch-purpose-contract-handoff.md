# Handoff: ArtScene AI Fetch Purpose Contract

## Purpose

ArtScene is formalizing facility-configured exhibition fetch methods. AI-backed methods select a logical business purpose; they must not select Gemini, a model name, provider credentials, or provider-specific Web tools.

Textus AI must provide the runtime contract that resolves these purposes to a safe, observable AI execution profile.

## Current Implementation Status

As of July 18, 2026, this contract is only partially implemented.

- Request-level `GenerateRequest.maxTokens` is implemented as a maximum output-token setting and is mapped to provider request fields, including Google `generationConfig.maxOutputTokens` and OpenAI `max_completion_tokens`.
- Purpose/model profiles resolve provider, mode, engine, model, and enabled tools.
- Purpose/model profiles do **not** currently resolve or enforce a maximum-token budget.
- Profile `cost` metadata exists, but it is descriptive model-selection metadata only. There is no cost budget, accumulated usage accounting, price calculation, admission control, or pre-execution cost rejection.
- Provider quota/rate-limit failures are classified structurally, but provider quota exhaustion is not an application-managed cost limit.

Do not describe purpose-level token or cost budgets as implemented until the runtime can enforce them.

## Purpose Selection: Current Capability and Gap

Textus AI currently resolves the following purpose configuration keys:

```text
textus.ai.purposes.<purpose>.model-profile
textus.ai.purposes.<purpose>.provider
textus.ai.purposes.<purpose>.mode
textus.ai.purposes.<purpose>.engine
textus.ai.purposes.<purpose>.model
textus.ai.purposes.<purpose>.tools
```

A model profile may supply provider, mode, engine, and model. Explicit request
fields currently take precedence over purpose/profile values.

This is sufficient for basic engine and logical-tool selection, but it is not
yet a complete purpose-policy system.

### Required Follow-up Semantics

- A purpose required by an application policy must fail structurally when no
  profile is configured. It must not silently fall through to a default
  provider because of an unknown or misspelled purpose.
- Purpose policy needs provider-neutral configuration for reasoning level,
  maximum output tokens, input/output token budgets, timeout, retry,
  concurrency, and cost budget.
- A purpose needs an optional structured-output policy, including schema ID and
  schema-validation requirements. The caller may continue to supply the
  concrete Record schema.
- A purpose needs an optional prompt policy for system instruction, output
  constraints, and source restrictions without exposing provider-specific
  prompt hacks to the caller.
- Before execution, Textus AI must validate that the selected provider/model
  supports the purpose's required logical tools. Unsupported tools must return
  a structured configuration or unsupported result.
- Provider/model fallback inside Textus AI must be explicit configuration. It
  must never be an implicit response to a purpose failure.

ArtScene owns fallback between fetch methods. For example, it may continue from
`ai_web_tools` to `museum_or_jp` because of facility `fetch_methods` policy;
Textus AI must not silently substitute a different ArtScene fetch purpose.

## ArtScene Fetch Methods and Logical Purposes

| ArtScene fetch method | Requested Textus AI purpose | ArtScene-provided context |
| --- | --- | --- |
| `ai_local_navigation` | `artscene-exhibition-extraction-from-source` | Bounded official-site pages retrieved by ArtScene/Textus Scraper, including URL and compact source text. |
| `ai_web_tools` | `artscene-exhibition-web-research` | Facility identity, official/fetch URLs, timezone, and extraction requirements. |
| `ai_managed_research` | `artscene-exhibition-managed-research` | Facility identity, official/fetch URLs, timezone, previous source failures, and extraction requirements. |

`ai_web_tools` is a Web-tool-enabled structured extraction request. `ai_managed_research` is a broader runner-managed research workflow that may make multiple research and source-comparison steps. They have different expected cost, latency, and observability profiles.

## Required Textus AI Contract

For each purpose, Textus AI resolves runtime policy for:

- AI product/provider and model;
- reasoning level;
- enabled external tools;
- timeout, token, and cost budget;
- structured `Record` generation schema;
- calltree metadata that safely records purpose, selected profile, enabled tools, and result/error summaries.

Token and cost budgets in this list are target capabilities, not current runtime behavior. Their implementation requires explicit budget configuration, provider-neutral usage facts, admission checks, and a policy for unknown provider usage/cost.

ArtScene must remain provider-neutral. It must not contain Gemini/Google API details, provider credentials, model names, or provider-specific prompt/tool workarounds.

## Structured Result

The required ArtScene result is a structured Record containing an `exhibitions` collection. Each accepted exhibition requires:

- `title`;
- `period_start` as ISO `YYYY-MM-DD`;
- `period_end` as ISO `YYYY-MM-DD`;
- `confidence` in `0..100`.

Optional fields are `source_url`, `description`, and `confidence_reason`.

The normal AI boundary must return either a schema-valid Record or a structured error. Textus AI may normalize provider text internally, but ArtScene must not parse provider prose, Markdown fences, or provider-specific response shapes.

## Failure Semantics

- A purpose not configured or unavailable must return a structured unsupported/source error.
- Provider/tool/network failures must retain a bounded, safe diagnostic summary.
- Invalid or schema-incompatible model output must return a structured parse/schema error.
- ArtScene will turn the result into its `UpdateResult` error model and may continue to later configured fetch methods.

## Assembly and Facility Boundaries

- Facility `fetch_methods` declares retrieval strategy and order only.
- Facilities may later select a logical AI profile override, but never a concrete provider/model/credential.
- Assembly/runtime configuration binds purposes and logical profiles to actual AI engine configuration.
- Textus AI owns the policy for whether a chosen provider supports the requested purpose and tools.

## Acceptance Criteria

- `AiRunner.generateRecord` accepts a purpose and produces a typed/structured Record result or structured error.
- Purpose profiles can independently configure local-source extraction, Web-tool extraction, and managed research.
- A provider unsupported for a purpose/tool is reported structurally rather than silently downgraded.
- Calltree exposes purpose, resolved profile, provider/model, enabled tools, and bounded result/error metadata without secrets.
- Deterministic tests cover all three purposes; live provider checks remain optional operational validation.

## Related ArtScene Record

See ArtScene journal entry `2026-07-18-ai-fetch-method-and-purpose-contract.md` for the caller-side selection and ownership model.
