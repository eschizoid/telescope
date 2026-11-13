package io.github.eschizoid.telescope.jackson;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.introspection.OpticReport;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

/**
 * Reads JSON into a record or a bean over {@code jackson-core}'s streaming parser, with no tree and
 * no map in between. Built by {@link TelescopeJson#fromJson TelescopeJson.fromJson}, which decides
 * once how every property of the target, and of every type it holds, is read. Reusable and safe to
 * share between threads.
 *
 * <p>The rules, per value:
 *
 * <ul>
 *   <li>A key that is missing and a key that holds {@code null} give the same value: the default for
 *       the property's declared type, a primitive's zero, an empty {@code List}, {@code Set}, {@code
 *       Map} or {@code Optional}, and {@code null} for anything else. A converter is called only for a
 *       value that is present. A key no property reads is skipped, whatever it holds.
 *   <li>A key that appears twice in one object keeps the value it holds last.
 *   <li>A value is read only from the JSON kind its type is written as: a number from a number, text
 *       from a string, a boolean from {@code true} or {@code false}. An integral property refuses a
 *       fraction and an integer past its range rather than truncate or wrap it, and {@code BigDecimal}
 *       keeps every digit. A row's converter reads another kind where the payload needs one.
 *   <li>A record or a bean outside the JDK is read from a nested object by the same rules, a {@code
 *       List} or {@code Set} from an array, a {@code Map} from an object, and an enum constant from its
 *       exact name.
 * </ul>
 *
 * <p>A value the rules refuse throws {@link IllegalArgumentException} naming where it is and what it
 * is: {@code 'lines[1].quantity' got 1.5, not an int, at line 3, column 18}. JSON that does not
 * parse throws {@code jackson-core}'s own {@code StreamReadException}, which carries its location.
 *
 * @param <T> the type the binder builds
 */
public final class JsonBinder<T> {

  private static final JsonFactory FACTORY = new JsonFactory();

  private final Frame<T> root;
  private final String what;

  JsonBinder(final Frame<T> root) {
    this.root = root;
    this.what = PairingMessages.withArticle(root.type().getSimpleName());
  }

  /**
   * The value the JSON text holds, or {@code null} for the document {@code null}.
   *
   * @param json a JSON object
   * @return the value built
   */
  public T read(final String json) {
    Objects.requireNonNull(json, "json");
    try (final var parser = FACTORY.createParser(ObjectReadContext.empty(), json)) {
      return document(parser);
    }
  }

  /**
   * The value the JSON in {@code bytes} holds, in UTF-8 or any encoding JSON allows.
   *
   * @param bytes a JSON object
   * @return the value built
   */
  public T read(final byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    try (final var parser = FACTORY.createParser(ObjectReadContext.empty(), bytes)) {
      return document(parser);
    }
  }

  /**
   * The value the JSON in {@code in} holds. The stream is read to the end of the value and closed.
   *
   * @param in a JSON object
   * @return the value built
   */
  public T read(final InputStream in) {
    Objects.requireNonNull(in, "in");
    try (final var parser = FACTORY.createParser(ObjectReadContext.empty(), in)) {
      return document(parser);
    }
  }

  /**
   * The values a JSON array of objects holds, in order, or {@code null} for the document {@code
   * null}. A {@code null} element is {@code null} in the list.
   *
   * @param json a JSON array
   * @return the values built
   */
  public List<T> readList(final String json) {
    Objects.requireNonNull(json, "json");
    try (final var parser = FACTORY.createParser(ObjectReadContext.empty(), json)) {
      parser.nextToken();
      return list(parser);
    }
  }

  /**
   * The values the JSON array in {@code in} holds. The stream is read to the end of the array and
   * closed.
   *
   * @param in a JSON array
   * @return the values built
   */
  public List<T> readList(final InputStream in) {
    Objects.requireNonNull(in, "in");
    try (final var parser = FACTORY.createParser(ObjectReadContext.empty(), in)) {
      parser.nextToken();
      return list(parser);
    }
  }

  /**
   * The value under a parser the caller owns, such as the one a custom deserializer is handed. A
   * fresh parser is advanced to its first token; one already on a token reads from there. The
   * parser is left on the value's last token and is not closed.
   *
   * @param parser a parser on a JSON object, or before one
   * @return the value built, or {@code null} for a JSON {@code null}
   */
  public T parse(final JsonParser parser) {
    Objects.requireNonNull(parser, "parser");
    if (parser.currentToken() == null) {
      parser.nextToken();
    }
    return value(parser);
  }

  /**
   * As {@link #parse(JsonParser)}, for a parser on a JSON array of objects.
   *
   * @param parser a parser on a JSON array, or before one
   * @return the values built, or {@code null} for a JSON {@code null}
   */
  public List<T> parseList(final JsonParser parser) {
    Objects.requireNonNull(parser, "parser");
    if (parser.currentToken() == null) {
      parser.nextToken();
    }
    return list(parser);
  }

  /**
   * The binding plan, decided when the binder was built: for each property of the target, the key
   * it reads and whether a missing key defaults or refuses the document. A bean property its writer
   * cannot set is reported as skipped.
   *
   * @return the plan; never null
   */
  public OpticReport explain() {
    return new OpticReport(root.explain());
  }

  @Override
  public String toString() {
    return "JsonBinder[" + root.type().getName() + "]";
  }

  private T document(final JsonParser parser) {
    parser.nextToken();
    return value(parser);
  }

  private T value(final JsonParser parser) {
    final var token = parser.currentToken();
    if (token == JsonToken.VALUE_NULL) {
      return null;
    }
    try {
      if (token != JsonToken.START_OBJECT) {
        throw Refused.value(parser, what, null);
      }
      return root.read(parser);
    } catch (final Refused refused) {
      throw refused.toException();
    }
  }

  private List<T> list(final JsonParser parser) {
    final var token = parser.currentToken();
    if (token == JsonToken.VALUE_NULL) {
      return null;
    }
    try {
      if (token != JsonToken.START_ARRAY) {
        throw Refused.value(parser, "an array of " + root.type().getSimpleName(), null);
      }
      final var out = new ArrayList<T>();
      var index = 0;
      for (var element = parser.nextToken(); element != JsonToken.END_ARRAY; element = parser.nextToken()) {
        if (element == JsonToken.VALUE_NULL) {
          out.add(null);
        } else if (element == JsonToken.START_OBJECT) {
          try {
            out.add(root.read(parser));
          } catch (final Refused refused) {
            throw refused.at(index);
          }
        } else {
          throw Refused.value(parser, what, null).at(index);
        }
        index++;
      }
      return out;
    } catch (final Refused refused) {
      throw refused.toException();
    }
  }
}
