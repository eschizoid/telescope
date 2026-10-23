package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.StaticCalls;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.mapping.Via;
import io.github.eschizoid.telescope.mapping.ViaClass;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The runtime path's reading of a {@code via(src, tgt, Hw.class)} row: the {@code forward} and
 * {@code backward} of the class chosen for the property's types, as the {@code @Bridge} processor
 * chooses them for a {@code @ViaMapper(using = Hw.class)}, made into the {@link Via} row the deep
 * mapping already converts through. Where the two sides are containers of one kind and the class
 * has a {@code forward} taking the source's element and a {@code backward} taking the target's, and
 * not a pair of overloads converting the whole property both ways, the row converts element by
 * element. Otherwise each direction calls the overload Java binds for the property's value: the
 * most specific one it applies to, without boxing where one applies so, then a generic one whose
 * type variable admits the value, and otherwise the one method a value of the property can be an
 * instance of the parameter of, which the call checks the value's class against.
 */
final class ViaClasses {

  private ViaClasses() {}

  private static final Map<Class<?>, Set<Class<?>>> WIDENS_TO = Map.of(
    byte.class,
    Set.of(short.class, int.class, long.class, float.class, double.class),
    short.class,
    Set.of(int.class, long.class, float.class, double.class),
    char.class,
    Set.of(int.class, long.class, float.class, double.class),
    int.class,
    Set.of(long.class, float.class, double.class),
    long.class,
    Set.of(float.class, double.class),
    float.class,
    Set.of(double.class)
  );

  /**
   * The via row {@code row} makes for a property the source declares as {@code srcType} and the
   * target as {@code tgtType}; {@code srcElement} and {@code tgtElement} are the elements of those
   * properties when they are containers of one kind, and null otherwise.
   *
   * @throws IllegalArgumentException if the class has no method of a name to call, or several the
   *     element's class could call for
   */
  static Via<?, ?> resolve(
    final ViaClass<?, ?> row,
    final Type srcType,
    final Type tgtType,
    final Type srcElement,
    final Type tgtElement,
    final String source,
    final String target,
    final String srcField,
    final String tgtField
  ) {
    final var using = row.using();
    // A method reference is spun in the package of the class it is written in, which is where the
    // row is declared, and the package the generated bridge of a @Bridge field is written into.
    final var caller = row.src().getClass().getPackageName();
    final var forwards = candidates(using, "forward", caller);
    final var backwards = candidates(using, "backward", caller);
    final var name = using.getName();
    if (forwards.isEmpty()) {
      throw new IllegalArgumentException(PairingMessages.viaClassDeclaresNo(source, target, srcField, name, "forward"));
    }
    if (backwards.isEmpty()) {
      throw new IllegalArgumentException(
        PairingMessages.viaClassDeclaresNo(source, target, srcField, name, "backward")
      );
    }
    if (
      srcElement != null &&
      tgtElement != null &&
      !(convertsWhole(forwards, srcType, tgtType) && convertsWhole(backwards, tgtType, srcType))
    ) {
      final var forward = elementMethods(forwards, srcElement, srcType);
      final var backward = elementMethods(backwards, tgtElement, tgtType);
      if (!forward.isEmpty() && !backward.isEmpty()) {
        if (forward.size() > 1 || backward.size() > 1) {
          final var method = forward.size() > 1 ? "forward" : "backward";
          final var container = forward.size() > 1 ? srcType : tgtType;
          throw new IllegalArgumentException(
            PairingMessages.viaClassRefused(
              source,
              target,
              srcField,
              name,
              method,
              PairingMessages.viaSeveralElementOverloads(container.getTypeName())
            )
          );
        }
        return via(
          row,
          forward.getFirst(),
          backward.getFirst(),
          elementClass(srcElement, forward.getFirst()),
          elementClass(tgtElement, backward.getFirst())
        );
      }
    }
    final var srcHeld = DeepMap.heldClassOf(srcType);
    final var tgtHeld = DeepMap.heldClassOf(tgtType);
    refuseNeverFits(forwards, srcHeld, source, target, "source", srcField);
    refuseNeverFits(backwards, tgtHeld, source, target, "target", tgtField);
    final var forward = call(forwards, srcType, srcHeld);
    if (forward == null) {
      throw new IllegalArgumentException(
        PairingMessages.viaClassRefused(source, target, srcField, name, "forward", unbound(forwards, srcType, srcHeld))
      );
    }
    final var backward = call(backwards, tgtType, tgtHeld);
    if (backward == null) {
      throw new IllegalArgumentException(
        PairingMessages.viaClassRefused(
          source,
          target,
          srcField,
          name,
          "backward",
          unbound(backwards, tgtType, tgtHeld)
        )
      );
    }
    return via(row, forward, backward, boxed(forward.getParameterTypes()[0]), boxed(backward.getParameterTypes()[0]));
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Via<?, ?> via(
    final ViaClass<?, ?> row,
    final Method forward,
    final Method backward,
    final Class<?> from,
    final Class<?> to
  ) {
    final var mapper = Mapper.create(
      StaticCalls.function(forward),
      StaticCalls.function(backward),
      (Class<Object>) from,
      (Class<Object>) to,
      Map.of()
    );
    return new Via(row.src(), row.tgt(), mapper);
  }

  /**
   * Every static method named {@code name} taking one value that code in the package {@code caller}
   * calls on {@code using}: of the members {@code using} declares or inherits from a superclass,
   * those that are public, and those that are neither public nor private and declared in {@code
   * caller}. A superclass's method is inherited unless a method a subclass declares with the same
   * parameter hides it, and, where it is package-private, only through classes all in its package.
   * These are the members the generated bridge, written into that package, can call by the class's
   * name.
   */
  private static List<Method> candidates(final Class<?> using, final String name, final String caller) {
    final var found = new ArrayList<Method>();
    final var hidden = new ArrayList<Class<?>>();
    var onePackage = true;
    for (Class<?> type = using; type != null && type != Object.class; type = type.getSuperclass()) {
      onePackage &= type.getPackageName().equals(using.getPackageName());
      for (final var method : type.getDeclaredMethods()) {
        final var modifiers = method.getModifiers();
        if (
          !method.getName().equals(name) ||
          method.getParameterCount() != 1 ||
          !Modifier.isStatic(modifiers) ||
          method.isSynthetic()
        ) {
          continue;
        }
        final var parameter = method.getParameterTypes()[0];
        if (hidden.contains(parameter)) continue;
        hidden.add(parameter);
        if (Modifier.isPrivate(modifiers)) continue;
        if (Modifier.isPublic(modifiers)) {
          found.add(method);
          continue;
        }
        final var inherited = type == using || Modifier.isProtected(modifiers) || onePackage;
        if (inherited && type.getPackageName().equals(caller)) found.add(method);
      }
    }
    return found;
  }

  /**
   * Whether one of {@code methods} converts the whole property: it takes a value of {@code from}
   * and gives one {@code to} holds, or a result its own type variable types, which the call infers.
   */
  private static boolean convertsWhole(final List<Method> methods, final Type from, final Type to) {
    final var taken = DeepMap.heldClassOf(from);
    final var held = DeepMap.heldClassOf(to);
    for (final var method : methods) {
      if (!assignable(taken, method.getParameterTypes()[0])) continue;
      if (assignable(method.getReturnType(), held)) return true;
      if (ownVariable(method.getGenericReturnType(), method) && !mentionsVariable(from)) return true;
    }
    return false;
  }

  /**
   * The methods of {@code methods} that take one {@code element} of {@code container}: those taking
   * the element's own class where there are any, and otherwise those taking a class the element is
   * one of; for an element a type variable types, those taking a class within its bound that the
   * container itself is not. A primitive parameter takes its wrapper's elements.
   */
  private static List<Method> elementMethods(final List<Method> methods, final Type element, final Type container) {
    final var containerClass = DeepMap.heldClassOf(container);
    if (element instanceof TypeVariable<?> variable) {
      final var bound = DeepMap.boundOf(variable);
      return methods
        .stream()
        .filter(m -> bound.isAssignableFrom(boxed(m.getParameterTypes()[0])))
        .filter(m -> !m.getParameterTypes()[0].isAssignableFrom(containerClass))
        .toList();
    }
    final var held = boxed(DeepMap.heldClassOf(element));
    final var exact = methods
      .stream()
      .filter(m -> boxed(m.getParameterTypes()[0]) == held)
      .toList();
    if (!exact.isEmpty()) return exact;
    return methods
      .stream()
      .filter(m -> boxed(m.getParameterTypes()[0]).isAssignableFrom(held))
      .filter(m -> !m.getParameterTypes()[0].isAssignableFrom(containerClass))
      .toList();
  }

  /**
   * The class a lifted mapper converts on one side: the element's own class, or, for an element a
   * type variable types, the class the chosen method takes.
   */
  private static Class<?> elementClass(final Type element, final Method method) {
    if (element instanceof TypeVariable<?>) return boxed(method.getParameterTypes()[0]);
    return boxed(DeepMap.heldClassOf(element));
  }

  /**
   * Refuses a direction none of whose {@code methods} takes a class a value of {@code held} can be
   * an instance of, in the words a {@code via} row with a mapper is refused in.
   */
  private static void refuseNeverFits(
    final List<Method> methods,
    final Class<?> held,
    final String source,
    final String target,
    final String side,
    final String property
  ) {
    for (final var method : methods) {
      if (DeepMap.mayBeInstance(boxed(held), boxed(method.getParameterTypes()[0]))) return;
    }
    throw new IllegalArgumentException(
      PairingMessages.viaMapperNeverFits(
        source,
        target,
        side,
        property,
        boxed(methods.getFirst().getParameterTypes()[0]).getSimpleName()
      )
    );
  }

  /**
   * Why {@link #call} binds none of {@code methods} for a value of {@code own}, held as {@code
   * held}: several that its class alone could tell apart, or none taking it.
   */
  private static String unbound(final List<Method> methods, final Type own, final Class<?> held) {
    final var phrase = mentionsVariable(own)
      ? PairingMessages.heldPhrase(own.getTypeName(), held.getTypeName())
      : own.getTypeName();
    return blind(methods, own, held)
      ? PairingMessages.viaChoosesBlind(phrase)
      : PairingMessages.viaTakesNoValue(phrase);
  }

  /**
   * Whether a value of {@code own} held as {@code held} reaches several of {@code methods} that
   * nothing but its class tells apart: a variable with no bound is held as {@code Object}.
   */
  private static boolean blind(final List<Method> methods, final Type own, final Class<?> held) {
    return methods.size() > 1 && own instanceof TypeVariable<?> && held == Object.class;
  }

  /**
   * The method of {@code methods} a call handed a value of {@code own}, held as {@code held},
   * binds, or null where it binds none. A value typed by a variable with no bound is held as {@code
   * Object}, which tells several overloads apart by nothing, so there the call binds only the one
   * method a value can be an instance of the parameter of.
   */
  private static Method call(final List<Method> methods, final Type own, final Class<?> held) {
    if (!blind(methods, own, held)) {
      final var bound = mostSpecific(methods, held);
      if (bound != null) return bound;
      final var inferring = inferring(methods, held);
      if (inferring != null) return inferring;
    }
    final var reached = methods
      .stream()
      .filter(m -> DeepMap.mayBeInstance(boxed(held), boxed(m.getParameterTypes()[0])))
      .toList();
    return reached.size() == 1 ? reached.getFirst() : null;
  }

  /**
   * The method of {@code methods} not generic in its parameter that Java binds for an argument of
   * {@code argument}: of those it applies to without boxing or unboxing where any apply so, and of
   * those it applies to with them otherwise, the one whose parameter is a subtype of every other's;
   * null where none applies, or two apply equally.
   */
  private static Method mostSpecific(final List<Method> methods, final Class<?> argument) {
    final var applicable = new ArrayList<Method>();
    final var strict = new ArrayList<Method>();
    for (final var method : methods) {
      if (ownVariable(method.getGenericParameterTypes()[0], method)) continue;
      final var parameter = method.getParameterTypes()[0];
      if (!assignable(argument, parameter)) continue;
      applicable.add(method);
      if (argument.isPrimitive() == parameter.isPrimitive()) strict.add(method);
    }
    final var phase = strict.isEmpty() ? applicable : strict;
    if (phase.isEmpty()) return null;
    var best = phase.getFirst();
    for (final var candidate : phase) {
      if (subtype(candidate.getParameterTypes()[0], best.getParameterTypes()[0])) best = candidate;
    }
    final var bestParameter = best.getParameterTypes()[0];
    for (final var candidate : phase) {
      final var parameter = candidate.getParameterTypes()[0];
      if (parameter != bestParameter && !subtype(bestParameter, parameter)) return null;
    }
    return best;
  }

  /**
   * The one method of {@code methods} whose parameter is its own type variable with a bound that
   * takes {@code argument}; of several, the one whose bound is a subtype of every other's. Null
   * where there is none or no such one.
   */
  private static Method inferring(final List<Method> methods, final Class<?> argument) {
    final var found = new ArrayList<Method>();
    for (final var method : methods) {
      if (!ownVariable(method.getGenericParameterTypes()[0], method)) continue;
      if (!assignable(argument, method.getParameterTypes()[0])) continue;
      found.add(method);
    }
    final var specific = found
      .stream()
      .filter(m ->
        found.stream().allMatch(other -> other == m || subtype(m.getParameterTypes()[0], other.getParameterTypes()[0]))
      )
      .filter(m ->
        found.stream().noneMatch(other -> other != m && other.getParameterTypes()[0] == m.getParameterTypes()[0])
      )
      .toList();
    return specific.size() == 1 ? specific.getFirst() : null;
  }

  /** Whether {@code type} is a type variable {@code method} itself declares. */
  private static boolean ownVariable(final Type type, final Method method) {
    return type instanceof TypeVariable<?> variable && variable.getGenericDeclaration().equals(method);
  }

  /** Whether {@code type} names a type variable anywhere in it. */
  private static boolean mentionsVariable(final Type type) {
    if (type instanceof TypeVariable<?>) return true;
    if (type instanceof ParameterizedType parameterized) {
      for (final var argument : parameterized.getActualTypeArguments()) if (mentionsVariable(argument)) return true;
    }
    return false;
  }

  /**
   * Whether a value of {@code from} can be handed where {@code to} is taken, as Java's assignment
   * allows it: a reference to a supertype, a primitive widened, a primitive boxed to a class its
   * wrapper is, or a wrapper unboxed and widened to a primitive.
   */
  static boolean assignable(final Class<?> from, final Class<?> to) {
    if (from == to) return true;
    if (!from.isPrimitive() && !to.isPrimitive()) return to.isAssignableFrom(from);
    if (from.isPrimitive() && to.isPrimitive()) return WIDENS_TO.getOrDefault(from, Set.of()).contains(to);
    if (from.isPrimitive()) return to.isAssignableFrom(boxed(from));
    final var unboxed = MethodType.methodType(from).unwrap().returnType();
    return unboxed.isPrimitive() && (unboxed == to || WIDENS_TO.getOrDefault(unboxed, Set.of()).contains(to));
  }

  /** Whether {@code a} is {@code b} or a subtype of it, a primitive's by widening. */
  private static boolean subtype(final Class<?> a, final Class<?> b) {
    if (a == b) return true;
    if (a.isPrimitive() != b.isPrimitive()) return false;
    return a.isPrimitive() ? WIDENS_TO.getOrDefault(a, Set.of()).contains(b) : b.isAssignableFrom(a);
  }

  private static Class<?> boxed(final Class<?> type) {
    return MethodType.methodType(type).wrap().returnType();
  }
}
