package io.github.eschizoid.telescope.codegen;

import io.github.eschizoid.telescope.internal.pairing.PropertySystem;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The {@code javax.lang.model} world's {@link PropertySystem}: type handles are {@link TypeMirror},
 * class handles are non-parameterized declared types (or primitives, matching the reflection
 * world's {@code Class} handles). {@link #copyAllocability} reports {@code UNKNOWN} — compile-time
 * can't probe the real intermediate allocator; how that uncertainty resolves is the shared rules'
 * policy, not this adapter's.
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
    return types.isSameType(a, b);
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
  public List<TypeMirror> typeArguments(final TypeMirror t) {
    return t instanceof DeclaredType dt ? List.copyOf(dt.getTypeArguments()) : List.of();
  }

  @Override
  public TypeMirror rawType(final TypeMirror t) {
    return types.erasure(t);
  }

  @Override
  public Allocability copyAllocability(final TypeMirror src, final TypeMirror tgt) {
    return Allocability.UNKNOWN;
  }

  @Override
  public String typeName(final TypeMirror t) {
    return t.toString();
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
    };
  }
}
