# AI Purpose Catalog

status=accepted
scope=textus-ai standard provider-neutral purpose vocabulary
updated_at=2026-07-18

## Decision

Textus AI defines a small standard catalog of generic purposes. A caller
selects one of these purposes, or an application-specific purpose that
explicitly inherits one. Textus AI resolves the selected purpose to a logical
level, then to an operator-managed model profile, and finally to effective
provider execution settings.

The catalog standardizes names and policy mapping; it does not ship a concrete
provider binding. Each deployment configures the listed generic purposes, its
logical levels, and approved model profiles. A requested standard purpose that
is not configured fails structurally before provider execution.

The standard purpose vocabulary names user-visible AI intent. It must not name
a provider, model, command-line tool, application, or domain workflow.

```text
purpose
  -> logical level
  -> operator-managed model profile
  -> provider, mode, engine, model, reasoning, tools, and limits
```

## Standard Catalog

| Purpose | Default logical level | Intent | Tool policy |
| --- | --- | --- | --- |
| `quick-response` | `simple-work` | Produce a bounded direct response from caller-supplied input. | No external tools. |
| `structured-extraction` | `standard-work` | Extract or normalize caller-supplied material into a required structured result. | No external tools. |
| `analysis` | `standard-deliberation` | Compare, explain, classify, or reason over caller-supplied material. | No external tools. |
| `deep-analysis` | `deep-deliberation` | Perform higher-cost, deeper reasoning over caller-supplied material. | No external tools. |
| `web-research` | `deep-deliberation` | Obtain and synthesize information through explicitly admitted external research tools. | `url_context` and `web_search` may be enabled by the operator profile. |

The first four purposes cover every standard logical level. `web-research`
shares `deep-deliberation` because external retrieval also requires explicit
source, timeout, concurrency, and cost controls. It is a separate purpose
because its admitted capabilities and observability requirements differ from
local analysis.

The default level is an approved policy baseline, not a caller choice. An
operator can bind a level to a different provider/model profile without
changing application code. A caller cannot replace the resolved level, model,
reasoning setting, or tool set through provider-specific request fields.

## Application Purposes

Applications use a descriptive domain purpose when they need domain-specific
prompt, schema, source, or execution bounds. That purpose inherits one
standard purpose with `base-purpose` and may narrow, but never broaden, its
effective policy.

```yaml
textus:
  ai:
    purposes:
      artscene-exhibition-extraction-from-source:
        base-purpose: structured-extraction
        max-output-tokens: 240
      artscene-exhibition-web-research:
        base-purpose: web-research
        tools: web_search
        max-output-tokens: 240
        max-concurrent: 1
```

Examples of non-standard application purposes include
`artscene-exhibition-web-research` and `car-review-domain-terminology`. Their
names express application intent, not provider selection.

An application requiring a specialized policy that cannot safely inherit a
standard purpose must define that policy explicitly in its assembly. It must
still resolve through a logical level and approved model profile; it must not
place provider or command arguments in application calls.

## Exclusions

The following are not standard purposes:

- provider or product names such as `codex`, `openai`, `gemini`, or `gemma`;
- model names, reasoning labels, and CLI capability names;
- project-specific activities such as `implementation-work` or
  `specification-analysis`; and
- transport or output-shape labels such as `chat`, `generate`, or
  `generateRecord`.

`implementation-work` and `specification-analysis` were provisional generic
configuration examples. They are application purposes when needed, normally
inheriting `analysis` or `deep-analysis`. A Codex CLI profile may be the
operator-selected resolution for either purpose, but it does not define their
meaning.

`local-source-extraction` is likewise an application purpose that inherits
`structured-extraction` and supplies bounded, admitted source material. It is
not a separate generic purpose because it requires no capability beyond the
standard structured-extraction boundary.

## Managed Research

Managed research is a Phase 3 workflow mode, not an additional standard
generic purpose. An application purpose such as
`artscene-exhibition-managed-research` inherits `web-research`; its configured
workflow may later perform finite research, source comparison, and structured
synthesis steps.

This distinction keeps the catalog stable: `web-research` defines the
provider-neutral intent and capability boundary, while Phase 3 determines the
bounded execution plan, input budget, and cost-accounting policy.

## Configuration Baseline

```yaml
textus:
  ai:
    levels:
      deep-deliberation: { model-profile: approved-deep }
      standard-deliberation: { model-profile: approved-standard }
      standard-work: { model-profile: approved-standard }
      simple-work: { model-profile: approved-simple }
    generic-purposes:
      quick-response:
        level: simple-work
      structured-extraction:
        level: standard-work
      analysis:
        level: standard-deliberation
      deep-analysis:
        level: deep-deliberation
      web-research:
        level: deep-deliberation
        tools: url_context, web_search
```

The model-profile bindings are deployment-specific. A production operator may
select a local model, OpenAI, Google Gemini, or Codex CLI only when the selected
profile and its admitted capabilities support the purpose policy.

## Invariants

- Every standard purpose resolves through one logical level and one approved
  model profile before execution.
- `web-research` is the only standard purpose that can admit external research
  tools.
- A standard purpose does not imply a provider, model, or provider-specific
  tool wire format.
- Application purposes may narrow inherited tools and execution limits but may
  not broaden them or replace inherited provider/model/reasoning policy.
- Missing, unsupported, or incompatible configured capabilities return a
  structured failure; they do not select a fallback purpose or provider.
