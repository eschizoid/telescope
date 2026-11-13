package io.github.eschizoid.telescope.jackson;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.ArrayDeque;
import java.util.function.Function;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

/**
 * A value the binder refuses, on its way out of the nested readers. Each level it passes through
 * adds where it is, a property name or an element position, so the innermost reader needs no path
 * and a value that binds pays nothing for one. {@link JsonBinder} turns it into the {@link
 * IllegalArgumentException} a caller sees, with the message rendered once the path is complete.
 */
final class Refused extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient ArrayDeque<String> segments = new ArrayDeque<>();
  private final transient Function<String, String> message;

  private Refused(final Function<String, String> message, final Throwable cause) {
    super(null, cause, false, false);
    this.message = message;
  }

  /**
   * The value under the parser's current token, which is not {@code what}: a value the property's
   * type does not take, or one it takes but cannot hold, such as an integer past its range.
   */
  static Refused value(final JsonParser parser, final String what, final Throwable cause) {
    final var got = got(parser);
    final var at = parser.currentTokenLocation();
    final var line = at.getLineNr();
    final var column = at.getColumnNr();
    return new Refused(path -> PairingMessages.jsonNotAValue(path, got, what, line, column), cause);
  }

  /** A refusal whose whole message, given the path, is {@code message}. */
  static Refused of(final Function<String, String> message) {
    return new Refused(message, null);
  }

  /** The refusal, now inside the property {@code name}. */
  Refused under(final String name) {
    segments.addFirst(name);
    return this;
  }

  /** The refusal, now inside the element at {@code position} of a list, a set or an object. */
  Refused at(final Object position) {
    segments.addFirst(PairingMessages.elementPosition(position));
    return this;
  }

  /** The exception a caller sees. */
  IllegalArgumentException toException() {
    return new IllegalArgumentException(message.apply(path()), getCause());
  }

  @Override
  public String getMessage() {
    return message.apply(path());
  }

  /**
   * The path the levels added, properties joined by a dot and positions written after what holds
   * them: {@code lines[1].quantity}.
   */
  private String path() {
    final var path = new StringBuilder();
    for (final var segment : segments) {
      if (!segment.startsWith("[") && !path.isEmpty()) path.append('.');
      path.append(segment);
    }
    return path.toString();
  }

  /**
   * The value under the current token as the document writes it: text in quotes, a number or a
   * literal as it stands, and a structure by its kind.
   */
  private static String got(final JsonParser parser) {
    final var token = parser.currentToken();
    if (token == null) return "no value";
    return switch (token) {
      case START_OBJECT -> "an object";
      case START_ARRAY -> "an array";
      case VALUE_STRING -> "\"" + parser.getString() + "\"";
      case PROPERTY_NAME -> "\"" + parser.currentName() + "\"";
      default -> token == JsonToken.VALUE_NULL ? "null" : parser.getString();
    };
  }
}
