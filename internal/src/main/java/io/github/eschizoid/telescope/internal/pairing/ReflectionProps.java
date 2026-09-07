package io.github.eschizoid.telescope.internal.pairing;

import io.github.eschizoid.telescope.internal.Beans;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
  public List<Type> typeArgumentsAs(final Type t, final WellKnown supertype) {
    return argumentsAs(t, classOf(supertype));
  }

  private List<Type> argumentsAs(final Type type, final Class<?> target) {
    final var raw = rawType(type);
    if (!(raw instanceof Class<?> cls) || !target.isAssignableFrom(cls)) return List.of();
    if (cls == target) return typeArguments(type);
    final var bindings = new HashMap<TypeVariable<?>, Type>();
    bind(type, bindings);
    for (final var parent : cls.getGenericInterfaces()) {
      final var resolved = substitute(parent, bindings);
      if (rawType(resolved) instanceof Class<?> c && target.isAssignableFrom(c)) {
        return argumentsAs(resolved, target);
      }
    }
    final var parent = cls.getGenericSuperclass();
    return parent == null ? List.of() : argumentsAs(substitute(parent, bindings), target);
  }

  private static void bind(final Type type, final Map<TypeVariable<?>, Type> bindings) {
    if (!(type instanceof ParameterizedType pt)) return;
    bind(pt.getOwnerType(), bindings);
    final var variables = ((Class<?>) pt.getRawType()).getTypeParameters();
    final var arguments = pt.getActualTypeArguments();
    for (int i = 0; i < variables.length; i++) bindings.put(variables[i], substitute(arguments[i], bindings));
  }

  private static Type substitute(final Type type, final Map<TypeVariable<?>, Type> bindings) {
    if (type instanceof TypeVariable<?> variable) return bindings.getOrDefault(variable, variable);
    if (type instanceof GenericArrayType array) {
      final var component = substitute(array.getGenericComponentType(), bindings);
      return component instanceof Class<?> cls ? Array.newInstance(cls, 0).getClass() : new ResolvedArray(component);
    }
    if (!(type instanceof ParameterizedType pt)) return type;
    final var arguments = Arrays.stream(pt.getActualTypeArguments())
      .map(t -> substitute(t, bindings))
      .toList();
    return new ResolvedType(pt.getRawType(), substitute(pt.getOwnerType(), bindings), arguments);
  }

  private record ResolvedArray(Type component) implements GenericArrayType {
    @Override
    public Type getGenericComponentType() {
      return component;
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof GenericArrayType array && component.equals(array.getGenericComponentType());
    }

    @Override
    public int hashCode() {
      return component.hashCode();
    }

    @Override
    public String getTypeName() {
      return component.getTypeName() + "[]";
    }
  }

  /** Structural equality with JDK ParameterizedType implementations, including nested arguments. */
  private record ResolvedType(Type raw, Type owner, List<Type> arguments) implements ParameterizedType {
    @Override
    public Type getRawType() {
      return raw;
    }

    @Override
    public Type getOwnerType() {
      return owner;
    }

    @Override
    public Type[] getActualTypeArguments() {
      return arguments.toArray(Type[]::new);
    }

    @Override
    public boolean equals(final Object other) {
      return (
        other instanceof ParameterizedType pt &&
        raw.equals(pt.getRawType()) &&
        Objects.equals(owner, pt.getOwnerType()) &&
        Arrays.equals(getActualTypeArguments(), pt.getActualTypeArguments())
      );
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(getActualTypeArguments()) ^ Objects.hashCode(owner) ^ raw.hashCode();
    }

    @Override
    public String getTypeName() {
      return raw.getTypeName() + "<" + String.join(", ", arguments.stream().map(Type::getTypeName).toList()) + ">";
    }

    @Override
    public String toString() {
      return getTypeName();
    }
  }

  @Override
  public Type rawType(final Type t) {
    if (t instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) return raw;
    return t;
  }

  @Override
  public Allocability copyAllocability(final Type src, final Type tgt) {
    // The allocator probe invokes a real constructor/builder; a type whose no-arg path throws is
    // simply not allocable — the failure must resolve the decision, not escape mid-analysis.
    // (The probe allocating at all is a known cost of proving constructibility; side-effectful
    // constructors should not be intermediate-allocated anyway, and this catch keeps them out.)
    try {
      final var allocable =
        src instanceof Class<?> srcCls &&
        tgt instanceof Class<?> tgtCls &&
        Beans.intermediateAllocator(srcCls).get() != null &&
        Beans.intermediateAllocator(tgtCls).get() != null;
      return allocable ? Allocability.ALLOCABLE : Allocability.NOT_ALLOCABLE;
    } catch (final RuntimeException e) {
      return Allocability.NOT_ALLOCABLE;
    }
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
