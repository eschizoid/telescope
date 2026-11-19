package io.github.eschizoid.telescope.jackson;

import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.pairing.JsonPropertyKeys;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What the Jackson annotations on a type the binder builds say about how its properties are read.
 * Read once, when the binder is built, so a document pays nothing for them; the binder keeps the
 * keys they give and no annotation object.
 *
 * <p>An annotation is found by the name of its class, never by a reference to the class, so the
 * binder runs without {@code jackson-annotations}: then no annotation is present, and every
 * property reads the key of its own name.
 *
 * <p>The members looked at are the ones databind reads a property's annotations from. A record
 * component's annotations reach its canonical constructor's parameter, its field and its accessor,
 * and an accessor declared in the record body can carry its own. A bean property's are on its
 * setters, its field and its getter.
 */
final class JacksonAnnotations {

  private static final String ANNOTATION = "com.fasterxml.jackson.annotation.";
  private static final String PROPERTY = ANNOTATION + "JsonProperty";
  private static final String ALIAS = ANNOTATION + "JsonAlias";
  private static final String IGNORE = ANNOTATION + "JsonIgnore";
  private static final String IGNORE_PROPERTIES = ANNOTATION + "JsonIgnoreProperties";
  private static final String CREATOR = ANNOTATION + "JsonCreator";
  private static final String ANY_SETTER = ANNOTATION + "JsonAnySetter";
  private static final String UNWRAPPED = ANNOTATION + "JsonUnwrapped";
  private static final String VIEW = ANNOTATION + "JsonView";
  private static final String SETTER = ANNOTATION + "JsonSetter";
  private static final String FORMAT = ANNOTATION + "JsonFormat";
  private static final String INSIDE = ANNOTATION + "JacksonAnnotationsInside";
  private static final String NAMING = "tools.jackson.databind.annotation.JsonNaming";

  private JacksonAnnotations() {}

  /**
   * How the property {@code name} reads, decided by {@link JsonPropertyKeys#decide} from the
   * annotations on the members it is written through, {@code writes}, most preferred first, and the
   * member it is read through, {@code read}, which may be null.
   *
   * @throws IllegalArgumentException when two members the property is written through name it with
   *     different keys
   */
  static JsonPropertyKeys.Keys property(
    final Class<?> owner,
    final String kind,
    final String name,
    final List<? extends AnnotatedElement> writes,
    final AnnotatedElement read,
    final boolean creator
  ) {
    final var members = new ArrayList<JsonPropertyKeys.Member>(writes.size() + 1);
    for (final var member : writes) members.add(member(member, true));
    if (read != null) members.add(member(read, false));
    return JsonPropertyKeys.decide(owner.getSimpleName(), kind, name, members, creator);
  }

  /** What the annotations on {@code member} say, read through reflection by class name. */
  private static JsonPropertyKeys.Member member(final AnnotatedElement member, final boolean writing) {
    final var ignore = find(member, IGNORE);
    final var ignored = ignore != null && (Boolean) attribute(ignore, "value");
    final var property = find(member, PROPERTY);
    final var said =
      property == null
        ? null
        : new JsonPropertyKeys.Property(
            (String) attribute(property, "value"),
            ((Enum<?>) attribute(property, "access")).name().equals("READ_ONLY"),
            (Boolean) attribute(property, "required")
          );
    final var alias = find(member, ALIAS);
    final var aliases = alias == null ? null : List.of((String[]) attribute(alias, "value"));
    return new JsonPropertyKeys.Member(
      writing,
      writing && member instanceof Method,
      member instanceof Parameter,
      ignored,
      said,
      aliases
    );
  }

  /**
   * Refuses {@code type} when it carries a Jackson annotation that changes how databind reads it
   * and that the binder does not read, rather than read it differently without a word:
   *
   * <ul>
   *   <li>on the class, a naming strategy, a view, a format, or an ignore list naming properties;
   *   <li>on a constructor or a static method, a creator;
   *   <li>on a method or field of the class or a superclass, an any-setter;
   *   <li>on a property's members, an unwrapping, a view, a setter's own settings, a format, or an
   *       ignore list naming properties;
   *   <li>on the class or a property's members, an annotation of the application's own that bundles
   *       Jackson annotations through {@code @JacksonAnnotationsInside}.
   * </ul>
   *
   * <p>{@code @JsonIgnoreProperties} that only ignores unknown keys is accepted, since the binder
   * skips them anyway.
   *
   * @param members the members each property is declared by, by property name
   * @throws IllegalArgumentException naming the annotation and what carries it
   */
  static void refuseUnsupported(
    final Class<?> type,
    final String kind,
    final Map<String, List<AnnotatedElement>> members
  ) {
    refuse(type, type, "the class", NAMING, VIEW, FORMAT);
    refuseIgnoreList(type, type, "the class");
    refuseComposite(type, type, "the class");
    for (final var constructor : type.getDeclaredConstructors()) {
      refuse(type, constructor, "constructor " + signature(constructor), CREATOR);
    }
    for (var c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      for (final var method : c.getDeclaredMethods()) {
        refuse(type, method, "method " + signature(method), ANY_SETTER);
        if (Modifier.isStatic(method.getModifiers())) refuse(type, method, "method " + signature(method), CREATOR);
      }
      for (final var field : c.getDeclaredFields()) refuse(type, field, "field '" + field.getName() + "'", ANY_SETTER);
    }
    for (final var property : members.entrySet()) {
      final var where = kind + " '" + property.getKey() + "'";
      for (final var member : property.getValue()) {
        refuse(type, member, where, UNWRAPPED, VIEW, SETTER, FORMAT);
        refuseIgnoreList(type, member, where);
        refuseComposite(type, member, where);
      }
    }
  }

  /**
   * Refuses the enum {@code type} when {@code @JsonProperty} or {@code @JsonAlias} gives one of its
   * constants another name: databind reads such a constant by that name, and the binder reads a
   * constant by its exact name only. A {@code @JsonProperty} naming the constant by its own name
   * changes nothing and is accepted. One with no value is refused: on an enum constant, databind
   * reads it as the name {@code ""}, not as the constant's own name. An alias is always a second
   * name.
   *
   * @throws IllegalArgumentException naming the constant and the annotation
   */
  static void refuseRenamedConstants(final Class<?> type) {
    for (final var field : type.getDeclaredFields()) {
      if (!field.isEnumConstant()) continue;
      final var property = find(field, PROPERTY);
      final var renamed = property != null && !attribute(property, "value").equals(field.getName());
      final var annotation = renamed ? PROPERTY : find(field, ALIAS) != null ? ALIAS : null;
      if (annotation != null) {
        throw new IllegalArgumentException(
          PairingMessages.jsonEnumConstantRenamed(type.getSimpleName(), field.getName(), simpleName(annotation))
        );
      }
    }
  }

  /** Refuses {@code @JsonIgnoreProperties} on {@code member} when it names properties. */
  private static void refuseIgnoreList(final Class<?> type, final AnnotatedElement member, final String where) {
    final var ignoreProperties = find(member, IGNORE_PROPERTIES);
    if (ignoreProperties != null && ((String[]) attribute(ignoreProperties, "value")).length > 0) {
      throw refusal(type, IGNORE_PROPERTIES, where);
    }
  }

  /** Refuses an annotation on {@code member} that bundles Jackson annotations. */
  private static void refuseComposite(final Class<?> type, final AnnotatedElement member, final String where) {
    for (final var annotation : member.getDeclaredAnnotations()) {
      if (find(annotation.annotationType(), INSIDE) != null) {
        throw new IllegalArgumentException(
          PairingMessages.jsonAnnotationUnsupported(
            annotation.annotationType().getSimpleName() + ", which bundles Jackson annotations,",
            where,
            type.getSimpleName()
          )
        );
      }
    }
  }

  /**
   * The members each component of the record {@code type} is written through, in component order:
   * its canonical constructor's parameter, then its field.
   */
  static List<List<AnnotatedElement>> recordWrites(final Class<?> type) {
    final var components = type.getRecordComponents();
    final var parameterTypes = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
    Parameter[] parameters = null;
    for (final var constructor : type.getDeclaredConstructors()) {
      if (Arrays.equals(constructor.getParameterTypes(), parameterTypes)) parameters = constructor.getParameters();
    }
    final var writes = new ArrayList<List<AnnotatedElement>>(components.length);
    for (var i = 0; i < components.length; i++) {
      final var members = new ArrayList<AnnotatedElement>(2);
      if (parameters != null) members.add(parameters[i]);
      final var field = field(type, components[i].getName());
      if (field != null) members.add(field);
      writes.add(members);
    }
    return writes;
  }

  /** The members the bean property {@code name} is written through: its setters, then its field. */
  static List<AnnotatedElement> beanWrites(final Class<?> type, final String name) {
    final var members = new ArrayList<AnnotatedElement>(Beans.propertySetters(type, name));
    final var field = field(type, name);
    if (field != null) members.add(field);
    return members;
  }

  /** The field {@code name} that {@code type} or a superclass declares, or null. */
  private static Field field(final Class<?> type, final String name) {
    for (var c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      for (final var field : c.getDeclaredFields()) if (field.getName().equals(name)) return field;
    }
    return null;
  }

  private static void refuse(
    final Class<?> type,
    final AnnotatedElement member,
    final String where,
    final String... annotations
  ) {
    for (final var annotation : annotations)
      if (find(member, annotation) != null) throw refusal(type, annotation, where);
  }

  private static IllegalArgumentException refusal(final Class<?> type, final String annotation, final String where) {
    return new IllegalArgumentException(
      PairingMessages.jsonAnnotationUnsupported(simpleName(annotation), where, type.getSimpleName())
    );
  }

  /** An annotation's class name without its package: {@code JsonFormat}. */
  private static String simpleName(final String annotation) {
    return annotation.substring(annotation.lastIndexOf('.') + 1);
  }

  /**
   * A constructor or method as a message names it: {@code Partner(String, int)}, {@code
   * of(String)}.
   */
  private static String signature(final Executable executable) {
    final var name =
      executable instanceof Constructor<?> ? executable.getDeclaringClass().getSimpleName() : executable.getName();
    return (
      name +
      Arrays.stream(executable.getParameterTypes())
        .map(Class::getSimpleName)
        .collect(Collectors.joining(", ", "(", ")"))
    );
  }

  /** The annotation of the class named {@code type} on {@code member}, or null. */
  private static Annotation find(final AnnotatedElement member, final String type) {
    for (final var annotation : member.getDeclaredAnnotations()) {
      if (annotation.annotationType().getName().equals(type)) return annotation;
    }
    return null;
  }

  /** The value of {@code annotation}'s attribute {@code name}. */
  private static Object attribute(final Annotation annotation, final String name) {
    try {
      return annotation.annotationType().getMethod(name).invoke(annotation);
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
        "TelescopeJson.fromJson: cannot read " + name + "() of @" + annotation.annotationType().getName(),
        e
      );
    }
  }
}
