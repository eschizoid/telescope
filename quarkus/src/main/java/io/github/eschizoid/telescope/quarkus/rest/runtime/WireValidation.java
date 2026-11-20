package io.github.eschizoid.telescope.quarkus.rest.runtime;

import io.quarkus.hibernate.validator.runtime.jaxrs.ResteasyReactiveViolationException;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Validates a request body as read through the application's {@link Validator}. The build makes it
 * a bean only when Hibernate Validator is present and some body parameter carries {@code @Valid}.
 *
 * <p>A violation is thrown as the exception Quarkus throws for an invalid REST parameter, which its
 * exception mapper answers with a {@code 400}. Each violation's path starts with the method and
 * parameter, as Quarkus names a violation of a resource method's parameter, followed by the wire
 * type's property, such as {@code place.command.buyer}.
 */
public class WireValidation implements BodyValidation {

  private final Validator validator;

  /**
   * @param validator the application's validator
   */
  @Inject
  public WireValidation(final Validator validator) {
    this.validator = validator;
  }

  @Override
  public void check(final List<Named> values) {
    final var violations = new LinkedHashSet<ConstraintViolation<?>>();
    for (final var value : values)
      if (value.value() != null) for (final var violation : validator.validate(value.value()))
        violations.add(new Prefixed<>(violation, value.path()));
    if (!violations.isEmpty()) throw new ResteasyReactiveViolationException(violations);
  }

  // A violation of the value as read, reported under the path that starts with the method and its
  // parameter.
  private record Prefixed<T>(ConstraintViolation<T> violation, Path path) implements ConstraintViolation<T> {
    Prefixed(final ConstraintViolation<T> violation, final String prefix) {
      this(violation, new PrefixedPath(prefix, violation.getPropertyPath()));
    }

    @Override
    public String getMessage() {
      return violation.getMessage();
    }

    @Override
    public String getMessageTemplate() {
      return violation.getMessageTemplate();
    }

    @Override
    public T getRootBean() {
      return violation.getRootBean();
    }

    @Override
    public Class<T> getRootBeanClass() {
      return violation.getRootBeanClass();
    }

    @Override
    public Object getLeafBean() {
      return violation.getLeafBean();
    }

    @Override
    public Object[] getExecutableParameters() {
      return violation.getExecutableParameters();
    }

    @Override
    public Object getExecutableReturnValue() {
      return violation.getExecutableReturnValue();
    }

    @Override
    public Path getPropertyPath() {
      return path;
    }

    @Override
    public Object getInvalidValue() {
      return violation.getInvalidValue();
    }

    @Override
    public ConstraintDescriptor<?> getConstraintDescriptor() {
      return violation.getConstraintDescriptor();
    }

    @Override
    public <U> U unwrap(final Class<U> type) {
      return violation.unwrap(type);
    }
  }

  // The path a violation of a resource method's parameter has: the method, the parameter, then the
  // property of the value. Its text is the prefix the build gave, which carries a list element's
  // index, followed by the property.
  private static final class PrefixedPath implements Path {

    private final String text;
    private final List<Node> nodes;

    PrefixedPath(final String prefix, final Path property) {
      final var rest = property.toString();
      this.text = rest.isEmpty() ? prefix : prefix + "." + rest;
      final var parts = prefix.split("\\.", 2);
      this.nodes = new ArrayList<>();
      nodes.add(new Step(parts[0], ElementKind.METHOD));
      if (parts.length > 1) nodes.add(new Step(parts[1], ElementKind.PARAMETER));
      for (final var node : property) nodes.add(node);
    }

    @Override
    public Iterator<Node> iterator() {
      return nodes.iterator();
    }

    @Override
    public String toString() {
      return text;
    }
  }

  // The method or parameter step of a prefixed path.
  private record Step(String name, ElementKind kind) implements Path.MethodNode, Path.ParameterNode {
    @Override
    public String getName() {
      return name;
    }

    @Override
    public boolean isInIterable() {
      return false;
    }

    @Override
    public Integer getIndex() {
      return null;
    }

    @Override
    public Object getKey() {
      return null;
    }

    @Override
    public ElementKind getKind() {
      return kind;
    }

    @Override
    public List<Class<?>> getParameterTypes() {
      return List.of();
    }

    @Override
    public int getParameterIndex() {
      return 0;
    }

    @Override
    public <N extends Path.Node> N as(final Class<N> nodeType) {
      return nodeType.cast(this);
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
