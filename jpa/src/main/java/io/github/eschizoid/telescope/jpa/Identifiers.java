package io.github.eschizoid.telescope.jpa;

import io.github.eschizoid.telescope.internal.Beans;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The identifier of an entity class, as its persistence mapping declares it: the property annotated
 * {@link Id} or {@link EmbeddedId}, on a field or a getter of the class or of a superclass, read
 * through its getter. Several {@link Id} properties, as an {@code @IdClass} declares, make one
 * identifier of all their values together. Found once per class; a class that is no {@link Entity},
 * or whose identifier has no getter, has none.
 *
 * <p>The identifier is generated where a property of it is {@link GeneratedValue}, or carries an
 * annotation that Hibernate's {@code org.hibernate.annotations.IdGeneratorType} marks as an
 * identifier generator, such as {@code @UuidGenerator}; that type is matched by name and never
 * linked, so the module needs no Hibernate. A generator another provider declares in an annotation
 * of its own, and that names no {@code @GeneratedValue}, is not seen, and its identifier is taken
 * for an assigned one.
 *
 * <p>Touched only once {@link LoadStates#AVAILABLE} holds, since it links against the persistence
 * annotations.
 */
final class Identifiers {

  /** The identifier of each class, or {@link #NONE}. */
  private static final ClassValue<Identifier> OF = new ClassValue<>() {
    @Override
    protected Identifier computeValue(final Class<?> type) {
      return discover(type);
    }
  };

  /** What a class with no identifier to merge by has. */
  static final Identifier NONE = new Identifier(List.of(), List.of(), false, false, null);

  /** Hibernate's mark on an annotation that declares an identifier generator. */
  private static final String ID_GENERATOR_TYPE = "org.hibernate.annotations.IdGeneratorType";

  private Identifiers() {}

  /** The identifier of {@code type}, or {@link #NONE}. */
  static Identifier of(final Class<?> type) {
    return OF.get(type);
  }

  /**
   * One entity class's identifier: the readers of its identifier properties, the class's own first
   * and each class's in name order, whether the provider generates it, and whether it is one
   * property of a primitive type.
   *
   * @param names the name of each identifier property, in the order of {@code readers}
   * @param readers the getter of each identifier property
   * @param generated whether a property of the identifier is generated
   * @param primitive whether the identifier is one property of a primitive type, whose default no
   *     row carries, as the provider reads an unsaved instance's
   * @param entity the simple name of the entity class, as a refusal names it
   */
  record Identifier(
    List<String> names,
    List<Function<Object, Object>> readers,
    boolean generated,
    boolean primitive,
    String entity
  ) {
    /**
     * The identifier of {@code element}: the value of its one identifier property, or the list of
     * the values of several; null where any is null, and where a primitive identifier holds its
     * type's default, which marks an element no row carries yet. A boxed identifier that holds zero
     * is an identifier, since null is how such an element says it is new.
     */
    Object of(final Object element) {
      if (readers.size() == 1) {
        final var value = readers.getFirst().apply(element);
        return primitive && isDefault(value) ? null : value;
      }
      final var values = new Object[readers.size()];
      for (var i = 0; i < values.length; i++) {
        values[i] = readers.get(i).apply(element);
        if (values[i] == null) return null;
      }
      return List.of(values);
    }

    /**
     * The identifier a sent element names through its properties of the identifier properties'
     * names, as {@code valueOf} reads each: one value, or the list of several; null where any is
     * null, and where a primitive identifier holds its type's default.
     */
    Object sentBy(final Function<String, Object> valueOf) {
      if (names.size() == 1) {
        final var value = valueOf.apply(names.getFirst());
        return primitive && isDefault(value) ? null : value;
      }
      final var values = new Object[names.size()];
      for (var i = 0; i < values.length; i++) {
        values[i] = valueOf.apply(names.get(i));
        if (values[i] == null) return null;
      }
      return List.of(values);
    }

    private static boolean isDefault(final Object value) {
      return switch (value) {
        case Number number -> number.doubleValue() == 0;
        case Character character -> character == 0;
        case null, default -> false;
      };
    }
  }

  private static Identifier discover(final Class<?> type) {
    if (!type.isAnnotationPresent(Entity.class)) return NONE;
    final var names = new ArrayList<String>();
    var generated = false;
    var primitive = false;
    for (var declaring = type; declaring != null && declaring != Object.class; declaring = declaring.getSuperclass()) {
      for (final var field : sorted(declaring.getDeclaredFields())) {
        if (Modifier.isStatic(field.getModifiers()) || !identifies(field)) continue;
        names.add(field.getName());
        generated |= generates(field);
        primitive |= field.getType().isPrimitive();
      }
      for (final var method : sorted(declaring.getDeclaredMethods())) {
        if (Modifier.isStatic(method.getModifiers()) || !identifies(method)) continue;
        final var name = propertyOf(method);
        if (name == null || names.contains(name)) continue;
        names.add(name);
        generated |= generates(method);
        primitive |= method.getReturnType().isPrimitive();
      }
    }
    if (names.isEmpty()) return NONE;
    final var readers = new ArrayList<Function<Object, Object>>(names.size());
    for (final var name : names) {
      try {
        readers.add(Beans.capturedReader(type, name));
      } catch (final IllegalArgumentException noGetter) {
        return NONE;
      }
    }
    return new Identifier(
      List.copyOf(names),
      List.copyOf(readers),
      generated,
      primitive && names.size() == 1,
      type.getSimpleName()
    );
  }

  /** The properties of each class whose value its persistence mapping generates. */
  private static final ClassValue<Set<String>> GENERATED = new ClassValue<>() {
    @Override
    protected Set<String> computeValue(final Class<?> type) {
      final var names = new LinkedHashSet<String>();
      for (
        var declaring = type;
        declaring != null && declaring != Object.class;
        declaring = declaring.getSuperclass()
      ) {
        for (final var field : sorted(declaring.getDeclaredFields())) {
          if (!Modifier.isStatic(field.getModifiers()) && generatedValue(field)) names.add(field.getName());
        }
        for (final var method : sorted(declaring.getDeclaredMethods())) {
          if (Modifier.isStatic(method.getModifiers()) || !generatedValue(method)) continue;
          final var name = propertyOf(method);
          if (name != null) names.add(name);
        }
      }
      return Set.copyOf(names);
    }
  };

  /**
   * Whether property {@code property} of {@code type} holds a value its persistence mapping
   * generates: an identifier, {@link Id} or {@link EmbeddedId}, with a generator, or a {@link
   * Version}, declared on the field of its name or on its getter, in the class or a superclass.
   */
  static boolean generated(final Class<?> type, final String property) {
    return GENERATED.get(type).contains(property);
  }

  private static boolean generatedValue(final AnnotatedElement member) {
    return member.isAnnotationPresent(Version.class) || (identifies(member) && generates(member));
  }

  private static boolean identifies(final AnnotatedElement member) {
    return member.isAnnotationPresent(Id.class) || member.isAnnotationPresent(EmbeddedId.class);
  }

  /**
   * Whether {@code member}'s value is generated: it is {@link GeneratedValue}, or carries an
   * annotation Hibernate marks as an identifier generator.
   */
  private static boolean generates(final AnnotatedElement member) {
    if (member.isAnnotationPresent(GeneratedValue.class)) return true;
    for (final var annotation : member.getAnnotations()) {
      for (final var mark : annotation.annotationType().getAnnotations())
        if (mark.annotationType().getName().equals(ID_GENERATOR_TYPE)) return true;
    }
    return false;
  }

  /** The members in name order, since reflection returns them in no specified order. */
  private static Field[] sorted(final Field[] fields) {
    Arrays.sort(fields, Comparator.comparing(Field::getName));
    return fields;
  }

  private static Method[] sorted(final Method[] methods) {
    Arrays.sort(methods, Comparator.comparing(Method::getName));
    return methods;
  }

  /** The bean property a getter reads, or null for a method that is no getter. */
  private static String propertyOf(final Method method) {
    if (method.getParameterCount() != 0) return null;
    final var name = method.getName();
    final var prefix = name.startsWith("get") ? 3 : name.startsWith("is") ? 2 : 0;
    if (prefix == 0 || name.length() == prefix) return null;
    final var rest = name.substring(prefix);
    if (
      rest.length() > 1 && Character.isUpperCase(rest.charAt(1)) && Character.isUpperCase(rest.charAt(0))
    ) return rest;
    return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
  }
}
