package org.simplemodeling.textus.ai.runtime

import org.goldenport.cncf.component.*
import org.simplemodeling.textus.ai.provider.anthropic.{AnthropicChatExtensionPoint, AnthropicGenerateExtensionPoint, AnthropicRuntimeConfig}
import org.simplemodeling.textus.ai.provider.codex.{CodexChatExtensionPoint, CodexGenerateExtensionPoint, CodexRuntimeConfig}
import org.simplemodeling.textus.ai.provider.claude.{ClaudeCodeChatExtensionPoint, ClaudeCodeGenerateExtensionPoint, ClaudeCodeRuntimeConfig}
import org.simplemodeling.textus.ai.provider.gemma.{GemmaChatExtensionPoint, GemmaGenerateExtensionPoint, GemmaRuntimeConfig}
import org.simplemodeling.textus.ai.provider.google.{GoogleChatExtensionPoint, GoogleGenerateExtensionPoint, GoogleRuntimeConfig}
import org.simplemodeling.textus.ai.provider.openai.{OpenAiChatExtensionPoint, OpenAiGenerateExtensionPoint, OpenAiRuntimeConfig}

object AiRuntimeGenerateBinding:
  def create(
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig]
  ): Component.Binding[GenerateRequirement, GenerateService] =
    _create(gemma, openai, google, codex, None, None, None)

  def create(
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig],
    claude: Option[ClaudeCodeRuntimeConfig]
  ): Component.Binding[GenerateRequirement, GenerateService] =
    _create(gemma, openai, google, codex, claude, None, None)

  private def _create_for_runtime(
    component: Component,
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig],
    claude: Option[ClaudeCodeRuntimeConfig],
    anthropic: Option[AnthropicRuntimeConfig]
  ): Component.Binding[GenerateRequirement, GenerateService] =
    _create(gemma, openai, google, codex, claude, anthropic, Some(component))

  private def _create(
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig],
    claude: Option[ClaudeCodeRuntimeConfig],
    anthropic: Option[AnthropicRuntimeConfig],
    runtimecomponent: Option[Component]
  ): Component.Binding[GenerateRequirement, GenerateService] =
    val spi =
      gemma.map(new GemmaGenerateExtensionPoint(_): ExtensionPoint[GenerateService]).toVector ++
        openai.map(new OpenAiGenerateExtensionPoint(_): ExtensionPoint[GenerateService]).toVector ++
        anthropic.map(new AnthropicGenerateExtensionPoint(_): ExtensionPoint[GenerateService]).toVector ++
        google.map(new GoogleGenerateExtensionPoint(_): ExtensionPoint[GenerateService]).toVector ++
        codex.map(_codex_extension_point(_, runtimecomponent)).toVector ++
        claude.map(_claude_extension_point(_, runtimecomponent)).toVector
    Component.Binding(
      Port(
        api = new GeneratePortApi {},
        spi = spi,
        variation = new GenerateVariationPoint {}
      )
    )

  def register(
    component: Component,
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig],
    claude: Option[ClaudeCodeRuntimeConfig] = None,
    anthropic: Option[AnthropicRuntimeConfig] = None
  ): Component =
    component.withBinding("generate", _create_for_runtime(component, gemma, openai, google, codex, claude, anthropic))

  private def _codex_extension_point(
    config: CodexRuntimeConfig,
    runtimecomponent: Option[Component]
  ): ExtensionPoint[GenerateService] =
    runtimecomponent match {
      case Some(component) => new CodexGenerateExtensionPoint(config)._with_runtime_component(component)
      case None => new CodexGenerateExtensionPoint(config)
    }

  private def _claude_extension_point(
    config: ClaudeCodeRuntimeConfig,
    runtimecomponent: Option[Component]
  ): ExtensionPoint[GenerateService] =
    runtimecomponent match {
      case Some(component) => new ClaudeCodeGenerateExtensionPoint(config)._with_runtime_component(component)
      case None => new ClaudeCodeGenerateExtensionPoint(config)
    }

object AiRuntimeChatBinding:
  def create(
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig]
  ): Component.Binding[GenerateRequirement, ChatService] =
    _create(gemma, openai, google, codex, None, None, None)

  def create(
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig],
    claude: Option[ClaudeCodeRuntimeConfig]
  ): Component.Binding[GenerateRequirement, ChatService] =
    _create(gemma, openai, google, codex, claude, None, None)

  private def _create_for_runtime(
    component: Component,
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig],
    claude: Option[ClaudeCodeRuntimeConfig],
    anthropic: Option[AnthropicRuntimeConfig]
  ): Component.Binding[GenerateRequirement, ChatService] =
    _create(gemma, openai, google, codex, claude, anthropic, Some(component))

  private def _create(
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig],
    claude: Option[ClaudeCodeRuntimeConfig],
    anthropic: Option[AnthropicRuntimeConfig],
    runtimecomponent: Option[Component]
  ): Component.Binding[GenerateRequirement, ChatService] =
    val spi =
      gemma.map(new GemmaChatExtensionPoint(_): ExtensionPoint[ChatService]).toVector ++
        openai.map(new OpenAiChatExtensionPoint(_): ExtensionPoint[ChatService]).toVector ++
        anthropic.map(new AnthropicChatExtensionPoint(_): ExtensionPoint[ChatService]).toVector ++
        google.map(new GoogleChatExtensionPoint(_): ExtensionPoint[ChatService]).toVector ++
        codex.map(_codex_extension_point(_, runtimecomponent)).toVector ++
        claude.map(_claude_extension_point(_, runtimecomponent)).toVector
    Component.Binding(
      Port(
        api = new ChatPortApi {},
        spi = spi,
        variation = new GenerateVariationPoint {}
      )
    )

  def register(
    component: Component,
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig],
    claude: Option[ClaudeCodeRuntimeConfig] = None,
    anthropic: Option[AnthropicRuntimeConfig] = None
  ): Component =
    component.withBinding("chat", _create_for_runtime(component, gemma, openai, google, codex, claude, anthropic))

  private def _codex_extension_point(
    config: CodexRuntimeConfig,
    runtimecomponent: Option[Component]
  ): ExtensionPoint[ChatService] =
    runtimecomponent match {
      case Some(component) => new CodexChatExtensionPoint(config)._with_runtime_component(component)
      case None => new CodexChatExtensionPoint(config)
    }

  private def _claude_extension_point(
    config: ClaudeCodeRuntimeConfig,
    runtimecomponent: Option[Component]
  ): ExtensionPoint[ChatService] =
    runtimecomponent match {
      case Some(component) => new ClaudeCodeChatExtensionPoint(config)._with_runtime_component(component)
      case None => new ClaudeCodeChatExtensionPoint(config)
    }
