package io.github.eschizoid.telescope.codegen;

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
 * result's shape forces on each variable, and decides only what those facts decide.
 *
 * <ul>
 *   <li>A variable that is a type argument of the result where the taken type has a type, not a
 *       wildcard, is that type exactly, since a generic class's arguments are invariant. Where the
 *       taken type has a wildcard, the variable is bounded by it, from above or below.
 *   <li>A variable that is the method's one parameter is the argument's type or a supertype of it,
 *       and is the argument's type where nothing fixes it exactly, which is the type javac infers.
 *   <li>A variable bounded by a final class is that class, the only class whose instances it can
 *       hold.
 * </ul>
 *
 * <p>A variable fixed so has to satisfy every bound it declares, read with the fixed variables in
 * place, and every bound a wildcard puts on it. A variable nothing fixes is left to javac, which
 * rejects the call, as this does, where two of the types it has to be a subtype of are two classes
 * neither of which extends the other, or two parameterizations of one class, or where a type it has
 * to be a supertype of is no subtype of one of them. A result whose class is no subtype of the
 * taken type's, and which still names a variable, is passed as it is and fits under no
 * instantiation.
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
    if (parameter >= 0) facts.get(parameter).lower.add(boxed(argument));
    final var solved = new TypeMirror[variables.size()];
    for (int i = 0; i < variables.size(); i++) {
      final var fact = facts.get(i);
      if (!fact.exact.isEmpty()) {
        final var exact = fact.exact.getFirst();
        if (fact.exact.stream().anyMatch(t -> !types.isSameType(t, exact))) return null;
        solved[i] = exact;
      } else if (parameter == i) {
        solved[i] = boxed(argument);
      } else {
        solved[i] = finalBound(variables.get(i));
      }
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
        continue;
      }
      final var uppers = new ArrayList<TypeMirror>(bounds);
      uppers.addAll(fact.upper);
      uppers.addAll(fact.top);
      for (int a = 0; a < uppers.size(); a++) {
        for (int b = a + 1; b < uppers.size(); b++) {
          if (exclusive(uppers.get(a), uppers.get(b))) return null;
        }
      }
      for (final var lower : fact.lower) {
        if (uppers.stream().anyMatch(upper -> !types.isAssignable(lower, upper))) return null;
      }
    }
    final var result = props.substitute(declared, fixed, values);
    if (gathered.topUnrelated && props.mentionsTypeVariable(result)) return null;
    return result;
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
  }

  /**
   * The facts per variable, and whether the result's class is no subtype of the taken type's, so
   * that only a check of its class could write it, which the call cannot carry while it names a
   * variable.
   */
  private static final class Gathered {

    final List<? extends TypeVariable> variables;
    final List<Facts> facts;
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
        case EXACT -> types.isSameType(types.erasure(given), types.erasure(met)) && arguments(declared, wanted, into);
        case SUBTYPE, TOP -> {
          if (!types.isSubtype(types.erasure(given), types.erasure(met))) {
            if (relation == Relation.SUBTYPE) yield false;
            into.topUnrelated = true;
            yield true;
          }
          final var view = viewAs(declared, (TypeElement) wanted.asElement());
          yield view == null || arguments(view, wanted, into);
        }
        case SUPERTYPE -> {
          if (!types.isSubtype(types.erasure(met), types.erasure(given))) yield false;
          final var view = viewAs(wanted, (TypeElement) declared.asElement());
          yield view == null || containing(declared, view, into);
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
   */
  private boolean arguments(final DeclaredType given, final DeclaredType wanted, final Gathered into) {
    final var givenArguments = given.getTypeArguments();
    final var wantedArguments = wanted.getTypeArguments();
    if (givenArguments.size() != wantedArguments.size()) return true;
    for (int i = 0; i < givenArguments.size(); i++) {
      final var argument = givenArguments.get(i);
      final var met = wantedArguments.get(i);
      if (met instanceof WildcardType wildcard) {
        if (argument instanceof WildcardType) continue;
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
   * Records what {@code given}'s type arguments ask of the variables they name, where {@code view},
   * a parameterization of the same class, is a subtype of it: each argument that is a type contains
   * only that type, so the view's argument in its place is that type exactly, and is no wildcard.
   */
  private boolean containing(final DeclaredType given, final DeclaredType view, final Gathered into) {
    final var givenArguments = given.getTypeArguments();
    final var viewArguments = view.getTypeArguments();
    if (givenArguments.size() != viewArguments.size()) return true;
    for (int i = 0; i < givenArguments.size(); i++) {
      final var argument = givenArguments.get(i);
      if (argument instanceof WildcardType || !names(argument, into.variables)) continue;
      final var met = viewArguments.get(i);
      if (met instanceof WildcardType || !gather(argument, met, Relation.EXACT, into)) return false;
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
   * Whether no class can be a subtype of both {@code a} and {@code b}: two classes, not interfaces,
   * neither a subtype of the other, or two types one of which, read as the other's class, is a
   * different parameterization of it, with types for every argument. A class has one supertype of
   * each generic class or interface.
   */
  private boolean exclusive(final TypeMirror a, final TypeMirror b) {
    if (!(a instanceof DeclaredType da) || !(b instanceof DeclaredType db)) return false;
    final var ea = types.erasure(a);
    final var eb = types.erasure(b);
    if (types.isSubtype(ea, eb)) return differentlyParameterized(viewAs(da, (TypeElement) db.asElement()), db);
    if (types.isSubtype(eb, ea)) return differentlyParameterized(viewAs(db, (TypeElement) da.asElement()), da);
    return da.asElement().getKind().isClass() && db.asElement().getKind().isClass();
  }

  private static boolean wildcardArgument(final DeclaredType type) {
    return type.getTypeArguments().stream().anyMatch(WildcardType.class::isInstance);
  }

  /** Whether {@code a} and {@code b}, one class's parameterizations, give it different types. */
  private boolean differentlyParameterized(final DeclaredType a, final DeclaredType b) {
    if (a == null || a.getTypeArguments().isEmpty() || b.getTypeArguments().isEmpty()) return false;
    if (a.getTypeArguments().size() != b.getTypeArguments().size()) return false;
    if (props.mentionsTypeVariable(a) || props.mentionsTypeVariable(b)) return false;
    if (wildcardArgument(a) || wildcardArgument(b)) return false;
    return !types.isSameType(a, b);
  }

  /**
   * The final class among {@code variable}'s bounds, where one names no type variable and no
   * wildcard; null otherwise.
   */
  private TypeMirror finalBound(final TypeVariable variable) {
    for (final var bound : boundsOf(variable)) {
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
