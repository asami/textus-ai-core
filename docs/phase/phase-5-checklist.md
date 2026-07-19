# Phase 5 Checklist - Local Gemma/Ollama Runtime Activation

status=active
phase=[Phase 5 - Local Gemma/Ollama Runtime Activation](phase-5.md)

## GO-01 Runtime Profile

- [x] Add the built-in `gemma` profile with local Ollama defaults for every
  standard execution class.
- [x] Resolve `simple-work` and `standard-work` through provider `gemma`, mode
  `local`, engine `ollama`, and the profile-owned model.
- [x] Supply `gemma-simple-gemini` and `gemma-simple-codex-cli` profiles, with
  Gemma limited to `simple-work`.

## GO-02 Container Contract

- [x] Make Docker provisioning the default for the `gemma` profile.
- [x] Prefer an explicit `textus.ai.gemma.endpoint` over Docker provisioning.
- [x] Compile fixed CNCF managed-process capabilities for container inspect,
  start/run, and profile-owned model pulls.
- [x] Verify an execution-class model override changes only the selected class.
- [x] Preserve caller rejection for provider/model/endpoint selection.

## GO-03 Executable Evidence

- [x] Verify profile resolution deterministically without an Ollama daemon.
- [x] Verify a standard purpose selects the profile-owned model and reaches the
  registered Ollama HTTP binding.
- [x] Verify Docker capability admission and external-endpoint precedence.
- [ ] Run live Gemma provisioning as a heavy test with an available Docker
  daemon and sufficient model-download capacity.
