package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.io.Serializable;
import java.lang.invoke.MethodType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * What a {@code via} row's method returns where it converts the whole property, and how the runtime
 * path writes it into the member the property's type chooses, read once, while it builds a mapper,
 * from the method's generic signature.
 *
 * <p>A result is written as Java's assignment converts it: a wrapper into its own primitive or into
 * one its primitive widens to, widened there; a primitive into its own wrapper or a class that
 * wrapper is; any other reference as it is, after a check of its class where the member takes a
 * narrower one. A result no value of which the member takes, through any assignment conversion or a
 * check of its class, is refused by name, as the generated bridge refuses the same row.
 *
 * <p>A generic method's own type variables are read as the property handed to it fixes them: a
 * variable that is the parameter's whole type is the property's class where the result is that
 * variable, and one that is a type argument of the parameter is the property's type argument in its
 * place, where that is a type and not a wildcard, since a generic class's arguments are invariant.
 * A property of a generic class used raw, or of a class extending it raw, handed to a parameter
 * that gives the class type arguments, reaches it through an unchecked conversion, and the call's
 * result is then the erasure of the one the method declares. A property a class's own variable
 * types fixes nothing. A result that still names a variable the property does not fix is held to
 * the member's type where its type arguments are invariant: a variable in an argument the member
 * gives a type has to be that type, which has to admit the property, and is refused where none can
 * be; anything else it names is left to the checks each call makes.
 */
final class ViaResults {

  private ViaResults() {}

  /**
   * What a {@code via} row's method returns.
   *
   * @param declared the result as the method declares it
   * @param given the result with every type variable of the method's own the argument fixes in
   *     place, or null where one is left for the call to infer
   * @param generic whether {@code declared} names a type variable the method declares
   * @param raw the generic class used raw the method is handed where the call's result is erased,
   *     or null
   * @param using the class the row names, by its name
   * @param method the method's name
   * @param forced what the argument forces on the variables {@code given} leaves for the call to
   *     infer, or null where it is not read
   */
  record Given(Type declared, Type given, boolean generic, Type raw, String using, String method, Forced forced) {
    Given(
      final Type declared,
      final Type given,
      final boolean generic,
      final Type raw,
      final String using,
      final String method
    ) {
      this(declared, given, generic, raw, using, method, null);
    }
  }

  /**
   * What the argument a generic method is handed forces on the method's own type variables: the
   * type each variable it fixes is, in {@code fixed} and {@code values}; for each variable in
   * {@code lowered}, the types in the matching list of {@code lowers}, of which the variable has to
   * be a supertype of at least one; for each variable in {@code raised}, the matching type of
   * {@code uppers}, which the variable has to be a subtype of; and the variables in {@code
   * captured}, which only the fresh variable of a wildcard's capture satisfies, and which is no
   * type a row's property names.
   *
   * @param variables the method's own type variables
   */
  record Forced(
    List<Type> variables,
    List<Type> fixed,
    List<Type> values,
    List<Type> lowered,
    List<List<Type>> lowers,
    List<Type> raised,
    List<Type> uppers,
    List<Type> captured
  ) {
    Forced(final List<Type> variables) {
      this(
        variables,
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>()
      );
    }

    /** Records that {@code variable} is a supertype of at least one of {@code types}. */
    void lower(final TypeVariable<?> variable, final List<Type> types) {
      lowered.add(variable);
      lowers.add(types);
    }

    /** Records that {@code variable} is a subtype of {@code type}. */
    void raise(final TypeVariable<?> variable, final Type type) {
      raised.add(variable);
      uppers.add(type);
    }
  }

  /**
   * What {@code method} returns when handed a value of {@code argument}, called by a row naming
   * {@code using}.
   */
  static Given of(final Method method, final Type argument, final String using) {
    final var declared = method.getGenericReturnType();
    final var name = method.getName();
    final var variables = List.<Type>of(method.getTypeParameters());
    if (variables.isEmpty() || !names(declared, variables)) {
      return new Given(declared, declared, false, null, using, name);
    }
    final var parameter = method.getGenericParameterTypes()[0];
    // A property a class's own variable types fixes none of the method's variables.
    if (DeepMap.PROPS.mentionsTypeVariable(argument)) return new Given(declared, null, true, null, using, name);
    // An argument whose class gives the parameter's class no type arguments, used raw or extending
    // it raw, reaches a parameter that gives the class type arguments only through an unchecked
    // conversion, which erases the call's result.
    if (
      parameter instanceof ParameterizedType parameterized &&
      seenAs(argument, (Class<?>) parameterized.getRawType()) == null
    ) {
      return new Given(declared, method.getReturnType(), true, argument, using, name);
    }
    // A variable that is the parameter's whole type is only a supertype of the argument's class,
    // which the type the result is written as can raise where the result gives the variable as a
    // type argument; the argument is its class only where the result is the variable itself.
    final var forced = new Forced(variables);
    if (parameter instanceof TypeVariable<?> variable && !parameter.equals(declared)) {
      forced.lower(variable, List.of(boxed(argument)));
      return new Given(declared, null, true, null, using, name, forced);
    }
    if (!fix(parameter, argument, variables, forced)) return new Given(declared, null, true, null, using, name);
    final var given = DeepMap.PROPS.substitute(declared, forced.fixed(), forced.values());
    return new Given(declared, names(given, variables) ? null : given, true, null, using, name, forced);
  }

  /**
   * What {@code reference}, a method reference converting one value, returns: the class its method
   * returns, or nothing a call does not infer where the method returns its own type variable.
   */
  static Given ofReference(final Serializable reference) {
    final var conversion = LambdaIntrospection.conversionOf(reference);
    final var result = conversion.to();
    return new Given(
      result,
      conversion.resultInferred() ? null : result,
      conversion.resultInferred(),
      null,
      LambdaIntrospection.implClassOf(reference).getName(),
      LambdaIntrospection.methodNameOf(reference)
    );
  }

  /**
   * Records in {@code forced} what {@code argument} forces on each of {@code variables} {@code
   * parameter} names; false where it gives one variable two types. A {@code via} row's methods are
   * static, so every type variable their signatures name is their own, and {@code argument} names
   * none.
   *
   * <p>A variable that is a type argument of the parameter is the argument's type argument in its
   * place where that is a type. One that bounds a wildcard of the parameter from above is a
   * supertype of what the argument's type argument in its place holds: the type itself, or for a
   * wildcard its capture, a subtype of the wildcard's upper bound and of the bound the class
   * declares for that argument. One that bounds a wildcard of the parameter from below is a subtype
   * of the argument's type argument in its place, or of the lower bound of a wildcard there. Where
   * the argument has a wildcard and the parameter a type naming a variable, or a wildcard bounded
   * from below by one, which a wildcard bounded from below alone satisfies, only the capture's
   * fresh variable satisfies the parameter, so each variable it names is one no property names.
   */
  private static boolean fix(
    final Type parameter,
    final Type argument,
    final List<Type> variables,
    final Forced forced
  ) {
    if (parameter instanceof TypeVariable<?> variable) {
      // A bound naming another of the method's variables can raise the variable above the
      // argument's class, so the argument fixes it only where its bounds name none.
      if (Arrays.stream(variable.getBounds()).anyMatch(bound -> names(bound, variables))) return true;
      return put(variable, boxed(argument), forced);
    }
    if (!(parameter instanceof ParameterizedType parameterized)) return true;
    final var raw = (Class<?>) parameterized.getRawType();
    final var seen = seenAs(argument, raw);
    if (seen == null) return true;
    final var arguments = parameterized.getActualTypeArguments();
    for (int i = 0; i < arguments.length; i++) {
      final var met = seen.get(i);
      final var wildcard = arguments[i] instanceof WildcardType bounded ? bounded : null;
      if (wildcard != null && bound(wildcard) instanceof TypeVariable<?> variable) {
        if (wildcard.getLowerBounds().length == 0) {
          forced.lower(variable, heldBy(met, argument, raw, i));
        } else if (!(met instanceof WildcardType metWildcard)) {
          forced.raise(variable, met);
        } else if (metWildcard.getLowerBounds().length > 0) {
          forced.raise(variable, metWildcard.getLowerBounds()[0]);
        } else {
          forced.captured().add(variable);
        }
      } else if (met instanceof WildcardType) {
        if (wildcard == null) {
          for (final var variable : variables) {
            if (names(arguments[i], List.of(variable))) forced.captured().add(variable);
          }
        }
      } else if (arguments[i] instanceof TypeVariable<?> variable) {
        if (!put(variable, met, forced)) return false;
      } else if (arguments[i] instanceof ParameterizedType && !fix(arguments[i], met, variables, forced)) {
        return false;
      }
    }
    return true;
  }

  /**
   * The type that bounds {@code wildcard}: its lower bound where it has one, or its upper bound.
   */
  private static Type bound(final WildcardType wildcard) {
    final var lower = wildcard.getLowerBounds();
    return lower.length > 0 ? lower[0] : wildcard.getUpperBounds()[0];
  }

  /**
   * The types a value the type argument {@code met} holds is a value of, any of which it can be the
   * whole of, where {@code met} is the {@code index}th argument {@code argument} gives {@code raw}:
   * {@code met} itself where it is a type, and where it is a wildcard its upper bound and the
   * bounds of the variable of {@code argument}'s own class it stands in for, a bound naming the
   * class's variables read as its erasure, which every value of it is one of.
   *
   * <p>A wildcard reaches {@code raw}'s arguments only as an argument of {@code argument}'s own
   * class, which gives {@code raw} that class's variable in its place: a class declaration gives
   * its supertypes no wildcard.
   */
  private static List<Type> heldBy(final Type met, final Type argument, final Class<?> raw, final int index) {
    if (!(met instanceof WildcardType wildcard)) return List.of(met);
    final var cls = (Class<?>) DeepMap.PROPS.rawType(argument);
    final var variable = (TypeVariable<?>) (cls == raw
      ? raw.getTypeParameters()[index]
      : DeepMap.PROPS.declaredArgumentsAs(cls, raw).get(index));
    final var held = new ArrayList<Type>(List.of(wildcard.getUpperBounds()));
    for (final var bound : variable.getBounds()) {
      held.add(DeepMap.PROPS.mentionsTypeVariable(bound) ? DeepMap.PROPS.rawType(bound) : bound);
    }
    return held;
  }

  /** Fixes {@code variable} to {@code value}; false where it is fixed to another type already. */
  private static boolean put(final TypeVariable<?> variable, final Type value, final Forced forced) {
    final var at = forced.fixed().indexOf(variable);
    if (at >= 0) return forced.values().get(at).equals(value);
    forced.fixed().add(variable);
    forced.values().add(value);
    return true;
  }

  /**
   * Whether an instantiation of the method's variables lets the result {@code declared}, which
   * still names one of them, be passed where {@code taken} is taken, as far as its type arguments
   * decide it. A result of a generic class no instantiation makes a subtype of {@code taken}'s
   * class, or a supertype of it, is passed as it is, and fits under none. Seen as {@code taken}'s
   * class, each argument the result gives where {@code taken} has a type, not a wildcard, has to be
   * that type exactly, since a generic class's arguments are invariant: a variable there is that
   * type, the same type at every such place and wherever the argument fixes it, and a generic class
   * there is that type's class. A variable so fixed is no fresh variable of a capture, is a
   * supertype of a type of each list the argument forces on it and a subtype of each type it forces
   * above it, and is a subtype of each of its bounds that names no other variable, read with it in
   * place. A variable nothing fixes is left to the checks each call makes.
   */
  private static boolean instantiable(final Forced forced, final Type declared, final Type taken) {
    final var exact = new ArrayList<Type>(forced.fixed());
    final var as = new ArrayList<Type>(forced.values());
    if (declared instanceof ParameterizedType parameterized) {
      final var cls = (Class<?>) parameterized.getRawType();
      final var takenClass = DeepMap.heldClassOf(taken);
      final var seen = resultArguments(parameterized, cls, takenClass);
      final var takenArguments = DeepMap.PROPS.typeArguments(taken);
      if (seen.size() == takenArguments.size()) {
        for (int i = 0; i < seen.size(); i++) {
          if (!meet(seen.get(i), takenArguments.get(i), exact, as)) return false;
        }
      }
    }
    for (int i = 0; i < exact.size(); i++) {
      final var variable = (TypeVariable<?>) exact.get(i);
      final var value = as.get(i);
      final var first = exact.indexOf(variable);
      if (first < i) {
        if (!as.get(first).equals(value)) return false;
        continue;
      }
      if (forced.captured().contains(variable)) return false;
      for (int l = 0; l < forced.lowered().size(); l++) {
        if (!forced.lowered().get(l).equals(variable)) continue;
        if (
          forced
            .lowers()
            .get(l)
            .stream()
            .noneMatch(lower -> assignable(lower, value))
        ) return false;
      }
      for (int u = 0; u < forced.raised().size(); u++) {
        if (forced.raised().get(u).equals(variable) && !assignable(value, forced.uppers().get(u))) return false;
      }
      final var others = forced
        .variables()
        .stream()
        .filter(other -> !other.equals(variable))
        .toList();
      for (final var bound : variable.getBounds()) {
        if (names(bound, others)) continue;
        if (!assignable(value, DeepMap.PROPS.substitute(bound, List.of(variable), List.of(value)))) return false;
      }
    }
    return true;
  }

  /**
   * The type arguments the result {@code declared}, of {@code cls}, which may name the method's
   * variables, gives {@code takenClass}, which {@code cls} is or extends.
   */
  private static List<Type> resultArguments(
    final ParameterizedType declared,
    final Class<?> cls,
    final Class<?> takenClass
  ) {
    final var props = DeepMap.PROPS;
    final var given = props.typeArguments(declared);
    if (cls == takenClass) return given;
    final var own = List.<Type>of(cls.getTypeParameters());
    return props
      .declaredArgumentsAs(cls, takenClass)
      .stream()
      .map(argument -> props.substitute(argument, own, given))
      .toList();
  }

  /**
   * Records in {@code exact} and {@code as} each of the method's variables the result's type
   * argument {@code given} names where it has to be {@code taken} exactly; false where {@code
   * given} is a generic class's parameterization and {@code taken} no parameterization of that
   * class.
   */
  private static boolean meet(final Type given, final Type taken, final List<Type> exact, final List<Type> as) {
    if (taken instanceof WildcardType) return true;
    if (given instanceof TypeVariable<?> variable) {
      exact.add(variable);
      as.add(taken);
      return true;
    }
    if (!(given instanceof ParameterizedType parameterized)) return true;
    if (
      !(taken instanceof ParameterizedType takenParameterized) ||
      !parameterized.getRawType().equals(takenParameterized.getRawType())
    ) {
      return false;
    }
    final var givenArguments = parameterized.getActualTypeArguments();
    final var takenArguments = takenParameterized.getActualTypeArguments();
    for (int i = 0; i < givenArguments.length; i++) {
      if (!meet(givenArguments[i], takenArguments[i], exact, as)) return false;
    }
    return true;
  }

  /**
   * The type arguments {@code type}, a type naming no variable, gives the class {@code raw}, which
   * it is or extends, or null where it gives none: it is used raw, it extends {@code raw} raw, or
   * {@code raw} is not one of its classes.
   */
  private static List<Type> seenAs(final Type type, final Class<?> raw) {
    final var props = DeepMap.PROPS;
    final var cls = (Class<?>) props.rawType(type);
    if (!raw.isAssignableFrom(cls)) return null;
    if (cls == raw) return type instanceof ParameterizedType parameterized
      ? List.of(parameterized.getActualTypeArguments())
      : null;
    if (!(type instanceof ParameterizedType) && cls.getTypeParameters().length > 0) return null;
    final var own = List.<Type>of(cls.getTypeParameters());
    final var given = props.typeArguments(type);
    final var seen = props
      .declaredArgumentsAs(cls, raw)
      .stream()
      .map(argument -> props.substitute(argument, own, given))
      .toList();
    // A class that extends {@code raw} raw gives it no arguments, or leaves its own variables in
    // their place.
    return seen.isEmpty() || seen.stream().anyMatch(props::mentionsTypeVariable) ? null : seen;
  }

  /** Whether {@code type} names any of {@code variables}. */
  private static boolean names(final Type type, final List<Type> variables) {
    return switch (type) {
      case TypeVariable<?> variable -> variables.contains(variable);
      case ParameterizedType parameterized -> Arrays.stream(parameterized.getActualTypeArguments()).anyMatch(argument ->
        names(argument, variables)
      ) ||
      (parameterized.getOwnerType() != null && names(parameterized.getOwnerType(), variables));
      case WildcardType wildcard -> Arrays.stream(wildcard.getUpperBounds()).anyMatch(bound ->
        names(bound, variables)
      ) ||
      Arrays.stream(wildcard.getLowerBounds()).anyMatch(bound -> names(bound, variables));
      case GenericArrayType array -> names(array.getGenericComponentType(), variables);
      default -> false;
    };
  }

  /**
   * Where a value {@code given} describes is written into {@code property} of {@code owner} through
   * a member taking {@code member}, declared as {@code memberType}: the conversion that widens it
   * to the member's primitive, or null where it is written as it is.
   *
   * @throws IllegalArgumentException naming the row where no value of the result is one the member
   *     takes
   */
  static Function<Object, Object> written(
    final Given given,
    final Class<?> member,
    final Type memberType,
    final String source,
    final String target,
    final String field,
    final String side,
    final Class<?> owner,
    final String property
  ) {
    if (given == null || member == null || DeepMap.PROPS.mentionsTypeVariable(memberType)) return null;
    final var ownerName = owner.getSimpleName();
    // An erased result the member takes is written as any other result of its class is.
    if (given.raw() != null && !assignable(given.given(), memberType)) {
      throw refused(
        given,
        source,
        target,
        field,
        PairingMessages.viaResultErased(
          given.declared().getTypeName(),
          given.raw().getTypeName(),
          given.given().getTypeName(),
          ownerName,
          property,
          memberType.getTypeName()
        )
      );
    }
    final var result = given.given();
    // Every parameterization of a generic class is a subtype of a class only where the generic
    // class is, so a result of one that is not fits under no instantiation of its variables.
    final var unrelated =
      given.generic() &&
      given.raw() == null &&
      given.declared() instanceof ParameterizedType declared &&
      !DeepMap.heldClassOf(memberType).isAssignableFrom((Class<?>) declared.getRawType());
    if (
      unrelated ||
      (result == null && given.forced() != null && !instantiable(given.forced(), given.declared(), memberType))
    ) {
      throw refused(
        given,
        source,
        target,
        field,
        PairingMessages.viaResultNoInstantiation(
          given.declared().getTypeName(),
          ownerName,
          property,
          memberType.getTypeName()
        )
      );
    }
    if (result == null) return null;
    final var resultClass = boxed(DeepMap.heldClassOf(result));
    final var unboxed = MethodType.methodType(resultClass).unwrap().returnType();
    if (member.isPrimitive() && unboxed.isPrimitive()) {
      if (unboxed == member) return null;
      if (ViaClasses.assignable(unboxed, member)) return value -> widened(value, member);
    } else if (DeepMap.mayBeInstance(resultClass, boxed(member))) {
      if (member.isPrimitive() || rawUse(result) || DeepMap.PAIRING.argumentsFit(result, memberType)) return null;
      throw refused(
        given,
        source,
        target,
        field,
        given.generic()
          ? PairingMessages.viaResultNoInstantiation(
              given.declared().getTypeName(),
              ownerName,
              property,
              memberType.getTypeName()
            )
          : PairingMessages.viaResultNotTaken(result.getTypeName(), ownerName, property, memberType.getTypeName())
      );
    }
    if (given.generic()) {
      throw refused(
        given,
        source,
        target,
        field,
        PairingMessages.viaResultNoInstantiation(
          given.declared().getTypeName(),
          ownerName,
          property,
          memberType.getTypeName()
        )
      );
    }
    throw new IllegalArgumentException(
      PairingMessages.viaResultNeverFits(
        source,
        target,
        side,
        property,
        resultClass.getSimpleName(),
        boxed(member).getSimpleName()
      )
    );
  }

  /** The refusal of a row whose method {@code given} describes, finished by {@code why}. */
  private static IllegalArgumentException refused(
    final Given given,
    final String source,
    final String target,
    final String field,
    final String why
  ) {
    return new IllegalArgumentException(
      PairingMessages.viaClassRefused(source, target, field, given.using(), given.method(), why)
    );
  }

  /**
   * Whether a value of {@code from}, a type naming no variable, is one Java's assignment hands
   * where {@code to} is taken, an unchecked conversion of a generic class used raw included.
   */
  private static boolean assignable(final Type from, final Type to) {
    final var fromClass = DeepMap.heldClassOf(from);
    final var toClass = DeepMap.heldClassOf(to);
    if (!ViaClasses.assignable(fromClass, toClass)) return false;
    return toClass.isPrimitive() || rawUse(from) || DeepMap.PAIRING.argumentsFit(from, to);
  }

  /** Whether {@code type} is a generic class used raw. */
  private static boolean rawUse(final Type type) {
    return type instanceof Class<?> cls && cls.getTypeParameters().length > 0;
  }

  /** {@code value}, a wrapper of a primitive that widens to {@code primitive}, widened to it. */
  private static Object widened(final Object value, final Class<?> primitive) {
    if (value == null) return null;
    final Number number = value instanceof Character character ? (int) character.charValue() : (Number) value;
    if (primitive == short.class) return number.shortValue();
    if (primitive == int.class) return number.intValue();
    if (primitive == long.class) return number.longValue();
    if (primitive == float.class) return number.floatValue();
    return number.doubleValue();
  }

  private static Type boxed(final Type type) {
    return type instanceof Class<?> cls ? boxed(cls) : type;
  }

  private static Class<?> boxed(final Class<?> type) {
    return MethodType.methodType(type).wrap().returnType();
  }
}
