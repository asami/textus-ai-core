package org.simplemodeling.textus.ai.runtime

import org.goldenport.cncf.component.*
import org.simplemodeling.textus.ai.provider.codex.{CodexChatExtensionPoint, CodexGenerateExtensionPoint, CodexRuntimeConfig}
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
    _create(gemma, openai, google, codex, None)

  private def _create_for_runtime(
    component: Component,
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig]
  ): Component.Binding[GenerateRequirement, GenerateService] =
    _create(gemma, openai, google, codex, Some(component))

  private def _create(
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig],
    runtimecomponent: Option[Component]
  ): Component.Binding[GenerateRequirement, GenerateService] =
    val spi =
      gemma.map(new GemmaGenerateExtensionPoint(_): ExtensionPoint[GenerateService]).toVector ++
        openai.map(new OpenAiGenerateExtensionPoint(_): ExtensionPoint[GenerateService]).toVector ++
        google.map(new GoogleGenerateExtensionPoint(_): ExtensionPoint[GenerateService]).toVector ++
        codex.map(_codex_extension_point(_, runtimecomponent)).toVector
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
    codex: Option[CodexRuntimeConfig]
  ): Component =
    component.withBinding("generate", _create_for_runtime(component, gemma, openai, google, codex))

  private def _codex_extension_point(
    config: CodexRuntimeConfig,
    runtimecomponent: Option[Component]
  ): ExtensionPoint[GenerateService] =
    runtimecomponent match {
      case Some(component) => new CodexGenerateExtensionPoint(config)._with_runtime_component(component)
      case None => new CodexGenerateExtensionPoint(config)
    }

object AiRuntimeChatBinding:
  def create(
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig]
  ): Component.Binding[GenerateRequirement, ChatService] =
    _create(gemma, openai, google, codex, None)

  private def _create_for_runtime(
    component: Component,
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig]
  ): Component.Binding[GenerateRequirement, ChatService] =
    _create(gemma, openai, google, codex, Some(component))

  private def _create(
    gemma: Option[GemmaRuntimeConfig],
    openai: Option[OpenAiRuntimeConfig],
    google: Option[GoogleRuntimeConfig],
    codex: Option[CodexRuntimeConfig],
    runtimecomponent: Option[Component]
  ): Component.Binding[GenerateRequirement, ChatService] =
    val spi =
      gemma.map(new GemmaChatExtensionPoint(_): ExtensionPoint[ChatService]).toVector ++
        openai.map(new OpenAiChatExtensionPoint(_): ExtensionPoint[ChatService]).toVector ++
        google.map(new GoogleChatExtensionPoint(_): ExtensionPoint[ChatService]).toVector ++
        codex.map(_codex_extension_point(_, runtimecomponent)).toVector
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
    codex: Option[CodexRuntimeConfig]
  ): Component =
    component.withBinding("chat", _create_for_runtime(component, gemma, openai, google, codex))

  private def _codex_extension_point(
    config: CodexRuntimeConfig,
    runtimecomponent: Option[Component]
  ): ExtensionPoint[ChatService] =
    runtimecomponent match {
      case Some(component) => new CodexChatExtensionPoint(config)._with_runtime_component(component)
      case None => new CodexChatExtensionPoint(config)
    }
