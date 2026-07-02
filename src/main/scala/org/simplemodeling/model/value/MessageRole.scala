package org.simplemodeling.model.value

import org.goldenport.Consequence
import org.goldenport.convert.ValueReader

enum MessageRole:
  case System, User, Assistant

object MessageRole:
  given ValueReader[MessageRole] with
    def readC(v: Any): Consequence[MessageRole] =
      v match
        case m: MessageRole => Consequence.success(m)
        case s: String =>
          parse(s) match
            case Some(x) => Consequence.success(x)
            case None => Consequence.valueInvalid(s"Unknown MessageRole: $s")
        case other =>
          Consequence.valueInvalid(s"Invalid MessageRole: $other")

  def parse(s: String): Option[MessageRole] =
    s.trim.toLowerCase(java.util.Locale.ROOT) match
      case "system" => Some(System)
      case "user" => Some(User)
      case "assistant" => Some(Assistant)
      case _ => None
