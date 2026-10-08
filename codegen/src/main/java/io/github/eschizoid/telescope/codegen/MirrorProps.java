package io.github.eschizoid.telescope.codegen;

import io.github.eschizoid.telescope.internal.pairing.PairingRules;
import io.github.eschizoid.telescope.internal.pairing.PropertySystem;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The {@code javax.lang.model} world's {@link PropertySystem}: type handles are {@link TypeMirror},
 * class handles are non-parameterized declared types (or primitives, matching the reflection
 * world's {@code Class} handles). {@link #copyAllocability} decides an interface or abstract class
 * through the shared allocation table, as the reflection world does, and reports {@code UNKNOWN}
 * for a concrete class, whose constructor compile time does not probe; how that uncertainty
 * resolves is the shared rules' policy, not this adapter's.
 */
final class MirrorProps implements PropertySystem<TypeMirror> {

  private final Types types;
  private final Elements elements;

  MirrorProps(final Types types, final Elements elements) {
    this.types = types;
    this.elements = elements;
  }

  @Override
  public boolean sameType(final TypeMirror a, final TypeMirror b) {
    return sameType(types, a, b);
  }

  /**
   * Type identity as {@link PropertySystem#sameType} defines it. {@link Types#isSameType} answers
   * false whenever either argument is a wildcard, a wildcard and itself included, so a wildcard is
   * compared here by its bounds and everything else is left to it.
   */
  static boolean sameType(final Types types, final TypeMirror a, final TypeMirror b) {
    if (a instanceof WildcardType wa && b instanceof WildcardType wb) {
      return (
        sameBound(types, writtenUpperBound(wa), writtenUpperBound(wb)) &&
        sameBound(types, wa.getSuperBound(), wb.getSuperBound())
      );
    }
    return types.isSameType(a, b);
  }

  /**
   * A wildcard's upper bound, or null where it bounds nothing narrower than {@code Object}: {@code
   * ?} and {@code ? extends Object} admit the same types.
   */
  private static TypeMirror writtenUpperBound(final WildcardType wildcard) {
    final var bound = wildcard.getExtendsBound();
    final var isObject =
      bound instanceof DeclaredType declared &&
      ((TypeElement) declared.asElement()).getQualifiedName().contentEquals("java.lang.Object");
    return isObject ? null : bound;
  }

  private static boolean sameBound(final Types types, final TypeMirror a, final TypeMirror b) {
    return a == null ? b == null : b != null && sameType(types, a, b);
  }

  @Override
  public boolean isAssignable(final TypeMirror from, final TypeMirror to) {
    return types.isAssignable(from, to);
  }

  @Override
  public boolean isWildcard(final TypeMirror t) {
    return t.getKind() == TypeKind.WILDCARD;
  }

  @Override
  public TypeMirror lowerBound(final TypeMirror t) {
    return t instanceof WildcardType wildcard ? wildcard.getSuperBound() : null;
  }

  @Override
  public boolean isClassType(final TypeMirror t) {
    // Arrays count: in the reflection world an array is a Class instance, and the container-view
    // map-key gate relies on the two worlds agreeing.
    if (t.getKind().isPrimitive() || t.getKind() == TypeKind.ARRAY) return true;
    return t instanceof DeclaredType dt && dt.getTypeArguments().isEmpty();
  }

  @Override
  public boolean isPrimitive(final TypeMirror t) {
    return t.getKind().isPrimitive();
  }

  @Override
  public TypeMirror boxed(final TypeMirror t) {
    return t instanceof PrimitiveType pt ? types.boxedClass(pt).asType() : t;
  }

  @Override
  public boolean isRecordType(final TypeMirror t) {
    return t instanceof DeclaredType dt && dt.asElement().getKind() == ElementKind.RECORD;
  }

  @Override
  public boolean isArrayType(final TypeMirror t) {
    return t.getKind() == TypeKind.ARRAY;
  }

  @Override
  public boolean isEnumType(final TypeMirror t) {
    return t instanceof DeclaredType dt && dt.asElement().getKind() == ElementKind.ENUM;
  }

  @Override
  public boolean isInterfaceType(final TypeMirror t) {
    return t instanceof DeclaredType dt && dt.asElement().getKind().isInterface();
  }

  @Override
  public boolean isSubtypeOf(final TypeMirror t, final WellKnown wellKnown) {
    final var target = elements.getTypeElement(fqnOf(wellKnown));
    if (target == null) return false;
    if (t.getKind().isPrimitive()) return false;
    return types.isAssignable(types.erasure(t), types.erasure(target.asType()));
  }

  @Override
  public boolean mentionsTypeVariable(final TypeMirror t) {
    return typeVariableIn(t);
  }

  /** {@link PropertySystem#mentionsTypeVariable}, for callers that hold no adapter. */
  static boolean typeVariableIn(final TypeMirror t) {
    if (t == null) return false;
    if (t instanceof TypeVariable) return true;
    if (t instanceof ArrayType array) return typeVariableIn(array.getComponentType());
    if (t instanceof WildcardType wildcard) {
      return typeVariableIn(wildcard.getExtendsBound()) || typeVariableIn(wildcard.getSuperBound());
    }
    if (!(t instanceof DeclaredType declared)) return false;
    if (typeVariableIn(declared.getEnclosingType())) return true;
    return declared.getTypeArguments().stream().anyMatch(MirrorProps::typeVariableIn);
  }

  @Override
  public List<TypeMirror> typeArguments(final TypeMirror t) {
    return t instanceof DeclaredType dt ? List.copyOf(dt.getTypeArguments()) : List.of();
  }

  @Override
  public List<TypeMirror> typeArgumentsAs(final TypeMirror t, final WellKnown supertype) {
    final var target = elements.getTypeElement(fqnOf(supertype));
    if (target == null || !(t instanceof DeclaredType dt)) return List.of();
    if (dt.asElement().equals(target)) return List.copyOf(dt.getTypeArguments());
    for (final var parent : types.directSupertypes(t)) {
      if (isSubtypeOf(parent, supertype)) return typeArgumentsAs(parent, supertype);
    }
    return List.of();
  }

  @Override
  public TypeMirror rawType(final TypeMirror t) {
    return types.erasure(t);
  }

  @Override
  public Allocability copyAllocability(final TypeMirror src, final TypeMirror tgt) {
    final var srcAnswer = copyAllocability(src);
    final var tgtAnswer = copyAllocability(tgt);
    if (srcAnswer == Allocability.NOT_ALLOCABLE || tgtAnswer == Allocability.NOT_ALLOCABLE) {
      return Allocability.NOT_ALLOCABLE;
    }
    return srcAnswer == Allocability.ALLOCABLE && tgtAnswer == Allocability.ALLOCABLE
      ? Allocability.ALLOCABLE
      : Allocability.UNKNOWN;
  }

  /**
   * One side's answer. An interface or abstract class is decided here, exactly as the reflection
   * world decides it: through the default implementation the shared table names for it, or not at
   * all. A concrete class is left unknown, because whether its constructor can be reached is a
   * question the generated code's own allocation checks answer.
   */
  private Allocability copyAllocability(final TypeMirror side) {
    // A copy pairs two classes the spec has already found to be collections or maps.
    final var element = ((DeclaredType) side).asElement();
    final var abstractType = element.getKind().isInterface() || element.getModifiers().contains(Modifier.ABSTRACT);
    if (!abstractType) return Allocability.UNKNOWN;
    return new PairingRules<TypeMirror>(this).hasDefaultImplementation(side)
      ? Allocability.ALLOCABLE
      : Allocability.NOT_ALLOCABLE;
  }

  @Override
  public String typeName(final TypeMirror t) {
    return t.toString();
  }

  @Override
  public String sourceName(final TypeMirror t) {
    final var element = elementOf(types.erasure(t));
    return element == null ? t.toString() : element.getQualifiedName().toString();
  }

  @Override
  public TypeMirror comparatorParameter(final TypeMirror impl, final List<TypeMirror> arguments) {
    final var implEl = elementOf(types.erasure(impl));
    final var comparator = elements.getTypeElement("java.util.Comparator");
    if (implEl == null || comparator == null) return null;
    final var parameters = implEl.getTypeParameters();
    final var substitutes = !parameters.isEmpty() && !arguments.isEmpty();
    if (substitutes && parameters.size() != arguments.size()) return null;
    final var owner = !substitutes
      ? (DeclaredType) implEl.asType()
      : types.getDeclaredType(implEl, arguments.toArray(TypeMirror[]::new));
    for (final var ctor : ElementFilter.constructorsIn(implEl.getEnclosedElements())) {
      if (!ctor.getModifiers().contains(Modifier.PUBLIC) || ctor.getParameters().size() != 1) continue;
      final var declared = ctor.getParameters().getFirst().asType();
      if (!types.isSameType(types.erasure(declared), types.erasure(comparator.asType()))) continue;
      return ((ExecutableType) types.asMemberOf(owner, ctor)).getParameterTypes().getFirst();
    }
    return null;
  }

  /** The {@link TypeElement} of a declared type handle, or {@code null}. */
  TypeElement elementOf(final TypeMirror t) {
    return t instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te ? te : null;
  }

  private static String fqnOf(final WellKnown wellKnown) {
    return switch (wellKnown) {
      case COLLECTION -> "java.util.Collection";
      case LIST -> "java.util.List";
      case SET -> "java.util.Set";
      case SORTED_SET -> "java.util.SortedSet";
      case QUEUE -> "java.util.Queue";
      case DEQUE -> "java.util.Deque";
      case MAP -> "java.util.Map";
      case SORTED_MAP -> "java.util.SortedMap";
      case OPTIONAL -> "java.util.Optional";
      case CHAR_SEQUENCE -> "java.lang.CharSequence";
      case NUMBER -> "java.lang.Number";
      case TEMPORAL -> "java.time.temporal.Temporal";
      case UUID -> "java.util.UUID";
      case BOOLEAN_WRAPPER -> "java.lang.Boolean";
      case CHARACTER_WRAPPER -> "java.lang.Character";
      case COMPARABLE -> "java.lang.Comparable";
    };
  }
}
