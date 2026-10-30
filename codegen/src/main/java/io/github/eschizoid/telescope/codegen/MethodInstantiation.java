package io.github.eschizoid.telescope.codegen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;

/**
 * The instantiation of a generic method's own type variables at a call whose result is passed where
 * one type is taken, as far as it is forced. This is not inference: it gathers only what the
 * result's shape and the variables' bounds force on each variable, and decides only what those
 * facts decide.
 *
 * <ul>
 *   <li>A variable that is a type argument of the result where the taken type has a type, not a
 *       wildcard, is that type exactly, since a generic class's arguments are invariant. Where the
 *       taken type has a wildcard, the variable is bounded by it, from above or below. A variable
 *       that bounds a wildcard of the result where the taken type has a wildcard bounded the same
 *       way is bounded by that wildcard's bound, the same way; a wildcard bounded from below is
 *       contained in one bounded from above only by {@code Object}. Inside a type argument met
 *       exactly, a wildcard is met by the same wildcard, so the variable bounding it is that
 *       wildcard's bound exactly.
 *   <li>An inner class's enclosing type is met as the inner class is, so the variables of {@code
 *       Outer<T>.Inner} are fixed by the {@code Outer} the taken type encloses its class in.
 *   <li>A variable's bound and a type the variable has to be a subtype of, or a supertype of, that
 *       reach one generic class give it the same type for each argument both give a type, since a
 *       class has one supertype of each generic class.
 *   <li>A variable that is the method's one parameter is a supertype of the argument's type. It is
 *       the argument's type only where no bound names it, since a bound naming it can raise it. A
 *       parameter that names a variable inside it is a supertype of the argument's type, read as
 *       the result is read against the taken type, so {@code List<? extends T>} handed a {@code
 *       List<String>} makes {@code T} a supertype of {@code String}.
 *   <li>A variable bounded by a final class, or that has to be a subtype of one, is that class, the
 *       only class whose instances it can hold.
 * </ul>
 *
 * <p>A variable fixed so has to satisfy every bound it declares, read with the fixed variables in
 * place, and every bound a wildcard puts on it. A variable nothing fixes is left to javac, which
 * rejects the call, as this does, where two of the types it has to be a subtype of are two classes
 * neither of which extends the other, an array type and a class every array does not extend, or two
 * types that reach one generic class with different types for its arguments, or where a type it has
 * to be a supertype of is no subtype of one of them. Where a bound and a type the variable has to
 * be a subtype of give one generic class a wildcard and a type for an argument, javac draws no
 * bound from them, and the call fits only where the wildcard contains the type once the variables
 * have the instantiations javac gives them. A result whose class is no subtype of the taken type's,
 * and which still names a variable, is passed as it is and fits under no instantiation.
 */
final class MethodInstantiation {

  private final Types types;
  private final MirrorProps props;

  MethodInstantiation(final Types types, final MirrorProps props) {
    this.types = types;
    this.props = props;
  }

  /**
   * {@code member}'s result type with each type variable the method declares that is its one
   * parameter's whole type read as {@code argument}, the value handed to it, boxed where it is
   * primitive.
   */
  TypeMirror fromArgument(final ExecutableType member, final TypeMirror argument) {
    final var variables = new ArrayList<TypeMirror>();
    final var replacements = new ArrayList<TypeMirror>();
    final var parameter = parameterVariable(member);
    if (parameter >= 0) {
      variables.add(member.getTypeVariables().get(parameter));
      replacements.add(boxed(argument));
    }
    return variables.isEmpty()
      ? member.getReturnType()
      : props.substitute(member.getReturnType(), variables, replacements);
  }

  /**
   * {@code member}'s result type at a call handed {@code argument} whose result is passed where
   * {@code taken} is taken, with every type variable the method declares that is fixed replaced by
   * its instantiation; null where no instantiation of the variables within their bounds lets the
   * result be passed there. A result that still names a variable is left to javac's inference.
   * {@code taken} names no type variable.
   */
  TypeMirror result(final ExecutableType member, final TypeMirror argument, final TypeMirror taken) {
    final var declared = member.getReturnType();
    final var variables = List.copyOf(member.getTypeVariables());
    if (variables.isEmpty()) return declared;
    final var facts = new ArrayList<Facts>();
    for (int i = 0; i < variables.size(); i++) facts.add(new Facts());
    final var gathered = new Gathered(variables, facts);
    if (!gather(declared, taken, Relation.TOP, gathered)) return null;
    final var parameter = parameterVariable(member);
    if (parameter >= 0) {
      facts.get(parameter).lower.add(boxed(argument));
    } else if (!argumentMeetsParameter(member, argument, gathered)) {
      return null;
    }
    if (!sharedSupertypes(gathered)) return null;
    final var solved = new TypeMirror[variables.size()];
    for (int i = 0; i < variables.size(); i++) {
      final var fact = facts.get(i);
      if (!fact.exact.isEmpty()) {
        final var exact = fact.exact.getFirst();
        if (fact.exact.stream().anyMatch(t -> !types.isSameType(t, exact))) return null;
        solved[i] = exact;
        continue;
      }
      solved[i] = finalClass(boundsOf(variables.get(i)));
      // An argument is a lower bound only: a bound naming the variable can raise it above the
      // argument's type, so the argument is its instantiation only where no bound names it. A
      // result fixed so is then checked against the taken type as a whole, which a check of its
      // class may still reach.
      if (solved[i] == null && parameter == i && !namedByABound(variables.get(i), variables)) {
        solved[i] = boxed(argument);
      }
      if (solved[i] == null) solved[i] = finalClass(fact.uppers());
    }
    final var fixed = new ArrayList<TypeMirror>();
    final var values = new ArrayList<TypeMirror>();
    for (int i = 0; i < variables.size(); i++) {
      if (solved[i] == null) continue;
      fixed.add(variables.get(i));
      values.add(solved[i]);
    }
    for (int i = 0; i < variables.size(); i++) {
      final var fact = facts.get(i);
      final var bounds = new ArrayList<TypeMirror>();
      for (final var bound : boundsOf(variables.get(i))) {
        final var read = props.substitute(bound, fixed, values);
        if (!props.mentionsTypeVariable(read)) bounds.add(read);
      }
      if (solved[i] != null) {
        final var value = solved[i];
        // An argument reaches its parameter through any conversion an invocation allows, a raw
        // class's unchecked one among them; a type argument has to be a subtype of each bound.
        final var argued = fact.exact.isEmpty() && parameter == i;
        if (
          bounds.stream().anyMatch(bound -> argued ? !types.isAssignable(value, bound) : !types.isSubtype(value, bound))
        ) {
          return null;
        }
        if (fact.upper.stream().anyMatch(upper -> !types.isAssignable(value, upper))) return null;
        if (fact.lower.stream().anyMatch(lower -> !types.isAssignable(lower, value))) return null;
        // A variable fixed to the fresh variable an argument's capture gives leaves the result to
        // javac, which writes it as it is: no check of its class reaches the taken type, so the
        // result as a whole has to fit it.
        if (props.mentionsTypeVariable(value) && fact.top.stream().anyMatch(top -> !types.isAssignable(value, top))) {
          return null;
        }
        continue;
      }
      final var uppers = new ArrayList<TypeMirror>(bounds);
      uppers.addAll(fact.uppers());
      for (int a = 0; a < uppers.size(); a++) {
        for (int b = a + 1; b < uppers.size(); b++) {
          if (exclusive(uppers.get(a), uppers.get(b))) return null;
        }
      }
      for (final var lower : fact.lower) {
        if (uppers.stream().anyMatch(upper -> !types.isAssignable(lower, upper))) return null;
      }
    }
    // javac gives every variable its instantiation before it reads a pair it drew no bound from.
    final var instantiated = new ArrayList<TypeMirror>(fixed);
    final var instances = new ArrayList<TypeMirror>(values);
    for (int i = 0; i < variables.size(); i++) {
      if (solved[i] != null) continue;
      final var instance = javacInstantiation(variables.get(i), facts.get(i));
      if (instance == null) continue;
      instantiated.add(variables.get(i));
      instances.add(instance);
    }
    for (final var pair : gathered.pending) {
      if (!contains(pair, instantiated, instances)) return null;
    }
    final var result = props.substitute(declared, fixed, values);
    if (gathered.topUnrelated && props.mentionsTypeVariable(result)) return null;
    return result;
  }

  /**
   * Records what {@code argument}, the value handed to {@code member}'s one parameter, asks of the
   * variables the parameter names where it is not a variable on its own: the parameter is a
   * supertype of the argument's type. The argument's type is read after capture conversion, as
   * javac reads it, so a wildcard argument stands for a fresh type variable: a variable the
   * parameter gives in its place is that fresh variable exactly, and a result naming it is left to
   * javac. A value whose class is no subtype of the parameter's erasure is handed over after a cast
   * to that erasure, which gives the variables nothing. False where the argument shows no
   * instantiation lets the parameter take it.
   */
  private boolean argumentMeetsParameter(final ExecutableType member, final TypeMirror argument, final Gathered into) {
    if (argument == null || member.getParameterTypes().size() != 1) return true;
    final var parameter = member.getParameterTypes().getFirst();
    if (!names(parameter, into.variables)) return true;
    final var handed = types.capture(boxed(argument));
    if (!(handed instanceof DeclaredType) && !(handed instanceof ArrayType)) return true;
    if (!types.isSubtype(types.erasure(handed), types.erasure(parameter))) return true;
    return gather(parameter, handed, Relation.SUPERTYPE, into);
  }

  /**
   * Records what a variable's bound and a type the variable has to be a subtype of ask of the
   * variables the bound names, where both reach one generic class or interface: a class has one
   * supertype of each, so where both give it a type for an argument, the two are the same type, as
   * javac reads them. False where they cannot be. Where either gives a wildcard, javac draws no
   * bound from the pair, so the pair is kept to be checked once each variable has the instantiation
   * javac gives it.
   *
   * <p>A type the variable has to be a supertype of is a subtype of each of its bounds: it reaches
   * each bound's class, and each argument its supertype of that class gives is contained in the
   * bound's argument there. A bound's type argument is that type exactly, and a bound's wildcard
   * bounds the variables it names, as {@code ? super T} containing {@code Integer} makes {@code T}
   * a subtype of {@code Integer}. So two such types that give the bound's class different types for
   * one argument admit no instantiation, whatever single type javac would join them into.
   */
  private boolean sharedSupertypes(final Gathered into) {
    for (int i = 0; i < into.variables.size(); i++) {
      final var uppers = List.copyOf(into.facts.get(i).uppers());
      final var lowers = List.copyOf(into.facts.get(i).lower);
      for (final var bound : boundsOf(into.variables.get(i))) {
        if (!(bound instanceof DeclaredType declared) || !names(bound, into.variables)) continue;
        for (final var lower : lowers) {
          if (!(lower instanceof DeclaredType given)) continue;
          if (!types.isSubtype(types.erasure(lower), types.erasure(bound))) return false;
          final var view = viewAs(given, (TypeElement) declared.asElement());
          if (view != null && !containing(declared, view, into)) return false;
        }
        for (final var upper : uppers) {
          if (!(upper instanceof DeclaredType wanted)) continue;
          final DeclaredType boundView;
          final DeclaredType upperView;
          if (types.isSubtype(types.erasure(bound), types.erasure(upper))) {
            boundView = viewAs(declared, (TypeElement) wanted.asElement());
            upperView = wanted;
          } else if (types.isSubtype(types.erasure(upper), types.erasure(bound))) {
            boundView = declared;
            upperView = viewAs(wanted, (TypeElement) declared.asElement());
          } else {
            continue;
          }
          if (boundView != null && upperView != null && !sameArguments(boundView, upperView, into)) return false;
        }
      }
    }
    return true;
  }

  /**
   * Records what {@code upper}'s type arguments ask of {@code bound}'s, two parameterizations of
   * one class that a variable's one supertype of that class has to be: a pair of types is one type,
   * and a pair with a wildcard is kept for {@link #contains}. False where a pair of types cannot be
   * one type.
   */
  private boolean sameArguments(final DeclaredType bound, final DeclaredType upper, final Gathered into) {
    final var boundArguments = bound.getTypeArguments();
    final var upperArguments = upper.getTypeArguments();
    if (boundArguments.size() != upperArguments.size()) return true;
    for (int i = 0; i < boundArguments.size(); i++) {
      final var argument = boundArguments.get(i);
      final var met = upperArguments.get(i);
      if (!names(argument, into.variables)) continue;
      if (argument instanceof WildcardType || met instanceof WildcardType) {
        into.pending.add(new Containment(argument, met));
      } else if (!gather(argument, met, Relation.EXACT, into)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether the type arguments of a {@link Containment}, read with {@code fixed} replaced by {@code
   * values}, can belong to one supertype: one argument a type, the other a wildcard that contains
   * it. True where either still names a variable, or both are wildcards.
   */
  private boolean contains(final Containment pair, final List<TypeMirror> fixed, final List<TypeMirror> values) {
    final var bound = props.substitute(pair.bound(), fixed, values);
    final var upper = props.substitute(pair.upper(), fixed, values);
    if (props.mentionsTypeVariable(bound) || props.mentionsTypeVariable(upper)) return true;
    if (bound instanceof WildcardType wildcard && !(upper instanceof WildcardType)) return holds(wildcard, upper);
    if (upper instanceof WildcardType wildcard && !(bound instanceof WildcardType)) return holds(wildcard, bound);
    return true;
  }

  /** Whether {@code wildcard} contains the type {@code type}. */
  private boolean holds(final WildcardType wildcard, final TypeMirror type) {
    if (wildcard.getExtendsBound() != null) return types.isSubtype(type, wildcard.getExtendsBound());
    if (wildcard.getSuperBound() != null) return types.isSubtype(wildcard.getSuperBound(), type);
    return true;
  }

  /**
   * A type argument of a variable's bound and the one in its place in a type the variable has to be
   * a subtype of, one of them a wildcard.
   */
  private record Containment(TypeMirror bound, TypeMirror upper) {}

  /**
   * The instantiation javac gives a variable nothing here fixes: the one type it has to be a
   * supertype of, or, where nothing bounds it but its declaration, its one declared bound naming no
   * variable; null otherwise.
   */
  private TypeMirror javacInstantiation(final TypeVariable variable, final Facts fact) {
    if (!fact.exact.isEmpty() || !fact.uppers().isEmpty()) return null;
    if (!fact.lower.isEmpty()) {
      final var first = fact.lower.getFirst();
      return fact.lower.stream().allMatch(lower -> types.isSameType(lower, first)) ? first : null;
    }
    final var bounds = boundsOf(variable);
    return bounds.size() == 1 && !props.mentionsTypeVariable(bounds.getFirst()) ? bounds.getFirst() : null;
  }

  /** Whether a bound of any of {@code variables} names {@code variable}. */
  private boolean namedByABound(final TypeVariable variable, final List<? extends TypeVariable> variables) {
    final var one = List.of(variable);
    return variables
      .stream()
      .anyMatch(each ->
        boundsOf(each)
          .stream()
          .anyMatch(bound -> names(bound, one))
      );
  }

  /** How the type a variable stands for relates to the type it meets. */
  private enum Relation {
    /** The variable's type is the met type exactly. */
    EXACT,
    /** The variable's type is a subtype of the met type. */
    SUBTYPE,
    /** The variable's type is a supertype of the met type. */
    SUPERTYPE,
    /**
     * The result as a whole meets the taken type: a subtype of it where the call is passed as it
     * is, which it is wherever the result still names a variable.
     */
    TOP,
  }

  /** What the result's shape asks of one variable. */
  private static final class Facts {

    /** Types the variable is exactly. */
    final List<TypeMirror> exact = new ArrayList<>();

    /** Types the variable is a subtype of, inside the result. */
    final List<TypeMirror> upper = new ArrayList<>();

    /** Types the variable is a subtype of, where the whole result is the variable. */
    final List<TypeMirror> top = new ArrayList<>();

    /** Types the variable is a supertype of. */
    final List<TypeMirror> lower = new ArrayList<>();

    /** Every type the variable is a subtype of, inside the result or as the whole of it. */
    List<TypeMirror> uppers() {
      final var all = new ArrayList<>(upper);
      all.addAll(top);
      return all;
    }
  }

  /**
   * The facts per variable; the pairs of type arguments a bound and a supertype give one class, one
   * of them a wildcard, to check once the variables are instantiated; and whether the result's
   * class is no subtype of the taken type's, so that only a check of its class could write it,
   * which the call cannot carry while it names a variable.
   */
  private static final class Gathered {

    final List<? extends TypeVariable> variables;
    final List<Facts> facts;
    final List<Containment> pending = new ArrayList<>();
    boolean topUnrelated;

    Gathered(final List<? extends TypeVariable> variables, final List<Facts> facts) {
      this.variables = variables;
      this.facts = facts;
    }
  }

  /**
   * Records what {@code met} asks of the variables in {@code given}; false where it shows no
   * instantiation can meet it.
   */
  private boolean gather(final TypeMirror given, final TypeMirror met, final Relation relation, final Gathered into) {
    if (!names(given, into.variables)) return true;
    if (given instanceof TypeVariable variable) {
      final var fact = into.facts.get(indexOf(variable, into.variables));
      switch (relation) {
        case EXACT -> fact.exact.add(met);
        case SUBTYPE -> fact.upper.add(met);
        case SUPERTYPE -> fact.lower.add(met);
        case TOP -> fact.top.add(met);
      }
      return true;
    }
    if (given instanceof WildcardType) return relation != Relation.EXACT;
    if (given instanceof DeclaredType declared) {
      if (!(met instanceof DeclaredType wanted)) {
        // A class type is never an array type or a primitive, nor a subtype of an array type, and
        // is a supertype of an array type only as one of the classes every array extends.
        return switch (relation) {
          case EXACT, SUBTYPE -> false;
          case TOP -> {
            into.topUnrelated = true;
            yield true;
          }
          case SUPERTYPE -> types.isSubtype(met, types.erasure(given));
        };
      }
      return switch (relation) {
        case EXACT -> types.isSameType(types.erasure(given), types.erasure(met)) &&
        arguments(declared, wanted, into, true) &&
        enclosing(declared, wanted, relation, into);
        case SUBTYPE, TOP -> {
          if (!types.isSubtype(types.erasure(given), types.erasure(met))) {
            if (relation == Relation.SUBTYPE) yield false;
            into.topUnrelated = true;
            yield true;
          }
          final var view = viewAs(declared, (TypeElement) wanted.asElement());
          yield view == null || (arguments(view, wanted, into, false) && enclosing(view, wanted, relation, into));
        }
        case SUPERTYPE -> {
          if (!types.isSubtype(types.erasure(met), types.erasure(given))) yield false;
          final var view = viewAs(wanted, (TypeElement) declared.asElement());
          yield view == null || (containing(declared, view, into) && enclosing(declared, view, relation, into));
        }
      };
    }
    if (given instanceof ArrayType array) {
      if (met instanceof ArrayType wantedArray) {
        final var component = array.getComponentType();
        final var inner =
          relation == Relation.EXACT || component.getKind().isPrimitive()
            ? Relation.EXACT
            : relation == Relation.SUPERTYPE
              ? Relation.SUPERTYPE
              : Relation.SUBTYPE;
        return gather(component, wantedArray.getComponentType(), inner, into);
      }
      // An array type is a supertype of array types alone.
      if (relation == Relation.EXACT || relation == Relation.SUPERTYPE) return false;
      return types.isSubtype(types.erasure(given), met);
    }
    return true;
  }

  /**
   * Records what {@code wanted}'s type arguments ask of those of {@code given}, one class's
   * parameterizations: a type argument is met exactly, and a wildcard bounds the argument it meets.
   * Where the two types are to be the same type, {@code exact}, each argument is too: a type is
   * never a wildcard, and two wildcards are one only where they are bounded the same way by the
   * same type.
   */
  private boolean arguments(
    final DeclaredType given,
    final DeclaredType wanted,
    final Gathered into,
    final boolean exact
  ) {
    final var givenArguments = given.getTypeArguments();
    final var wantedArguments = wanted.getTypeArguments();
    if (givenArguments.size() != wantedArguments.size()) return true;
    for (int i = 0; i < givenArguments.size(); i++) {
      final var argument = givenArguments.get(i);
      final var met = wantedArguments.get(i);
      if (met instanceof WildcardType wildcard) {
        if (argument instanceof WildcardType inResult) {
          final var fits = exact ? sameWildcard(inResult, wildcard, into) : wildcardContained(inResult, wildcard, into);
          if (!fits) return false;
          continue;
        }
        if (exact) {
          if (names(argument, into.variables)) return false;
          continue;
        }
        if (wildcard.getExtendsBound() != null) {
          if (!gather(argument, wildcard.getExtendsBound(), Relation.SUBTYPE, into)) return false;
        } else if (wildcard.getSuperBound() != null) {
          if (!gather(argument, wildcard.getSuperBound(), Relation.SUPERTYPE, into)) return false;
        }
      } else if (!gather(argument, met, Relation.EXACT, into)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Records what {@code met}, a wildcard argument of the taken type, asks of the variables {@code
   * given}, the wildcard in its place in the result, names, for {@code given} to be contained in
   * it: a wildcard bounded from above is contained in one bounded from above by a supertype of its
   * bound, and one bounded from below in one bounded from below by a subtype of its bound. A
   * wildcard bounded from above is contained in none bounded from below, and one bounded from below
   * in one bounded from above only where that bound is {@code Object}; false where such a {@code
   * given} names a variable.
   */
  private boolean wildcardContained(final WildcardType given, final WildcardType met, final Gathered into) {
    if (given.getExtendsBound() != null && met.getExtendsBound() != null) {
      return gather(given.getExtendsBound(), met.getExtendsBound(), Relation.SUBTYPE, into);
    }
    if (given.getSuperBound() != null && met.getSuperBound() != null) {
      return gather(given.getSuperBound(), met.getSuperBound(), Relation.SUPERTYPE, into);
    }
    if (!names(given, into.variables)) return true;
    if (given.getExtendsBound() != null) return met.getSuperBound() == null;
    return met.getExtendsBound() == null || isObject(met.getExtendsBound());
  }

  /**
   * Records what {@code met}, a wildcard argument of the taken type, asks of the variables {@code
   * given}, the wildcard in its place in the result, names, for the two to be one wildcard: bounded
   * the same way, by the same type. An unbounded wildcard is bounded from above by {@code Object}.
   * False where {@code given} names a variable and the two are bounded different ways.
   */
  private boolean sameWildcard(final WildcardType given, final WildcardType met, final Gathered into) {
    if (!names(given, into.variables)) return true;
    if (given.getSuperBound() != null || met.getSuperBound() != null) {
      return (
        given.getSuperBound() != null &&
        met.getSuperBound() != null &&
        gather(given.getSuperBound(), met.getSuperBound(), Relation.EXACT, into)
      );
    }
    final var givenBound = given.getExtendsBound() == null ? objectType() : given.getExtendsBound();
    final var metBound = met.getExtendsBound() == null ? objectType() : met.getExtendsBound();
    return gather(givenBound, metBound, Relation.EXACT, into);
  }

  /** Whether {@code type} is {@code Object}. */
  private boolean isObject(final TypeMirror type) {
    return types.isSameType(type, objectType());
  }

  /** The type {@code Object}. */
  private TypeMirror objectType() {
    return props.objectType();
  }

  /**
   * Records what {@code wanted}'s enclosing type asks of the variables {@code given}'s names, the
   * two being one inner class's parameterizations: the enclosing type of a subtype is a subtype of
   * its supertype's, as {@code relation} relates the two inner types.
   */
  private boolean enclosing(
    final DeclaredType given,
    final DeclaredType wanted,
    final Relation relation,
    final Gathered into
  ) {
    if (!(given.getEnclosingType() instanceof DeclaredType outer)) return true;
    if (!(wanted.getEnclosingType() instanceof DeclaredType wantedOuter)) return true;
    return gather(outer, wantedOuter, relation == Relation.TOP ? Relation.SUBTYPE : relation, into);
  }

  /**
   * Records what {@code given}'s type arguments ask of the variables they name, where {@code view},
   * a parameterization of the same class, is a subtype of it, so each of its arguments is contained
   * in {@code given}'s: an argument that is a type contains only that type, which is no wildcard; a
   * wildcard bounded from above contains the types below its bound, and one bounded from below the
   * types above it.
   */
  private boolean containing(final DeclaredType given, final DeclaredType view, final Gathered into) {
    final var givenArguments = given.getTypeArguments();
    final var viewArguments = view.getTypeArguments();
    if (givenArguments.size() != viewArguments.size()) return true;
    for (int i = 0; i < givenArguments.size(); i++) {
      final var argument = givenArguments.get(i);
      if (!names(argument, into.variables)) continue;
      final var met = viewArguments.get(i);
      if (argument instanceof WildcardType wildcard) {
        if (met instanceof WildcardType) continue;
        if (wildcard.getExtendsBound() != null) {
          if (!gather(wildcard.getExtendsBound(), met, Relation.SUPERTYPE, into)) return false;
        } else if (wildcard.getSuperBound() != null) {
          if (!gather(wildcard.getSuperBound(), met, Relation.SUBTYPE, into)) return false;
        }
      } else if (met instanceof WildcardType || !gather(argument, met, Relation.EXACT, into)) {
        return false;
      }
    }
    return true;
  }

  /** {@code type} as its supertype of class {@code element}, or null where none is found. */
  private DeclaredType viewAs(final DeclaredType type, final TypeElement element) {
    if (type.asElement().equals(element)) return type;
    for (final var parent : types.directSupertypes(type)) {
      if (
        parent instanceof DeclaredType declared &&
        types.isSubtype(types.erasure(parent), types.erasure(element.asType()))
      ) {
        final var view = viewAs(declared, element);
        if (view != null) return view;
      }
    }
    return null;
  }

  /**
   * Whether no class can be a subtype of both {@code a} and {@code b}, neither of which names a
   * type variable: two classes, not interfaces, neither a subtype of the other; an array type and a
   * class or interface every array does not extend, or two array types whose components are so; or
   * two types that reach one generic class or interface with different types for its arguments,
   * since a class has one supertype of each.
   */
  private boolean exclusive(final TypeMirror a, final TypeMirror b) {
    if (a instanceof ArrayType aa && b instanceof ArrayType ab) {
      final var ca = aa.getComponentType();
      final var cb = ab.getComponentType();
      if (ca.getKind().isPrimitive() || cb.getKind().isPrimitive()) return !types.isSameType(ca, cb);
      return exclusive(ca, cb);
    }
    if (a instanceof ArrayType && b instanceof DeclaredType) return !types.isSubtype(a, b);
    if (b instanceof ArrayType && a instanceof DeclaredType) return !types.isSubtype(b, a);
    if (!(a instanceof DeclaredType da) || !(b instanceof DeclaredType db)) return false;
    final var ea = types.erasure(a);
    final var eb = types.erasure(b);
    if (
      da.asElement().getKind().isClass() &&
      db.asElement().getKind().isClass() &&
      !types.isSubtype(ea, eb) &&
      !types.isSubtype(eb, ea)
    ) {
      return true;
    }
    for (final var supertype : supertypesOf(da)) {
      if (supertype.getTypeArguments().isEmpty()) continue;
      if (!types.isSubtype(eb, types.erasure(supertype))) continue;
      if (differentlyParameterized(supertype, viewAs(db, (TypeElement) supertype.asElement()))) return true;
    }
    return false;
  }

  /** {@code type} and every class and interface it extends, each read as a supertype of it. */
  private List<DeclaredType> supertypesOf(final DeclaredType type) {
    final var found = new ArrayList<DeclaredType>();
    final var pending = new ArrayDeque<DeclaredType>(List.of(type));
    while (!pending.isEmpty()) {
      final var next = pending.removeFirst();
      if (found.stream().anyMatch(seen -> types.isSameType(seen, next))) continue;
      found.add(next);
      for (final var parent : types.directSupertypes(next)) {
        if (parent instanceof DeclaredType declared) pending.addLast(declared);
      }
    }
    return found;
  }

  private static boolean wildcardArgument(final DeclaredType type) {
    return type.getTypeArguments().stream().anyMatch(WildcardType.class::isInstance);
  }

  /** Whether {@code a} and {@code b}, one class's parameterizations, give it different types. */
  private boolean differentlyParameterized(final DeclaredType a, final DeclaredType b) {
    if (a == null || b == null || a.getTypeArguments().isEmpty() || b.getTypeArguments().isEmpty()) return false;
    if (a.getTypeArguments().size() != b.getTypeArguments().size()) return false;
    if (props.mentionsTypeVariable(a) || props.mentionsTypeVariable(b)) return false;
    if (wildcardArgument(a) || wildcardArgument(b)) return false;
    return !types.isSameType(a, b);
  }

  /**
   * The final class among {@code candidates}, where one names no type variable and no wildcard;
   * null otherwise. A variable a final class bounds holds instances of that class alone.
   */
  private TypeMirror finalClass(final List<? extends TypeMirror> candidates) {
    for (final var bound : candidates) {
      if (!(bound instanceof DeclaredType declared) || props.mentionsTypeVariable(bound)) continue;
      if (wildcardArgument(declared)) continue;
      if (declared.asElement().getModifiers().contains(Modifier.FINAL)) return bound;
    }
    return null;
  }

  /** {@code variable}'s upper bounds, each component of an intersection on its own. */
  private static List<? extends TypeMirror> boundsOf(final TypeVariable variable) {
    final var bound = variable.getUpperBound();
    return bound instanceof IntersectionType intersection ? intersection.getBounds() : List.of(bound);
  }

  /** The index of the variable that is {@code member}'s one parameter's whole type, or -1. */
  private int parameterVariable(final ExecutableType member) {
    if (member.getParameterTypes().size() != 1) return -1;
    final var parameter = member.getParameterTypes().getFirst();
    return parameter instanceof TypeVariable variable ? indexOf(variable, member.getTypeVariables()) : -1;
  }

  private int indexOf(final TypeVariable variable, final List<? extends TypeVariable> variables) {
    for (int i = 0; i < variables.size(); i++) {
      if (types.isSameType(variable, variables.get(i))) return i;
    }
    return -1;
  }

  /** Whether {@code type} names any of {@code variables}. */
  private boolean names(final TypeMirror type, final List<? extends TypeVariable> variables) {
    if (type == null) return false;
    return switch (type) {
      case TypeVariable variable -> indexOf(variable, variables) >= 0;
      case ArrayType array -> names(array.getComponentType(), variables);
      case WildcardType wildcard -> names(wildcard.getExtendsBound(), variables) ||
      names(wildcard.getSuperBound(), variables);
      case DeclaredType declared -> names(declared.getEnclosingType(), variables) ||
      declared
        .getTypeArguments()
        .stream()
        .anyMatch(argument -> names(argument, variables));
      default -> false;
    };
  }

  private TypeMirror boxed(final TypeMirror type) {
    return type instanceof PrimitiveType primitive ? types.boxedClass(primitive).asType() : type;
  }
}
