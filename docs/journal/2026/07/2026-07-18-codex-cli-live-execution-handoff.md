# Codex CLI Live Execution Handoff

Date: 2026-07-18

## Context

Textus AI now has a component-local `CodexExecutionBinding`. It correctly
installs a `codex-cli` Process Execution admission when all of the following
are configured:

```yaml
textus:
  ai:
    codex:
      enabled: true
      executable: /Applications/ChatGPT.app/Contents/Resources/codex
```

The implementation fixes the Codex command prefix, sandbox mode, ephemeral
session mode, permitted argument vectors, schema input path, empty environment
allowlist, and finite execution limits.

The current executable specifications validate admission. They do not validate
a live process lifecycle.

## Remaining Defect

The component scope currently receives `ProcessExecutionAdmission` only.
It does not receive a `ProcessExecutionDriver`.

```text
TextusAiRuntimeComponent.withScopeContext
  -> processExecutionAdmissionOption = Some(admission)
  -> processExecutionDriverOption is absent
```

`CodexRuntimeProvider` submits an admitted request through
`UnitOfWorkOp.ProcessExec`. The UnitOfWork interpreter resolves a driver from
the same execution scope. Without one, the normal result is the structured
failure `Process Execution driver is not configured`.

The Codex provider must be live-executable through Textus AI. This is not a
CNCF launcher feature and must not be delegated to Sanpomap or GeoResolver.

## Required Textus AI Change

Extend the Textus AI runtime assembly so that an enabled Codex configuration
installs both of these into the Textus AI component scope:

1. the existing `ProcessExecutionAdmission` from `CodexExecutionBinding`;
2. a trusted `LocalProcessExecutionDriver`.

The driver is a generic CNCF primitive, but its installation for the Codex
provider belongs to Textus AI. CNCF remains unaware of the `codex-cli`
capability and Codex command line.

The disabled case must install neither admission nor driver.

## WorkArea Requirement

The local driver runs each request in its managed temporary WorkArea. That
directory is not a Git repository. The installed Codex CLI requires
`--skip-git-repo-check` to run in such a directory.

Add this as a fixed argument in `CodexExecutionBinding`:

```text
codex exec --sandbox read-only --ephemeral --skip-git-repo-check
```

It is not a component request argument and is not user-configurable. The only
admitted provider suffixes remain:

```text
-
--output-schema schema.json -
```

## Environment and Authentication Check

`LocalProcessExecutionDriver` clears inherited process environment values.
This is correct for isolation, but the live binding must verify how the local
Codex CLI locates its authenticated account with an empty environment.

Required rule:

- begin with an empty environment;
- add only a documented, non-secret runtime value if the installed Codex CLI
  requires one to locate its local state;
- never copy an API key, access token, account identity, shell profile, or
  arbitrary host environment into the process definition.

The exact behavior must be validated with the installed Codex CLI rather than
assumed from `PATH`, `HOME`, or `CODEX_HOME` behavior.

## Required Executable Specifications

- Given an enabled Codex configuration, when Textus AI creates its component
  scope, then that scope resolves both a Codex admission and a local process
  driver.
- Given a disabled Codex configuration, when Textus AI creates its component
  scope, then neither admission nor driver is present.
- Given a plain provider request, when it executes through the local driver,
  then the effective command is exactly the fixed Codex prefix plus `-` and its
  working directory is the managed WorkArea.
- Given a record provider request, when it executes through the local driver,
  then `schema.json` is materialized in the WorkArea and the command suffix is
  exactly `--output-schema schema.json -`.
- Given a request outside a Git repository, when the fixed binding runs it,
  then `--skip-git-repo-check` is present.
- Given a launch, timeout, non-zero exit, or output-limit failure, when the
  provider returns the result, then diagnostics remain bounded and do not
  expose credentials, environment values, host paths, or raw stderr.

Use a fake local launcher or deterministic driver for executable
specifications. A real Codex invocation is an integration verification, not a
unit/spec fixture.

## Live Integration Verification

After the driver and fixed argument are added, use an explicitly enabled
Textus AI profile and run a small Sanpomap scenario-generation request.

Success requires all of the following:

- the process starts through Textus AI, rather than a direct Sanpomap call;
- `ai.execution.provider=codex` is reported;
- a valid Scenario DSL is written;
- no credential, environment value, executable path, or raw unrestricted
  process output reaches the operation response.

## Non-Goals

- No Codex-specific change in CNCF runtime or launcher.
- No direct process execution in Sanpomap or GeoResolver.
- No arbitrary executable, shell command, argument prefix, model override, or
  environment map supplied by a component request.
