package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The slot of a property a conversion of the row's own fills, a transform or a mapper, whose result
 * is checked against the class the member the property's type chooses takes, before it is written
 * through that member. Each direction checks against its own class, or checks nothing where the
 * side it writes has none to check.
 *
 * <p>The value read for the conversion is checked too, where the row reads a property declared with
 * a type variable as a class narrower than the variable's bound: against that class, before the
 * conversion is handed it. Each direction checks the side it reads, or checks nothing where the row
 * reads the property as a class every value of it is.
 *
 * <p>The row's conversion is typed by its functions, which the runtime cannot read, so a result of
 * a class the member does not take would otherwise reach the write and fail there without a name,
 * and a value of a class the conversion does not take would fail inside it without one. An {@link
 * Iso} so the array leaf runs it like any slot, and a type of its own so {@link MhIso} reads it as
 * the conversion's own slot with a filter before and after it, keeping the pair on the composed
 * handle.
 */
public final class ResultCheck implements Iso<Object, Object> {

  // (Object, Class, Class, String, Object) -> Object  ==  checkedOr(value, expected, owner,
  // property,
  // fallback).
  private static final MethodHandle CHECKED_OR;

  // (Object, Class, Class, String) -> Object  ==  argument(value, expected, owner, property).
  private static final MethodHandle ARGUMENT;

  static {
    try {
      final var lookup = MethodHandles.lookup();
      CHECKED_OR = lookup.findStatic(
        ResultCheck.class,
        "checkedOr",
        MethodType.methodType(Object.class, Object.class, Class.class, Class.class, String.class, Object.class)
      );
      ARGUMENT = lookup.findStatic(
        ResultCheck.class,
        "argument",
        MethodType.methodType(Object.class, Object.class, Class.class, Class.class, String.class)
      );
    } catch (final ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final Iso<Object, Object> conversion;
  private final Class<?> intoTarget;
  private final Class<?> target;
  private final String targetProperty;
  private final Object targetNull;
  private final Class<?> intoSource;
  private final Class<?> source;
  private final String sourceProperty;
  private final Object sourceNull;
  private final Class<?> fromSource;
  private final Class<?> fromTarget;

  /**
   * @param conversion the row's own conversion
   * @param intoTarget the class the target's member takes, or null where a forward write checks
   *     nothing
   * @param target the target class
   * @param targetProperty the target property written forward
   * @param targetNull what the target's member takes in place of a null result: a primitive
   *     argument's default, or null
   * @param intoSource the class the source's member takes, or null where a backward write checks
   *     nothing
   * @param source the source class
   * @param sourceProperty the source property written backward
   * @param sourceNull what the source's member takes in place of a null result
   * @param fromSource the class the forward conversion takes, which the source property's value is
   *     checked against before it is handed over, or null where a forward read checks nothing
   * @param fromTarget the class the backward conversion takes, which the target property's value is
   *     checked against before it is handed over, or null where a backward read checks nothing
   */
  public ResultCheck(
    final Iso<Object, Object> conversion,
    final Class<?> intoTarget,
    final Class<?> target,
    final String targetProperty,
    final Object targetNull,
    final Class<?> intoSource,
    final Class<?> source,
    final String sourceProperty,
    final Object sourceNull,
    final Class<?> fromSource,
    final Class<?> fromTarget
  ) {
    this.conversion = conversion;
    this.intoTarget = intoTarget;
    this.target = target;
    this.targetProperty = targetProperty;
    this.targetNull = targetNull;
    this.intoSource = intoSource;
    this.source = source;
    this.sourceProperty = sourceProperty;
    this.sourceNull = sourceNull;
    this.fromSource = fromSource;
    this.fromTarget = fromTarget;
  }

  @Override
  public Object to(final Object value) {
    final var converted = conversion.to(
      fromSource == null ? value : argument(value, fromSource, source, sourceProperty)
    );
    return intoTarget == null ? converted : checkedOr(converted, intoTarget, target, targetProperty, targetNull);
  }

  @Override
  public Object from(final Object value) {
    final var converted = conversion.from(
      fromTarget == null ? value : argument(value, fromTarget, target, targetProperty)
    );
    return intoSource == null ? converted : checkedOr(converted, intoSource, source, sourceProperty, sourceNull);
  }

  /** The row's own conversion, which a composed handle runs before the check. */
  Iso<Object, Object> conversion() {
    return conversion;
  }

  /** {@code (Object) -> Object} checking a forward result, or null where it checks nothing. */
  MethodHandle forwardFilter() {
    return intoTarget == null
      ? null
      : MethodHandles.insertArguments(CHECKED_OR, 1, intoTarget, target, targetProperty, targetNull);
  }

  /**
   * {@code (Object) -> Object} checking the value a forward conversion is handed, or null where it
   * checks nothing.
   */
  MethodHandle forwardArgumentFilter() {
    return fromSource == null ? null : MethodHandles.insertArguments(ARGUMENT, 1, fromSource, source, sourceProperty);
  }

  /**
   * {@code (Object) -> Object} checking the value a backward conversion is handed, or null where it
   * checks nothing.
   */
  MethodHandle backwardArgumentFilter() {
    return fromTarget == null ? null : MethodHandles.insertArguments(ARGUMENT, 1, fromTarget, target, targetProperty);
  }

  /** {@code (Object) -> Object} checking a backward result, or null where it checks nothing. */
  MethodHandle backwardFilter() {
    return intoSource == null
      ? null
      : MethodHandles.insertArguments(CHECKED_OR, 1, intoSource, source, sourceProperty, sourceNull);
  }

  /**
   * {@code fallback} in place of a null {@code value}, and {@link #checked} of any other: what a
   * primitive record component, constructor parameter or builder member takes for a null result.
   */
  private static Object checkedOr(
    final Object value,
    final Class<?> expected,
    final Class<?> owner,
    final String property,
    final Object fallback
  ) {
    return value == null ? fallback : checked(value, expected, owner, property);
  }

  /**
   * {@code value} itself, when it is null or an instance of {@code expected}.
   *
   * @throws ClassCastException naming {@code property} of {@code owner}, the value's class and
   *     {@code expected} when the value is of another class
   */
  public static <V> V checked(final V value, final Class<?> expected, final Class<?> owner, final String property) {
    if (value == null || expected.isInstance(value)) return value;
    throw new ClassCastException(
      PairingMessages.resultClassMismatch(
        owner.getTypeName(),
        property,
        value.getClass().getTypeName(),
        expected.getTypeName()
      )
    );
  }

  /**
   * {@code value} itself, when it is null or an instance of {@code expected}: the check of a value
   * read from a property declared with a type variable, before a conversion that takes {@code
   * expected}, a class narrower than the variable's bound, is handed it.
   *
   * @throws ClassCastException naming {@code property} of {@code owner}, the value's class and
   *     {@code expected} when the value is of another class
   */
  public static <V> V argument(final V value, final Class<?> expected, final Class<?> owner, final String property) {
    if (value == null || expected.isInstance(value)) return value;
    throw new ClassCastException(
      PairingMessages.argumentClassMismatch(
        owner.getTypeName(),
        property,
        value.getClass().getTypeName(),
        expected.getTypeName()
      )
    );
  }
}
