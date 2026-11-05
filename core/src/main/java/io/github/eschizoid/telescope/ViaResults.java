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
 * types fixes nothing, and a result that still names a variable the property does not fix is left
 * to the checks each call makes.
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
   */
  record Given(Type declared, Type given, boolean generic, Type raw, String using, String method) {}

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
    if (parameter instanceof TypeVariable<?> && !parameter.equals(declared)) {
      return new Given(declared, null, true, null, using, name);
    }
    final var fixed = new ArrayList<Type>();
    final var values = new ArrayList<Type>();
    if (!fix(parameter, argument, variables, fixed, values)) return new Given(declared, null, true, null, using, name);
    final var given = DeepMap.PROPS.substitute(declared, fixed, values);
    return new Given(declared, names(given, variables) ? null : given, true, null, using, name);
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
   * Records in {@code fixed} and {@code values} what {@code argument} fixes each of {@code
   * variables} {@code parameter} names to; false where it gives one variable two types. A {@code
   * via} row's methods are static, so every type variable their signatures name is their own.
   */
  private static boolean fix(
    final Type parameter,
    final Type argument,
    final List<Type> variables,
    final List<Type> fixed,
    final List<Type> values
  ) {
    if (parameter instanceof TypeVariable<?> variable) {
      // A bound naming another of the method's variables can raise the variable above the
      // argument's class, so the argument fixes it only where its bounds name none.
      if (Arrays.stream(variable.getBounds()).anyMatch(bound -> names(bound, variables))) return true;
      return put(variable, boxed(argument), fixed, values);
    }
    if (!(parameter instanceof ParameterizedType parameterized)) return true;
    final var seen = seenAs(argument, (Class<?>) parameterized.getRawType());
    if (seen == null) return true;
    final var arguments = parameterized.getActualTypeArguments();
    for (int i = 0; i < arguments.length; i++) {
      final var met = seen.get(i);
      if (met instanceof WildcardType || DeepMap.PROPS.mentionsTypeVariable(met)) continue;
      if (arguments[i] instanceof TypeVariable<?> variable) {
        if (!put(variable, met, fixed, values)) return false;
      } else if (arguments[i] instanceof ParameterizedType && !fix(arguments[i], met, variables, fixed, values)) {
        return false;
      }
    }
    return true;
  }

  /** Fixes {@code variable} to {@code value}; false where it is fixed to another type already. */
  private static boolean put(
    final TypeVariable<?> variable,
    final Type value,
    final List<Type> fixed,
    final List<Type> values
  ) {
    final var at = fixed.indexOf(variable);
    if (at >= 0) return values.get(at).equals(value);
    fixed.add(variable);
    values.add(value);
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
