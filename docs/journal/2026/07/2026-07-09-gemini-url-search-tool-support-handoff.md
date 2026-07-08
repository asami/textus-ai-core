# AI Web Tool Support Handoff

status=implemented
updated_at=2026-07-09
tag=textus-ai, ai-runner, url-context, web-search, gemini, openai, artscene

## Summary

Textus AI now supports provider-neutral AI web tool requests through the
existing CNCF `AiRunner` generate/chat path. This replaces the earlier
Gemini-only handoff shape with a logical tool contract that can be mapped by
Google Gemini and OpenAI providers.

The public component/CML operation surface remains unchanged. Callers keep
using `generate` / `chat` and may select behavior through
`AiRunnerRequirement` or purpose profile configuration.

## Implemented Contract

The CNCF `AiRunnerRequirement` contract now carries a logical tool set:

- `url_context`
- `web_search`

Profile configuration can supply purpose-level defaults:

```properties
textus.ai.purposes.artscene-exhibition-fetch.provider = google
textus.ai.purposes.artscene-exhibition-fetch.model = gemini-2.5-flash
textus.ai.purposes.artscene-exhibition-fetch.tools = url_context, web_search
```

Request-level tools take precedence over purpose-profile defaults. If neither
request nor profile supplies tools, existing plain provider behavior is
preserved.

## Provider Mapping

Google Gemini maps the logical tools to Gemini Interactions API tools:

- `url_context` -> `url_context`
- `web_search` -> `google_search`

OpenAI maps both logical web tools to Responses API web search:

- `url_context` -> `web_search`
- `web_search` -> `web_search`

Gemma does not support web tools in this slice and fails explicitly when tools
are requested.

## Provider Local Properties

Provider-specific tuning remains local to the provider and is passed as request
properties, not as new public component operations.

OpenAI-specific examples:

- `ai.openai.web_search.search_context_size`
- `ai.openai.web_search.return_token_budget`
- `ai.openai.reasoning.effort`

Google-specific examples:

- `ai.google.tool.url_context`
- `ai.google.tool.google_search`

## Failure Semantics

Unknown tool tokens are preserved by the CNCF parser and rejected by the
provider layer as structured configuration failures. Supported-but-unavailable
tool requests also fail explicitly instead of being silently ignored.

This is important for CAR callers such as ArtScene: a request for web-assisted
retrieval must either execute with the requested capability or fail in a way
that can be recorded in operation results.

## Runtime Metadata

`TextusAiRunner` passes the effective tool set to providers as `ai.tools` and
records effective tool names in calltree attributes.

Provider responses enrich existing response metadata without changing the
response shape. Tool-enabled providers report provider tool names and available
grounding/search summaries, such as Google search-call and URL-citation counts
or OpenAI web-search-call counts.

## ArtScene Relationship

ArtScene can keep using a provider-neutral requirement such as:

```scala
AiRunnerRequirement(purpose = Some("artscene-exhibition-fetch"))
```

The CAR stays independent from Google and OpenAI wire APIs. Operator-managed
purpose profile configuration decides whether the request uses plain generation,
Gemini URL/search tools, or OpenAI web search.

