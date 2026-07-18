# Handoff: Codex CLI Purpose Policy and Web Tools

## Context

ArtScene selects a logical fetch method per facility. Its default order is:

```text
official_driver -> ai_web_tools -> museum_or_jp
```

The caller selects the retrieval strategy only. It must not know a concrete AI
provider, model, credential, or provider-specific command-line option.

`ai_local_navigation` remains available for facilities where ArtScene first
retrieves bounded same-site source text and asks AI to extract exhibitions.
`ai_web_tools` asks the AI runtime to research the supplied official/fetch URL
with Web access. `ai_managed_research` is a distinct, broader research method.

This handoff covers the missing Textus AI support required to use the local
Codex CLI profile for those purpose-based methods.

## Current State

The local Codex CLI provider can execute structured generation, subject to its
process-execution runtime wiring. It does not currently support logical tool
requests such as `url_context` or `web_search`: those requests are returned as
a structured unsupported result.

This is a Textus AI integration gap, not evidence that Codex CLI itself cannot
search the Web. Current Codex CLI supports Web search, using cached search by
default in local chats and live search when started with its approved search
setting (for example, `--search` or `web_search = "live"`). Textus AI must map
its logical tool request to that admitted Codex capability; a natural-language
prompt alone cannot make the existing provider binding do so.

Purpose/profile configuration can resolve provider, mode, engine, and model in
configuration. The current Codex provider does not yet admit a purpose-resolved
model selection as a bounded Codex command argument. Provider-neutral reasoning
level policy is also not implemented for Codex; existing reasoning mapping is
limited to the OpenAI provider request path.

Consequently, a prompt such as "read https://example.test and extract
exhibitions" is not sufficient to enable Web access. A prompt can instruct a
model to use a tool, but it cannot grant the process a Web-search or URL-fetch
capability.

## Required Contract

Textus AI must own the mapping from a logical purpose to a safe Codex CLI
execution profile.

For `artscene-exhibition-web-research`, it must:

1. Resolve the purpose to a Codex profile, including a model and reasoning
   level when configured.
2. Validate that the selected Codex executable/runtime supports the logical
   `url_context` and `web_search` capabilities.
3. Invoke Codex through an admitted, bounded execution contract that enables
   only those capabilities required by the purpose.
4. Return a schema-valid `Record` or a structured unsupported, configuration,
   provider, tool, timeout, or parse/schema error.
5. Record the resolved purpose, profile, provider, effective model, reasoning
   level, requested/enabled tools, and bounded outcome in CallTree metadata.

The application must not construct Codex command lines, add provider-specific
prompt switches, pass credentials, or infer tool support from model names.

## Model and Reasoning Selection

The desired configuration shape is purpose/profile policy, not caller-supplied
command arguments. For example:

```yaml
textus:
  ai:
    purposes:
      artscene-exhibition-web-research:
        model-profile: codex-artscene-research
    model-profiles:
      codex-artscene-research:
        provider: codex
        mode: local
        engine: codex-cli
        model: <approved-model>
        reasoning-level: <approved-level>
        tools: [url_context, web_search]
```

The implementation must define a finite, provider-specific allowlist for the
Codex model and reasoning arguments. It must not turn arbitrary configuration
text or request fields into arbitrary process arguments. If the installed Codex
CLI cannot support a requested policy field, resolution must fail structurally
rather than silently ignoring it.

## Tool Semantics

- `url_context` means the runtime permits Codex to retrieve and reason over the
  URLs supplied by the application request.
- `web_search` means the runtime permits Codex to search public Web sources.
- `ai_web_tools` normally supplies official/fetch URLs as strong source hints;
  these are not a browser-side permission mechanism.
- `ai_managed_research` may use the same logical tools but has a separate
  purpose, limits, and observability profile.
- `ai_local_navigation` does not require Codex Web access: ArtScene supplies
  local source context and Textus AI performs structured extraction only.

If Web tools cannot be enabled in the installed Codex CLI execution mode, the
runtime must return an explicit unsupported error. ArtScene may then continue
to the next configured fetch method, such as `museum_or_jp`.

## Safety and Operations

- Capability enablement belongs in the admitted Codex execution binding, never
  in free-form prompt text.
- Purpose policy needs bounded timeout, input/output token, concurrency, and
  cost controls. These are target capabilities; do not claim purpose-level
  token or cost budgets are enforced until admission and usage accounting exist.
- No credentials, session identifiers, raw environment values, or unrestricted
  model/tool transcripts may be returned to applications or CallTree.
- CallTree should retain bounded tool/grounding summaries and error categories
  sufficient to distinguish unsupported capability from provider failure.

## Required Executable Specifications

- A Codex purpose profile with no requested tools executes structured Record
  generation through the normal admitted process path.
- A Web-research purpose resolves the configured Codex model/reasoning policy
  only from its approved profile.
- An unsupported `url_context` or `web_search` capability returns a structured
  unsupported result and does not execute an implicit local-navigation path.
- When Codex Web capability is available, the admitted invocation receives only
  the fixed safe Web-tool configuration; application prompt content cannot add
  arbitrary CLI options or tools.
- CallTree records purpose, profile, effective model/reasoning, requested and
  enabled tools, and a bounded result/error summary without secrets.
- A deterministic fake Codex execution binding covers command construction and
  tool admission. A real local Codex Web run is an optional operational smoke.

## Acceptance Criteria

- ArtScene can select `ai_web_tools` without knowing it is backed by Codex.
- A configured Codex Web-research purpose returns a schema-valid Record or a
  clear structured error; it never silently pretends that a prompt alone gave
  Web access.
- Model and reasoning level are selected by the resolved purpose/profile and
  are observable in bounded CallTree metadata.
- `ai_local_navigation` remains usable with Codex even when Codex Web tools are
  unavailable.
- The existing ArtScene fallback order remains application policy; Textus AI
  does not silently choose another ArtScene fetch method.

## Related Records

- `docs/journal/2026/07/2026-07-18-artscene-ai-fetch-purpose-contract-handoff.md`
- `docs/journal/2026/07/2026-07-18-codex-cli-live-execution-handoff.md`
