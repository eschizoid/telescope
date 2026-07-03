package io.github.eschizoid.telescope.internal.pairing;

import io.github.eschizoid.telescope.internal.Beans;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.UUID;

/**
 * The reflection-world {@link PropertySystem}: type handles are {@link Type}, class handles are
 * {@link Class}, and allocability probes the real intermediate allocator. Used by the runtime
 * mapper construction; the compile-time verifier supplies the {@code javax.lang.model} twin.
 */
public final class ReflectionProps implements PropertySystem<Type> {

  @Override
  public boolean sameType(final Type a, final Type b) {
    return a.equals(b);
  }

  @Override
  public boolean isClassType(final Type t) {
    return t instanceof Class<?>;
  }

  @Override
  public boolean isPrimitive(final Type t) {
    return t instanceof Class<?> c && c.isPrimitive();
  }

  @Override
  public Type boxed(final Type t) {
    if (!(t instanceof Class<?> c) || !c.isPrimitive()) return t;
    if (c == int.class) return Integer.class;
    if (c == long.class) return Long.class;
    if (c == double.class) return Double.class;
    if (c == float.class) return Float.class;
    if (c == boolean.class) return Boolean.class;
    if (c == short.class) return Short.class;
    if (c == byte.class) return Byte.class;
    if (c == char.class) return Character.class;
    return t;
  }

  @Override
  public boolean isRecordType(final Type t) {
    return t instanceof Class<?> c && c.isRecord();
  }

  @Override
  public boolean isArrayType(final Type t) {
    return t instanceof Class<?> c && c.isArray();
  }

  @Override
  public boolean isEnumType(final Type t) {
    return t instanceof Class<?> c && c.isEnum();
  }

  @Override
  public boolean isInterfaceType(final Type t) {
    return t instanceof Class<?> c && c.isInterface();
  }

  @Override
  public boolean isSubtypeOf(final Type t, final WellKnown wellKnown) {
    return t instanceof Class<?> c && classOf(wellKnown).isAssignableFrom(c);
  }

  @Override
  public List<Type> typeArguments(final Type t) {
    return t instanceof ParameterizedType pt ? List.of(pt.getActualTypeArguments()) : List.of();
  }

  @Override
  public Type rawType(final Type t) {
    if (t instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) return raw;
    return t;
  }

  @Override
  public Allocability copyAllocability(final Type src, final Type tgt) {
    final var allocable =
      src instanceof Class<?> srcCls &&
      tgt instanceof Class<?> tgtCls &&
      Beans.intermediateAllocator(srcCls).get() != null &&
      Beans.intermediateAllocator(tgtCls).get() != null;
    return allocable ? Allocability.ALLOCABLE : Allocability.NOT_ALLOCABLE;
  }

  @Override
  public String typeName(final Type t) {
    return t.getTypeName();
  }

  private static Class<?> classOf(final WellKnown wellKnown) {
    return switch (wellKnown) {
      case COLLECTION -> Collection.class;
      case LIST -> List.class;
      case SET -> Set.class;
      case SORTED_SET -> SortedSet.class;
      case QUEUE -> Queue.class;
      case DEQUE -> Deque.class;
      case MAP -> Map.class;
      case SORTED_MAP -> SortedMap.class;
      case OPTIONAL -> Optional.class;
      case CHAR_SEQUENCE -> CharSequence.class;
      case NUMBER -> Number.class;
      case TEMPORAL -> Temporal.class;
      case UUID -> UUID.class;
      case BOOLEAN_WRAPPER -> Boolean.class;
      case CHARACTER_WRAPPER -> Character.class;
    };
  }
}
