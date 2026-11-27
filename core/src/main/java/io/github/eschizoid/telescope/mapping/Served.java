package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The function a row holds where its factory was handed a {@link ServiceCall} or a {@link
 * ServiceSupplier}: the service function, the service it is bound to or none, and the row's own
 * accessors, which name the row in a refusal. Each kind implements the functional interface the
 * plain row holds, so the engine writes a service row through the same path as the plain one; a
 * mapper binds its service rows by building itself again from rows holding bound copies.
 *
 * <p>An unbound copy is never called: every operation of a mapper holding one refuses before it
 * reads anything. Calling one anyway throws the refusal that names the row.
 *
 * <p>Internal: users build these with {@code service(...)} and never name this type.
 */
public abstract sealed class Served implements Serializable {

  @Serial
  private static final long serialVersionUID = 1L;

  private final Serializable function;
  private final transient Object service;
  private final String row;
  private final Serializable[] accessors;

  Served(final Serializable function, final Object service, final String row, final Serializable... accessors) {
    this.function = Objects.requireNonNull(function, "function");
    this.service = service;
    this.row = row;
    this.accessors = accessors.clone();
  }

  /** The function, which takes the service first. */
  public final Serializable function() {
    return function;
  }

  /** The class of service the function takes. */
  public final Class<?> serviceClass() {
    return LambdaIntrospection.serviceOf(function).service();
  }

  /** The service this copy is bound to, or null. */
  public final Object service() {
    return service;
  }

  /** A copy of this function bound to {@code service}. */
  public abstract Served bind(Object service);

  /**
   * The row as it is written, with each accessor and the function named, as {@code
   * compute(OrderLine::getUnitPrice, service(Catalog::price))}.
   */
  public final String describe() {
    final var out = new StringBuilder(row).append('(');
    for (final var accessor : accessors) out.append(named(accessor)).append(", ");
    out.append("service(").append(functionName()).append(')');
    if (row.equals("when")) out.append(", ...");
    return out.append(')').toString();
  }

  /**
   * What {@code explain()} says the row does: the target property and the call, as {@code
   * OrderLine.unitPrice: computed by Catalog.price(LineDto)}, or for a {@code when} row the call
   * gating the rows it wraps.
   */
  public final String explain() {
    final String takes;
    try {
      final var read = LambdaIntrospection.serviceOf(function).takes();
      takes = read == null ? "" : PairingMessages.className(read);
    } catch (final RuntimeException unreadable) {
      return describe();
    }
    final var name = functionName();
    final var call = name.contains("::") ? name.replace("::", ".") + "(" + takes + ")" : name;
    if (row.equals("when")) return "rows gated by " + call;
    final var verb = switch (row) {
      case "toOneWay" -> "converted by ";
      case "toOrElseGet" -> "defaulted by ";
      default -> "computed by ";
    };
    return property(accessors[accessors.length - 1]) + ": " + verb + call;
  }

  /** A target accessor as {@code OrderLine.unitPrice}. */
  private static String property(final Serializable accessor) {
    try {
      final var owner = LambdaIntrospection.receiverClassOf(accessor);
      final var method = LambdaIntrospection.methodNameOf(accessor);
      return PairingMessages.className(owner) + "." + (owner.isRecord() ? method : PropertyNames.property(method));
    } catch (final RuntimeException unreadable) {
      return "a property";
    }
  }

  /** The function as {@code Owner::method}, or {@code a lambda}. */
  public final String functionName() {
    final String name;
    try {
      name = LambdaIntrospection.serviceOf(function).name();
    } catch (final RuntimeException unreadable) {
      return "a function of " + function.getClass().getName();
    }
    return name == null ? "a lambda" : name;
  }

  private static String named(final Serializable accessor) {
    try {
      final var owner = LambdaIntrospection.receiverClassOf(accessor);
      return PairingMessages.className(owner) + "::" + LambdaIntrospection.methodNameOf(accessor);
    } catch (final RuntimeException unreadable) {
      return "an accessor";
    }
  }

  /** The bound service, or the refusal naming the row where there is none. */
  final Object bound() {
    if (service == null) throw new IllegalStateException(PairingMessages.serviceRowUnbound(describe()));
    return service;
  }

  /** A row's function of the value it hands on: {@code compute}'s and {@code toOneWay}'s. */
  public static final class Value extends Served implements Mapping.FromSource<Object, Object> {

    @Serial
    private static final long serialVersionUID = 1L;

    Value(final Serializable function, final Object service, final String row, final Serializable... accessors) {
      super(function, service, row, accessors);
    }

    @Override
    public Value bind(final Object service) {
      return new Value(function(), Objects.requireNonNull(service, "service"), rowName(), accessors());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object apply(final Object value) {
      return ((BiFunction<Object, Object, Object>) function()).apply(bound(), value);
    }
  }

  /** {@code compute}'s supplier, asking the service alone. */
  public static final class Supply extends Served implements Supplier<Object> {

    @Serial
    private static final long serialVersionUID = 1L;

    Supply(final Serializable function, final Object service, final String row, final Serializable... accessors) {
      super(function, service, row, accessors);
    }

    @Override
    public Supply bind(final Object service) {
      return new Supply(function(), Objects.requireNonNull(service, "service"), rowName(), accessors());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object get() {
      return ((Function<Object, Object>) function()).apply(bound());
    }
  }

  /** {@code when}'s predicate of the source. */
  public static final class Test extends Served implements Predicate<Object> {

    @Serial
    private static final long serialVersionUID = 1L;

    Test(final Serializable function, final Object service) {
      super(function, service, "when");
    }

    @Override
    public Test bind(final Object service) {
      return new Test(function(), Objects.requireNonNull(service, "service"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean test(final Object value) {
      return (Boolean) ((BiFunction<Object, Object, Object>) function()).apply(bound(), value);
    }
  }

  /** {@code toOrElseGet}'s forward: the value read, or the service's when it is null. */
  public static final class OrElse extends Served implements Function<Object, Object> {

    @Serial
    private static final long serialVersionUID = 1L;

    OrElse(final Serializable function, final Object service, final Serializable... accessors) {
      super(function, service, "toOrElseGet", accessors);
    }

    @Override
    public OrElse bind(final Object service) {
      return new OrElse(function(), Objects.requireNonNull(service, "service"), accessors());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object apply(final Object value) {
      return value != null ? value : ((Function<Object, Object>) function()).apply(bound());
    }
  }

  final String rowName() {
    return row;
  }

  final Serializable[] accessors() {
    return accessors.clone();
  }
}
