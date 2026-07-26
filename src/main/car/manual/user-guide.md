# Textus AI Runtime User Guide

## First Use

Configure one runtime profile in CNCF configuration. For a local developer
machine with ChatGPT/Codex CLI installed, use:

```yaml
textus:
  ai:
    profile: codex-cli
    codex:
      enabled: true
      executable: /Applications/ChatGPT.app/Contents/Resources/codex
      schema-maximum-bytes: 65536
```

For local low-cost work with Ollama/Gemma and Codex CLI fallback, use:

```yaml
textus:
  ai:
    profile: gemma-work-codex-cli
    codex:
      enabled: true
      executable: /Applications/ChatGPT.app/Contents/Resources/codex
      schema-maximum-bytes: 65536
```

The `gemma-work-codex-cli` profile expects native Ollama to be running and the
needed Gemma models to be installed locally.

## Application Use

Applications should call Textus AI through the CNCF `AiRunner` SPI and specify a
registered application purpose:

```scala
AiRunnerRequirement(
  purpose = Some("sanpomap-location-investigation"),
  purposeRequired = true
)
```

Application code should not specify provider, model, execution class, CLI flags,
or tool wire names. Textus AI resolves those from the registered application
purpose, standard purpose, runtime profile, and operator configuration.

## Local Gemma Setup

Install and start native Ollama, then install the required local models:

```bash
ollama pull gemma:2b
ollama pull gemma3:12b
```

Textus AI connects to `http://127.0.0.1:11434` by default. If Ollama listens
elsewhere, configure:

```yaml
textus:
  ai:
    gemma:
      endpoint: http://127.0.0.1:11434
```

If Ollama is not reachable, Gemma requests fail. Textus AI does not start Docker
as a fallback for native local operation.

## Remote API Setup

Remote API providers require operator-owned credentials in local or deployment
configuration:

```yaml
textus:
  ai:
    profile: openai
    openai:
      api-key: ${OPENAI_API_KEY}
```

```yaml
textus:
  ai:
    profile: gemini
    google:
      api-key: ${GOOGLE_API_KEY}
```

Keep credentials outside repository files.

## Common Workflows

Use `codex-cli` when the user has ChatGPT/Codex CLI installed and wants a
general local managed CLI route.

Use `gemma-work-codex-cli` when simple and standard work should prefer local
Gemma while thinking classes should use Codex CLI.

Use `gemini`, `openai`, or `anthropic` when deployment needs direct API
execution and the operator has configured credentials.

Use application purposes for domain behavior. Use standard purposes only for
shared Textus AI policy design and tests.

## Troubleshooting

If initialization fails with a missing profile, set `textus.ai.profile`.

If Codex CLI fails to initialize, confirm `textus.ai.codex.enabled: true` and
that `textus.ai.codex.executable` is an absolute path to an executable file.

If Gemma requests fail, confirm native Ollama is running and that the configured
model appears in `ollama list`.

If a request fails before provider execution, check whether the application
purpose was registered at bootstrap and whether deployment configuration is only
tuning an existing registration.

If a Web-capable purpose fails on a local model, check whether the selected
runtime profile supports the required logical tool.

## Validation

Run focused executable specifications before publishing or integrating a
provider change:

```bash
sbt test
```

Run live Gemma checks only on machines where Ollama and the target local models
are already available:

```bash
TEXTUS_AI_LIVE_NATIVE_GEMMA_TEST=true \
  sbt --batch 'testOnly org.simplemodeling.textus.ai.GemmaOllamaLiveSpec'
```
