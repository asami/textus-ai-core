package org.simplemodeling.textus.ai.provider.codex

import io.circe.parser.parse
import org.goldenport.Consequence

/** Consume the admitted CLI's control stream, never fields in agent text.
  * Unknown events/annotations do not certify or invalidate completion. Missing
  * optional context stays unknown; an ambiguous context is not guessed.
  * @since   Oct.  9, 2026
  * @version Oct. 11, 2026
  */
private[codex] object CodexExecutionEvents {
  final case class Reply(text: String, contextidentity: Option[String])
  private final case class Stream(context: Option[String] = None, ambiguous: Boolean = false,
    message: Option[String] = None, completed: Boolean = false)

  def decodeC(raw: String): Consequence[Reply] = {
    val stream = raw.linesIterator.foldLeft(Stream()) { (state, line) =>
      parse(line).toOption.fold(state) { event =>
        val cursor = event.hcursor
        cursor.get[String]("type").toOption match {
          case Some("thread.started") =>
            val observed = cursor.get[String]("thread_id").toOption
              .filter(value => value.nonEmpty && !value.exists(_.isControl))
            state.copy(context = state.context.orElse(observed),
              ambiguous = state.ambiguous || (state.context.nonEmpty && observed.nonEmpty && state.context != observed))
          case Some("turn.started") => state.copy(message = None, completed = false)
          case Some("item.completed") if !state.completed &&
              cursor.downField("item").get[String]("type").contains("agent_message") =>
            state.copy(message = cursor.downField("item").get[String]("text").toOption)
          case Some("turn.completed") => state.copy(completed = true)
          case Some("turn.failed") | Some("error") => state.copy(message = None, completed = false)
          case _ => state
        }
      }
    }
    stream.message.filter(_.trim.nonEmpty) match {
      case Some(text) if stream.completed =>
        Consequence.success(Reply(text, if (stream.ambiguous) None else stream.context))
      case _ => Consequence.valueInvalid("Codex CLI returned no completed agent message")
    }
  }
}
