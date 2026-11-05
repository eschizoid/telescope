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
 * the member's type: each type argument it gives the member's class is that type where the member
 * gives a type there, and lies within the bound where the member gives a wildcard. A variable so
 * held, together with what the property and its own bounds force on it, has to admit some type, and
 * is refused where none can be; anything else it names is left to the checks each call makes.
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
   * fresh variable satisfies the parameter, so each variable it names is one no property names. A
   * wildcard of the parameter bounded from above by a generic class naming a variable is met as
   * that class is, by the first type what the argument's type argument in its place holds is that
   * gives the class type arguments.
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
      } else if (
        wildcard != null &&
        wildcard.getLowerBounds().length == 0 &&
        wildcard.getUpperBounds()[0] instanceof ParameterizedType bounding
      ) {
        // What the property holds there is a subtype of the generic class bounding the wildcard
        // through the type it is, or a bound of its wildcard, and gives the class the arguments
        // that one gives it, since a class has one supertype of each generic class.
        final var boundClass = (Class<?>) bounding.getRawType();
        for (final var held : heldBy(met, argument, raw, i)) {
          if (seenAs(held, boundClass) == null) continue;
          if (!fix(bounding, held, variables, forced)) return false;
          break;
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
   * class's variables read with the types {@code argument} gives them, and as its erasure, which
   * every value of it is one of, where a wildcard stands for one.
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
    // A bound naming the class's variables is read with the types the argument gives them; one a
    // wildcard stands for, the variable's own capture among them, is read as its erasure.
    final var own = new ArrayList<Type>();
    final var given = new ArrayList<Type>();
    final var arguments = DeepMap.PROPS.typeArguments(argument);
    final var parameters = cls.getTypeParameters();
    for (int i = 0; i < parameters.length; i++) {
      if (arguments.get(i) instanceof WildcardType) continue;
      own.add(parameters[i]);
      given.add(arguments.get(i));
    }
    final var held = new ArrayList<Type>(List.of(wildcard.getUpperBounds()));
    for (final var bound : variable.getBounds()) {
      final var read = DeepMap.PROPS.substitute(bound, own, given);
      held.add(DeepMap.PROPS.mentionsTypeVariable(read) ? DeepMap.heldClassOf(read) : read);
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
   * decide it. A result that is one of the variables is a subtype of {@code taken}, where that is
   * no primitive. A result of a generic class no instantiation makes a subtype of {@code taken}'s
   * class, or a supertype of it, is passed as it is, and fits under none. Seen as {@code taken}'s
   * class, each argument the result gives is contained in {@code taken}'s: where {@code taken} has
   * a type it is that type exactly, since a generic class's arguments are invariant; where it has a
   * wildcard bounded from above it is a subtype of the bound, and where it has one bounded from
   * below a supertype of it. A variable met exactly is that type, the same type at every such place
   * and wherever the argument fixes it, is no fresh variable of a capture, and is held to every
   * bound below. A variable met by no type is held to every bound below that names no type: no two
   * of the types it is a subtype of are two classes neither of which extends the other, or an array
   * and a class no array is. Each type a variable is a subtype of, a bound it declares read with
   * each variable met exactly in place among them, is a supertype of a type of each list the
   * argument forces below it, and of each type the result's shape puts below it. A bound still
   * naming a variable is left to the checks each call makes.
   */
  private static boolean instantiable(final Forced forced, final Type declared, final Type taken) {
    final var met = new Met(forced.variables());
    if (declared instanceof TypeVariable<?> variable && forced.variables().contains(variable)) {
      if (!(taken instanceof Class<?> cls && cls.isPrimitive())) met.upper(variable, taken);
    } else if (declared instanceof ParameterizedType parameterized && !below(parameterized, taken, met, true)) {
      return false;
    }
    for (int i = 0; i < forced.fixed().size(); i++) met.exact(forced.fixed().get(i), forced.values().get(i));
    for (int i = 0; i < forced.lowered().size(); i++) met.lower(forced.lowered().get(i), forced.lowers().get(i));
    for (int i = 0; i < forced.raised().size(); i++) met.upper(forced.raised().get(i), forced.uppers().get(i));
    return met.satisfiable(forced.captured());
  }

  /**
   * What the result's shape asks of the method's variables, each at the index it has among them:
   * the types each is exactly, the types each is a subtype of, and the lists of types each is a
   * supertype of at least one of.
   */
  private record Met(
    List<Type> variables,
    List<List<Type>> exact,
    List<List<Type>> uppers,
    List<List<List<Type>>> lowers
  ) {
    Met(final List<Type> variables) {
      this(variables, lists(variables.size()), lists(variables.size()), lists(variables.size()));
    }

    private static <E> List<List<E>> lists(final int size) {
      final var all = new ArrayList<List<E>>();
      for (int i = 0; i < size; i++) all.add(new ArrayList<>());
      return all;
    }

    void exact(final Type variable, final Type type) {
      exact.get(variables.indexOf(variable)).add(type);
    }

    void upper(final Type variable, final Type type) {
      uppers.get(variables.indexOf(variable)).add(type);
    }

    void lower(final Type variable, final List<Type> types) {
      lowers.get(variables.indexOf(variable)).add(types);
    }

    /** Whether an instantiation meets every bound recorded; see {@link #instantiable}. */
    boolean satisfiable(final List<Type> captured) {
      final var fixed = new ArrayList<Type>();
      final var values = new ArrayList<Type>();
      for (int i = 0; i < variables.size(); i++) {
        final var types = exact.get(i);
        if (types.isEmpty()) continue;
        if (types.stream().anyMatch(type -> !type.equals(types.getFirst()))) return false;
        if (captured.contains(variables.get(i))) return false;
        fixed.add(variables.get(i));
        values.add(types.getFirst());
      }
      for (int i = 0; i < variables.size(); i++) {
        final var variable = (TypeVariable<?>) variables.get(i);
        final var above = new ArrayList<Type>(uppers.get(i));
        for (final var bound : variable.getBounds()) {
          final var read = DeepMap.PROPS.substitute(bound, fixed, values);
          if (!names(read, variables)) above.add(read);
        }
        final var at = fixed.indexOf(variable);
        if (at >= 0) {
          final var value = values.get(at);
          if (above.stream().anyMatch(upper -> !assignable(value, upper))) return false;
          for (final var below : lowers.get(i)) {
            if (below.stream().noneMatch(lower -> assignable(lower, value))) return false;
          }
          continue;
        }
        for (int a = 0; a < above.size(); a++) {
          for (int b = a + 1; b < above.size(); b++) {
            if (exclusive(DeepMap.heldClassOf(above.get(a)), DeepMap.heldClassOf(above.get(b)))) return false;
          }
        }
        for (final var below : lowers.get(i)) {
          for (final var upper : above) {
            if (below.stream().noneMatch(lower -> assignable(lower, upper))) return false;
          }
        }
      }
      return true;
    }
  }

  /**
   * Whether no class is a subclass of both {@code a} and {@code b}, as Java reads two upper bounds
   * of one variable: two classes, not interfaces, neither of which extends the other; an array and
   * a class no array is; or two arrays whose components are so, or are two primitives.
   */
  private static boolean exclusive(final Class<?> a, final Class<?> b) {
    if (a.isArray() && b.isArray()) {
      final var ac = a.getComponentType();
      final var bc = b.getComponentType();
      return ac.isPrimitive() || bc.isPrimitive() ? ac != bc : exclusive(ac, bc);
    }
    if (a.isArray()) return !b.isAssignableFrom(a);
    if (b.isArray()) return !a.isAssignableFrom(b);
    return !a.isInterface() && !b.isInterface() && !a.isAssignableFrom(b) && !b.isAssignableFrom(a);
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
   * Records in {@code met} what {@code given}, a part of the result, asks of the method's variables
   * for it to be a subtype of {@code taken}; false where no instantiation makes it one. A generic
   * class is one where its class is a subclass of {@code taken}'s, and the arguments it gives that
   * class are contained in {@code taken}'s; one reaching {@code taken}'s class only raw, or with
   * another count of arguments, is left as it is. An array is a subtype of an array whose component
   * its component is a subtype of, a primitive one only of its own, and of the classes every array
   * extends. At the result's top, {@code top}, a class no instantiation makes a subtype is left to
   * the check of the result's class that writes it.
   */
  private static boolean below(final Type given, final Type taken, final Met met, final boolean top) {
    if (!names(given, met.variables())) return true;
    if (given instanceof TypeVariable<?>) {
      met.upper(given, taken);
      return true;
    }
    if (given instanceof GenericArrayType array) {
      final var takenClass = DeepMap.heldClassOf(taken);
      if (!takenClass.isArray()) return takenClass.isAssignableFrom(Object[].class);
      final var component = componentOf(taken);
      return (
        !takenClass.getComponentType().isPrimitive() && below(array.getGenericComponentType(), component, met, false)
      );
    }
    if (!(given instanceof ParameterizedType parameterized)) return true;
    final var cls = (Class<?>) parameterized.getRawType();
    final var takenClass = DeepMap.heldClassOf(taken);
    if (!takenClass.isAssignableFrom(cls)) return top;
    final var seen = resultArguments(parameterized, cls, takenClass);
    final var takenArguments = DeepMap.PROPS.typeArguments(taken);
    if (seen.size() != takenArguments.size()) return true;
    for (int i = 0; i < seen.size(); i++) {
      if (!contained(seen.get(i), takenArguments.get(i), met)) return false;
    }
    return true;
  }

  /**
   * Records in {@code met} what {@code given}, a part of the result, asks of the method's variables
   * for it to be a supertype of {@code taken}; false where no instantiation makes it one. A generic
   * class is one where {@code taken}'s class is a subclass of its own, and each argument {@code
   * taken} gives that class is the type it gives in its place, or within the wildcard it gives.
   */
  private static boolean above(final Type given, final Type taken, final Met met) {
    if (!names(given, met.variables())) return true;
    if (given instanceof TypeVariable<?>) {
      met.lower(given, List.of(taken));
      return true;
    }
    if (given instanceof GenericArrayType array) {
      final var takenClass = DeepMap.heldClassOf(taken);
      if (!takenClass.isArray() || takenClass.getComponentType().isPrimitive()) return false;
      return above(array.getGenericComponentType(), componentOf(taken), met);
    }
    if (!(given instanceof ParameterizedType parameterized)) return true;
    final var cls = (Class<?>) parameterized.getRawType();
    if (!cls.isAssignableFrom(DeepMap.heldClassOf(taken))) return false;
    final var seen = seenAs(taken, cls);
    if (seen == null) return true;
    final var arguments = parameterized.getActualTypeArguments();
    for (int i = 0; i < arguments.length; i++) {
      final var argument = arguments[i];
      final var held = seen.get(i);
      if (!(argument instanceof WildcardType wildcard)) {
        if (!exactly(argument, held, met)) return false;
      } else if (!(held instanceof WildcardType)) {
        final var fits =
          wildcard.getLowerBounds().length > 0
            ? below(wildcard.getLowerBounds()[0], held, met, false)
            : above(wildcard.getUpperBounds()[0], held, met);
        if (!fits) return false;
      }
    }
    return true;
  }

  /**
   * Records in {@code met} what {@code given}, a type argument of the result, asks of the method's
   * variables for it to be contained in {@code taken}, the one in its place in the type the result
   * is passed as: a type contains that type alone, a wildcard bounded from above the arguments
   * below its bound, and one bounded from below those above it. A wildcard bounded from below is
   * contained in one bounded from above only by {@code Object}, and one bounded from above in none
   * bounded from below.
   */
  private static boolean contained(final Type given, final Type taken, final Met met) {
    if (!(taken instanceof WildcardType wildcard)) return exactly(given, taken, met);
    if (!names(given, met.variables())) return true;
    final var lower = wildcard.getLowerBounds();
    if (given instanceof WildcardType inResult) {
      final var resultLower = inResult.getLowerBounds();
      if (lower.length > 0) return resultLower.length > 0 && above(resultLower[0], lower[0], met);
      if (resultLower.length > 0) return wildcard.getUpperBounds()[0] == Object.class;
      return below(inResult.getUpperBounds()[0], wildcard.getUpperBounds()[0], met, false);
    }
    return lower.length > 0 ? above(given, lower[0], met) : below(given, wildcard.getUpperBounds()[0], met, false);
  }

  /**
   * Records in {@code met} what {@code given}, a part of the result, asks of the method's variables
   * for it to be {@code taken} exactly; false where no instantiation makes it so. A variable is
   * never a wildcard, and a wildcard is another only where both are bounded the same way, by the
   * same type.
   */
  private static boolean exactly(final Type given, final Type taken, final Met met) {
    if (!names(given, met.variables())) return true;
    if (given instanceof TypeVariable<?>) {
      if (taken instanceof WildcardType) return false;
      met.exact(given, taken);
      return true;
    }
    if (given instanceof WildcardType inResult) {
      if (!(taken instanceof WildcardType wildcard)) return false;
      final var resultLower = inResult.getLowerBounds();
      final var lower = wildcard.getLowerBounds();
      if (resultLower.length != lower.length) return false;
      return lower.length > 0
        ? exactly(resultLower[0], lower[0], met)
        : exactly(inResult.getUpperBounds()[0], wildcard.getUpperBounds()[0], met);
    }
    if (given instanceof GenericArrayType array) {
      if (taken instanceof WildcardType || !DeepMap.heldClassOf(taken).isArray()) return false;
      if (DeepMap.heldClassOf(taken).getComponentType().isPrimitive()) return false;
      return exactly(array.getGenericComponentType(), componentOf(taken), met);
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
      if (!exactly(givenArguments[i], takenArguments[i], met)) return false;
    }
    return true;
  }

  /** The component of {@code array}, an array type naming no variable. */
  private static Type componentOf(final Type array) {
    return array instanceof GenericArrayType generic
      ? generic.getGenericComponentType()
      : ((Class<?>) array).getComponentType();
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
