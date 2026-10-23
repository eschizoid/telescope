package io.github.eschizoid.telescope.internal;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Function;

/**
 * A static method taking one value, as a {@link Function} the runtime path calls. On the JVM the
 * function is a class {@link LambdaMetafactory} spins over the method, which the JIT inlines
 * through; where no class can be spun, inside a native image or for a member the spinning lookup
 * cannot reach, it is a {@link MhAccessors} closure over the method's handle. Either way a
 * primitive parameter is unboxed from the value handed in and a primitive result boxed, and a value
 * of another class than the parameter's fails with {@link ClassCastException}, as a cast would.
 */
public final class StaticCalls {

  private StaticCalls() {}

  /**
   * {@code method}, a static method of one parameter, as a function.
   *
   * @throws IllegalArgumentException if {@code method} is not static or does not take one value
   * @throws IllegalStateException if the method's class is not open to this module
   */
  @SuppressWarnings("unchecked")
  public static Function<Object, Object> function(final Method method) {
    if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) {
      throw new IllegalArgumentException("Expected a static method of one parameter, got " + method);
    }
    final var declaring = method.getDeclaringClass();
    final MethodHandles.Lookup lookup;
    try {
      lookup = ModuleAccess.privateLookupIn(declaring);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(
        "Cannot call " + declaring.getName() + "." + method.getName() + ". " + ModuleAccess.opensRemedy(declaring),
        e
      );
    }
    try {
      final var handle = lookup.unreflect(method);
      final var spinner = ModuleAccess.spinner(lookup, method);
      if (spinner == null) return MhAccessors.function(handle);
      final var callSite = LambdaMetafactory.metafactory(
        spinner,
        "apply",
        MethodType.methodType(Function.class),
        MethodType.methodType(Object.class, Object.class),
        handle,
        MethodType.methodType(method.getReturnType(), method.getParameterTypes()[0]).wrap()
      );
      return (Function<Object, Object>) callSite.getTarget().invoke();
    } catch (final Throwable t) {
      throw new IllegalStateException("Cannot call " + declaring.getName() + "." + method.getName(), t);
    }
  }
}
