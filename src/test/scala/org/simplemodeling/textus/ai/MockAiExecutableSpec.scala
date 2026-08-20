package org.simplemodeling.textus.ai

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.simplemodeling.model.value.MessageRole
import org.simplemodeling.textus.airuntime.ai.*

/*
 * @since   Apr. 10, 2026
 * @version Aug. 21, 2026
 * @author  ASAMI, Tomoharu
 */

final class MockAiExecutableSpec extends AnyFunSuite with Matchers {
  private final class MockAiAdapter extends LlmAdapter {
    override def generate(request: GenerateRequest): GenerateResponse =
      GenerateResponse(s"mock:${request.prompt.trim}")

    override def chat(request: ChatRequest): ChatResponse = {
      val lastUserMessage =
        request.messages.reverse.find(_.role == MessageRole.User).map(_.content.trim).getOrElse("hello")
      ChatResponse(Message(MessageRole.Assistant, s"mock:${lastUserMessage}"))
    }
  }

  test("Mock AI generates deterministic output from prompt") {
    val ai = new MockAiAdapter
    ai.generate(GenerateRequest("Write a short summary.")).text shouldBe "mock:Write a short summary."
  }

  test("Mock AI chats deterministically from the last user message") {
    val ai = new MockAiAdapter
    val request = ChatRequest(
      Vector(
        Message(MessageRole.System, "You are a helpful assistant."),
        Message(MessageRole.User, "Hello"),
        Message(MessageRole.Assistant, "Hi."),
        Message(MessageRole.User, "Tell me a joke.")
      )
    )

    ai.chat(request).message shouldBe Message(MessageRole.Assistant, "mock:Tell me a joke.")
  }

  test("Mock AI falls back to hello when no user message exists") {
    val ai = new MockAiAdapter
    ai.chat(ChatRequest(Vector(Message(MessageRole.System, "Ready.")))).message shouldBe
      Message(MessageRole.Assistant, "mock:hello")
  }
}
