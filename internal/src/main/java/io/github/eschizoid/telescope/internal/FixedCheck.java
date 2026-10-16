package io.github.eschizoid.telescope.internal;

import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The slot of a property whose value is handed across unchanged, checked against the class the side
 * it is written into fixes the property's type variable to. Each direction checks against its own
 * class, or checks nothing where that side fixes none.
 *
 * <p>An {@link Iso} so the array leaf runs it like any slot, and a type of its own so {@link MhIso}
 * reads it as an identity slot with a filter on the read: the composed handle then pays one type
 * test per checked value rather than a call through the slot.
 */
public final class FixedCheck implements Iso<Object, Object> {

  // (Object, Class, Class, String) -> Object  ==  checked(value, fixed, owner, property).
  private static final MethodHandle CHECKED;

  static {
    try {
      CHECKED = MethodHandles.lookup().findStatic(
        FixedCheck.class,
        "checked",
        MethodType.methodType(Object.class, Object.class, Class.class, Class.class, String.class)
      );
    } catch (final ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final Class<?> intoTarget;
  private final Class<?> target;
  private final String targetProperty;
  private final Class<?> intoSource;
  private final Class<?> source;
  private final String sourceProperty;

  /**
   * @param intoTarget the class the target fixes, or null where a forward write checks nothing
   * @param target the target class
   * @param targetProperty the target property written forward
   * @param intoSource the class the source fixes, or null where a backward write checks nothing
   * @param source the source class
   * @param sourceProperty the source property written backward
   */
  public FixedCheck(
    final Class<?> intoTarget,
    final Class<?> target,
    final String targetProperty,
    final Class<?> intoSource,
    final Class<?> source,
    final String sourceProperty
  ) {
    this.intoTarget = intoTarget;
    this.target = target;
    this.targetProperty = targetProperty;
    this.intoSource = intoSource;
    this.source = source;
    this.sourceProperty = sourceProperty;
  }

  @Override
  public Object to(final Object value) {
    return intoTarget == null ? value : checked(value, intoTarget, target, targetProperty);
  }

  @Override
  public Object from(final Object value) {
    return intoSource == null ? value : checked(value, intoSource, source, sourceProperty);
  }

  /** {@code (Object) -> Object} checking a forward write, or null where it checks nothing. */
  MethodHandle forwardFilter() {
    return intoTarget == null ? null : MethodHandles.insertArguments(CHECKED, 1, intoTarget, target, targetProperty);
  }

  /** {@code (Object) -> Object} checking a backward write, or null where it checks nothing. */
  MethodHandle backwardFilter() {
    return intoSource == null ? null : MethodHandles.insertArguments(CHECKED, 1, intoSource, source, sourceProperty);
  }

  /**
   * {@code value} itself, when it is null or an instance of {@code fixed}.
   *
   * @throws ClassCastException naming {@code property} of {@code owner}, the value's class and
   *     {@code fixed} when the value is of another class
   */
  public static <V> V checked(final V value, final Class<?> fixed, final Class<?> owner, final String property) {
    if (value == null || fixed.isInstance(value)) return value;
    throw new ClassCastException(
      PairingMessages.fixedClassMismatch(
        owner.getTypeName(),
        property,
        value.getClass().getTypeName(),
        fixed.getTypeName()
      )
    );
  }
}
