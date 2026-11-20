package io.github.eschizoid.telescope.quarkus.rest.runtime;

import java.util.List;

/**
 * Validates a request body as read, before it is mapped. The runtime looks the implementation up as
 * a bean and names no validation type itself, so an application without Hibernate Validator, where
 * the build registers no implementation, links none of the validation API, also in a native image.
 */
public interface BodyValidation {
  /**
   * @param values the values a body holds, each with the property path its violations start with
   * @throws RuntimeException the exception Quarkus answers with a {@code 400} when a value breaks
   *     its constraints
   */
  void check(final List<Named> values);

  /**
   * One value of a body and the property path its violations start with.
   *
   * @param path the path, as Quarkus names a resource method's parameter, such as {@code
   *     place.command} or {@code batch.commands[1]}
   * @param value the value as read, or {@code null}
   */
  record Named(String path, Object value) {}
}
