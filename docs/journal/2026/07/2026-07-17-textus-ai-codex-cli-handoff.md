# Textus AI Codex CLI Activation Handoff

Date: 2026-07-17

## Objective

Activate the existing Codex CLI provider inside Textus AI so that an application
such as Sanpomap can select a `codex` AI profile and execute it through the
normal Textus AI interface.

This is a Textus AI implementation task. It must not introduce Codex-specific
assembly, launcher syntax, configuration, or business logic into CNCF,
Sanpomap, or GeoResolver.

## Current State

Textus AI revision `68b4ec5 Add Codex CLI AI runner provider` already contains:

- provider selection for `codex` and `codex-cli`;
- `CodexRuntimeProvider`;
- Generate and Chat extension points;
- bounded prompt stdin;
- structured-record generation through `--output-schema schema.json -`;
- provider-level rejection of unsupported tools and model overrides;
- executable specifications using an admitted deterministic process fixture.

The relevant implementation is:

```text
textus-ai/src/main/scala/org/simplemodeling/textus/ai/ComponentFactory.scala
textus-ai/src/main/scala/org/simplemodeling/textus/ai/runtime/AiRuntimeBinding.scala
textus-ai/src/main/scala/org/simplemodeling/textus/ai/provider/codex/CodexRuntimeProvider.scala
```

`ComponentFactory` currently enables the Generate/Chat provider SPI when:

```yaml
textus:
  ai:
    codex:
      enabled: true
```

However, `CodexRuntimeProvider` subsequently requests the logical process
capability `codex-cli` through `ProcessExecutionAdmission`. In ordinary
Textus AI execution, Textus AI has not installed the corresponding admitted
Codex execution binding into its execution scope. The provider is therefore
selectable but not live-executable.

## Ownership Boundary

### Textus AI owns

- `codex` / `codex-cli` provider registration and selection;
- the trusted Codex CLI invocation profile;
- capability name `codex-cli`;
- fixed command prefix and allowable provider argument suffixes;
- Codex-specific sandbox/session policy;
- provider-specific executable configuration and validation;
- installation of the admitted execution binding into the Textus AI runtime
  scope;
- conversion of process outcomes to Textus AI structured results.

### CNCF provides without Codex knowledge

- `ProcessExecutionRequest`;
- `ProcessProgramDefinition`, policy, grant, and admission primitives;
- `ProcessExecutionDriver` and UnitOfWork execution;
- scope propagation and structured failure contracts.

CNCF must remain unaware of `codex-cli`, `codex exec`, Codex credentials, and
Textus AI profile names.

### Sanpomap and GeoResolver own

- selection of an AI purpose/profile through Textus AI;
- input/output domain contracts;
- no Codex CLI configuration, executable lookup, or process invocation.

## Required Textus AI Work

1. Add a Textus AI internal Codex execution binding/installer.

   When `textus.ai.codex.enabled` is true, it must create and register the
   `codex-cli` process capability in the Textus AI execution scope by using the
   existing CNCF process-execution primitives. This must happen as part of
   Textus AI runtime construction, not in an application component.

2. Keep the invocation trusted and non-generic.

   Textus AI owns the executable location and fixed `codex exec` prefix. It
   must not accept a shell command or arbitrary leading argument string from a
   Scenario DSL, application configuration, or AI request.

   Confirm the exact non-interactive, machine-readable, read-only sandbox, and
   ephemeral-session flags against the installed Codex CLI help during
   implementation. The provider must use fixed policy flags rather than
   guessing or exposing them as arbitrary configuration.

3. Admit only the existing provider protocol.

   The only dynamic argument vectors admitted for the `codex-cli` capability
   are:

   ```text
   -
   --output-schema schema.json -
   ```

   `schema.json` is an internal work-area input created by
   `CodexRuntimeProvider`. Reject arbitrary options, arbitrary paths, shell
   syntax, executable overrides, and additional input files before a process is
   launched.

4. Bound execution resources in the Textus AI profile.

   Define finite limits for timeout, stdin, stdout, stderr, work area, input
   artifacts, and output artifacts. The optional request timeout may narrow the
   configured maximum but may not enlarge it.

   Execution must use no shell interpolation. Environment handling must be an
   explicit allowlist. Codex authentication remains the responsibility of the
   locally installed Codex CLI; do not add account state or API keys to Textus
   AI/CNCF configuration.

5. Preserve disabled behavior.

   With `textus.ai.codex.enabled` absent or false, Textus AI must not create the
   Codex execution binding. A `codex` profile must then fail through the normal
   structured unavailable/admission path and launch no process.

## Acceptance Criteria

### Textus AI Executable Specifications

- Given Codex is disabled, when `codex-cli` is requested, then Textus AI returns
  a structured unavailable/admission result and launches nothing.
- Given Codex is enabled, when Generate or Chat uses provider `codex`, then the
  Textus AI runtime scope contains the admitted `codex-cli` capability.
- Given a plain request, when it is admitted, then its provider suffix is
  exactly `-`.
- Given record generation, when it is admitted, then its provider suffix is
  exactly `--output-schema schema.json -` and `schema.json` is the only allowed
  provider input artifact.
- Given any other option, path, control character, executable override, or
  unsupported provider request, when requested, then Textus AI rejects it before
  process launch.
- Given timeout, non-zero exit, launch failure, or an output limit breach, then
  Textus AI returns the existing structured provider failure without leaking
  credential values, environment values, raw unrestricted stderr, or host paths.

### Live Integration Verification

With a locally authenticated Codex CLI and an explicitly enabled Textus AI
Codex configuration, the following Sanpomap call must generate a valid Scenario
DSL:

```sh
cncf /Users/asami/src/dev2026/textus-sanpomap command \
  --component-dev-dir /Users/asami/src/dev2026/textus-ai \
  --component-dev-dir /Users/asami/src/dev2026/textus-georesolver \
  sanpomap.presentation.generate-scenario-dsl \
  --start '府中本町駅' \
  --stop '府中市郷土の森博物館' \
  --out /private/tmp/sanpomap-codex-scenario.yaml
```

The response must report AI execution facts for provider `codex`; the Scenario
DSL must be valid; no credential, environment, executable-path, or raw
unrestricted process output may appear in the response.

## Non-Goals

- No CNCF change for a Codex-specific runtime/launcher feature.
- No direct process execution in Sanpomap or GeoResolver.
- No application-level Codex executable path, shell command, account setting,
  or API-key configuration.
- No automatic enablement. The Textus AI Codex provider remains explicitly
  opt-in.

## Follow-up Ownership

1. Textus AI: implement and test the internal live execution binding.
2. Sanpomap: configure the `sanpomap-scenario-generation` purpose to select a
   `codex` profile and run the integration verification after Textus AI is
   updated.
