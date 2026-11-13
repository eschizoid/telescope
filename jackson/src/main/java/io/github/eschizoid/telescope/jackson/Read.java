package io.github.eschizoid.telescope.jackson;

import tools.jackson.core.JsonParser;

/**
 * How one JSON value becomes a value of one declared type. It is called with the parser on the
 * value's first token, which is never {@code null}: the caller answers a JSON {@code null} itself.
 * It leaves the parser on the value's last token, so the caller's next {@code nextToken} moves past
 * it.
 */
@FunctionalInterface
interface Read {
  /**
   * The value under the parser's current token.
   *
   * @throws Refused when the value is not one the declared type takes
   */
  Object from(JsonParser parser);
}
