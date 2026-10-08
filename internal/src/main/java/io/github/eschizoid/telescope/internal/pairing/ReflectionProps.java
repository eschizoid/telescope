package io.github.eschizoid.telescope.internal.pairing;

import io.github.eschizoid.telescope.internal.Beans;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
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
  public boolean isAbstractType(final Type t) {
    return rawType(t) instanceof Class<?> c && (c.isInterface() || Modifier.isAbstract(c.getModifiers()));
  }

  @Override
  public boolean isImplementedBy(final Type t, final String className) {
    if (!(rawType(t) instanceof Class<?> c)) return false;
    try {
      return c.isAssignableFrom(Class.forName(className, false, c.getClassLoader()));
    } catch (final ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  @Override
  public Access noArgConstructorAccess(final Type t) {
    if (!(rawType(t) instanceof Class<?> c) || isAbstractType(c)) return Access.NONE;
    final int modifiers;
    try {
      modifiers = c.getDeclaredConstructor().getModifiers();
    } catch (final NoSuchMethodException e) {
      return Access.NONE;
    }
    if (Modifier.isPublic(modifiers)) return Access.PUBLIC;
    return Modifier.isPrivate(modifiers) ? Access.PRIVATE : Access.PACKAGE;
  }

  @Override
  public String packageName(final Type t) {
    return rawType(t) instanceof Class<?> c ? c.getPackageName() : "";
  }

  @Override
  public Type typeNamed(final String binaryName) {
    try {
      return Class.forName(binaryName, false, ReflectionProps.class.getClassLoader());
    } catch (final ClassNotFoundException | LinkageError e) {
      return null;
    }
  }

  @Override
  public boolean hasPublicConstructorAccepting(final Type t, final Type argument) {
    return (
      rawType(t) instanceof Class<?> c &&
      rawType(argument) instanceof Class<?> passed &&
      Arrays.stream(c.getConstructors()).anyMatch(
        ctor -> ctor.getParameterCount() == 1 && ctor.getParameterTypes()[0].isAssignableFrom(passed)
      )
    );
  }

  @Override
  public boolean isSubtypeOf(final Type t, final WellKnown wellKnown) {
    return t instanceof Class<?> c && classOf(wellKnown).isAssignableFrom(c);
  }

  @Override
  public boolean isAssignable(final Type from, final Type to) {
    if (from.equals(to)) return true;
    // Boxing and unboxing, as an assignment context allows.
    if (from instanceof Class<?> f && f.isPrimitive()) return (
      !(to instanceof Class<?> t && t.isPrimitive()) && isAssignable(boxed(f), to)
    );
    if (to instanceof Class<?> t && t.isPrimitive()) return boxed(t).equals(from);
    // A type variable is assignable wherever one of its bounds is.
    if (from instanceof TypeVariable<?> variable) {
      return Arrays.stream(variable.getBounds()).anyMatch(bound -> isAssignable(bound, to));
    }
    final var toComponent = componentOf(to);
    if (toComponent != null) {
      final var fromComponent = componentOf(from);
      if (fromComponent == null) return false;
      if (fromComponent instanceof Class<?> fc && fc.isPrimitive()) return fromComponent.equals(toComponent);
      return isAssignable(fromComponent, toComponent);
    }
    final var source = rawClass(from);
    if (source == null) return false;
    if (to instanceof Class<?> target) return target.isAssignableFrom(source);
    if (!(to instanceof ParameterizedType wanted) || !(wanted.getRawType() instanceof Class<?> target)) return false;
    if (!target.isAssignableFrom(source)) return false;
    // A generic class used raw carries no arguments to check, which is the unchecked conversion.
    if (from instanceof Class<?> raw && raw.getTypeParameters().length > 0) return true;
    final var actual = argumentsAs(from, target);
    if (actual.isEmpty()) return true;
    final var expected = wanted.getActualTypeArguments();
    for (int i = 0; i < expected.length; i++) {
      if (!contains(expected[i], actual.get(i))) return false;
    }
    return true;
  }

  /** An array type's component, or null when the type is not an array. */
  private static Type componentOf(final Type type) {
    if (type instanceof GenericArrayType array) return array.getGenericComponentType();
    return type instanceof Class<?> cls && cls.isArray() ? cls.getComponentType() : null;
  }

  /**
   * Whether a type argument admits another: a wildcard admits what its bounds allow, and anything
   * else admits only itself.
   */
  private boolean contains(final Type expected, final Type actual) {
    if (!(expected instanceof WildcardType wildcard)) return expected.equals(actual);
    final var actualUpper = actual instanceof WildcardType w ? w.getUpperBounds() : new Type[] { actual };
    for (final var upper : wildcard.getUpperBounds()) {
      if (Arrays.stream(actualUpper).noneMatch(bound -> isAssignable(bound, upper))) return false;
    }
    for (final var lower : wildcard.getLowerBounds()) {
      final var actualLower = actual instanceof WildcardType w ? w.getLowerBounds() : new Type[] { actual };
      if (Arrays.stream(actualLower).noneMatch(bound -> isAssignable(lower, bound))) return false;
    }
    return true;
  }

  /** The class a type erases to, or null for a type that names none. */
  private static Class<?> rawClass(final Type type) {
    return switch (type) {
      case Class<?> cls -> cls;
      case ParameterizedType pt when pt.getRawType() instanceof Class<?> cls -> cls;
      case TypeVariable<?> variable -> rawClass(variable.getBounds()[0]);
      case WildcardType wildcard -> rawClass(wildcard.getUpperBounds()[0]);
      case GenericArrayType array -> {
        final var component = rawClass(array.getGenericComponentType());
        yield component == null ? null : Array.newInstance(component, 0).getClass();
      }
      default -> null;
    };
  }

  @Override
  public boolean isWildcard(final Type t) {
    return t instanceof WildcardType;
  }

  @Override
  public Type lowerBound(final Type t) {
    return t instanceof WildcardType wildcard && wildcard.getLowerBounds().length > 0
      ? wildcard.getLowerBounds()[0]
      : null;
  }

  /**
   * {@code type} with the type variables of {@code owner} replaced by {@code arguments}, in
   * declaration order, so a member read off the raw class answers in terms of the arguments a field
   * gave it.
   */
  public Type resolve(final Type type, final Class<?> owner, final List<Type> arguments) {
    final var variables = owner.getTypeParameters();
    final var bindings = new HashMap<TypeVariable<?>, Type>();
    for (int i = 0; i < variables.length && i < arguments.size(); i++) bindings.put(variables[i], arguments.get(i));
    return substitute(type, bindings);
  }

  @Override
  public boolean mentionsTypeVariable(final Type t) {
    if (t instanceof TypeVariable<?>) return true;
    if (t instanceof GenericArrayType array) return mentionsTypeVariable(array.getGenericComponentType());
    if (t instanceof WildcardType wildcard) {
      return anyMentionsTypeVariable(wildcard.getUpperBounds()) || anyMentionsTypeVariable(wildcard.getLowerBounds());
    }
    if (!(t instanceof ParameterizedType pt)) return false;
    if (pt.getOwnerType() != null && mentionsTypeVariable(pt.getOwnerType())) return true;
    return anyMentionsTypeVariable(pt.getActualTypeArguments());
  }

  private boolean anyMentionsTypeVariable(final Type[] types) {
    return Arrays.stream(types).anyMatch(this::mentionsTypeVariable);
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
    if (type instanceof WildcardType wildcard) {
      return new ResolvedWildcard(
        Arrays.stream(wildcard.getUpperBounds())
          .map(t -> substitute(t, bindings))
          .toList(),
        Arrays.stream(wildcard.getLowerBounds())
          .map(t -> substitute(t, bindings))
          .toList()
      );
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

  /** Structural equality with the JDK's WildcardType implementation, whose hash it also matches. */
  private record ResolvedWildcard(List<Type> upper, List<Type> lower) implements WildcardType {
    @Override
    public Type[] getUpperBounds() {
      return upper.toArray(Type[]::new);
    }

    @Override
    public Type[] getLowerBounds() {
      return lower.toArray(Type[]::new);
    }

    @Override
    public boolean equals(final Object other) {
      return (
        other instanceof WildcardType wildcard &&
        Arrays.equals(getUpperBounds(), wildcard.getUpperBounds()) &&
        Arrays.equals(getLowerBounds(), wildcard.getLowerBounds())
      );
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(getLowerBounds()) ^ Arrays.hashCode(getUpperBounds());
    }

    @Override
    public String getTypeName() {
      if (!lower.isEmpty()) return "? super " + lower.getFirst().getTypeName();
      return upper.isEmpty() || upper.getFirst() == Object.class ? "?" : "? extends " + upper.getFirst().getTypeName();
    }

    @Override
    public String toString() {
      return getTypeName();
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
        copyAllocable(srcCls) &&
        copyAllocable(tgtCls);
      return allocable ? Allocability.ALLOCABLE : Allocability.NOT_ALLOCABLE;
    } catch (final RuntimeException e) {
      return Allocability.NOT_ALLOCABLE;
    }
  }

  /**
   * Whether a copy can build this side: an interface or abstract class through the default
   * implementation the shared table names for it, which the runtime copy allocates in its place,
   * and any other class through the constructor the shared allocation rules let a rebuild call, or
   * through its builder where they let it call none.
   */
  private boolean copyAllocable(final Class<?> cls) {
    if (cls.isInterface() || Modifier.isAbstract(cls.getModifiers())) {
      return new PairingRules<Type>(this).hasDefaultImplementation(cls);
    }
    final var allocation = new ContainerAllocation<Type>(this);
    final var alloc =
      allocation.allocate(cls, allocation.familyOf(cls), null) instanceof Allocation.Build
        ? Beans.noArgConstructor(cls)
        : Beans.intermediateAllocator(cls);
    return alloc != null && alloc.get() != null;
  }

  @Override
  public String typeName(final Type t) {
    return t.getTypeName();
  }

  @Override
  public String sourceName(final Type t) {
    if (!(rawType(t) instanceof Class<?> raw)) return t.getTypeName();
    return raw.getCanonicalName() == null ? raw.getName() : raw.getCanonicalName();
  }

  @Override
  public Type comparatorParameter(final Type impl, final List<Type> arguments) {
    if (!(rawType(impl) instanceof Class<?> raw)) return null;
    final var variables = raw.getTypeParameters();
    if (variables.length != 0 && !arguments.isEmpty() && variables.length != arguments.size()) return null;
    if (Beans.publicConstructor(raw, Comparator.class) == null) return null;
    for (final var ctor : raw.getConstructors()) {
      if (ctor.getParameterCount() != 1 || ctor.getParameterTypes()[0] != Comparator.class) continue;
      return resolve(ctor.getGenericParameterTypes()[0], raw, arguments);
    }
    return null;
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
      case COMPARABLE -> Comparable.class;
    };
  }
}
