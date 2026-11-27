package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.internal.NativeImage;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The edit mappers static fields declare, which an edit mapper converts a pair it writes through
 * where no row names a conversion. A pair converts through the edit mapper of its own declared in
 * the class whose static initialiser builds the owner's mapper, or in the pair's source or target
 * class; one declared anywhere else is named in a {@code via} row. Which pairs a class declares one
 * for is read from its fields' declared types, so the order the fields come in does not matter, and
 * the mapper is read from the field the first time a value of the pair converts, once the class has
 * initialised.
 */
final class EditRegistry {

  private EditRegistry() {}

  /** A static field of {@code declaring} whose type is {@code EditMapper<source, target>}. */
  record Declared(Class<?> declaring, Field field, Class<?> source, Class<?> target) {
    /** The field as {@code Class.FIELD}, as a refusal and {@code explain()} name it. */
    String name() {
      return declaring.getSimpleName() + "." + field.getName();
    }
  }

  private static final ClassValue<List<Declared>> DECLARED = new ClassValue<>() {
    @Override
    protected List<Declared> computeValue(final Class<?> type) {
      return scan(type);
    }
  };

  /** The edit mappers {@code declaring}'s static fields declare, in declaration order. */
  private static List<Declared> scan(final Class<?> declaring) {
    final var found = new ArrayList<Declared>();
    if (declaring.isPrimitive() || declaring.isArray() || declaring.getName().startsWith("java.")) return found;
    for (final var field : declaring.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) || field.getType() != EditMapper.class) continue;
      if (!(field.getGenericType() instanceof ParameterizedType type)) continue;
      final var arguments = type.getActualTypeArguments();
      if (!(arguments[0] instanceof Class<?> source) || !(arguments[1] instanceof Class<?> target)) continue;
      found.add(new Declared(declaring, field, source, target));
    }
    return List.copyOf(found);
  }

  /**
   * Refuses {@code owner}, the class whose static initialiser builds an edit mapper, when it lists
   * no field at all: inside a native image that is a class whose fields the image was not told to
   * keep, and reading none would convert by name a pair the JVM converts through its own mapper.
   */
  static void refuseUnlistedFields(final Class<?> owner) {
    if (NativeImage.IN_IMAGE && owner != null && owner.getDeclaredFields().length == 0) {
      throw new IllegalStateException(PairingMessages.editFieldsNotRegistered(owner.getName()));
    }
  }

  /**
   * The fields declaring an edit mapper of the pair of {@code source} and {@code target} that a
   * mapper {@code owner} builds converts the pair through: those of {@code owner}, where it is
   * known, and those of the pair's own two classes.
   */
  static List<Declared> forPair(final Class<?> owner, final Class<?> source, final Class<?> target) {
    final var classes = new LinkedHashSet<Class<?>>();
    if (owner != null) classes.add(owner);
    classes.add(source);
    classes.add(target);
    final var found = new ArrayList<Declared>();
    for (final var type : classes) {
      for (final var declared : DECLARED.get(type)) {
        if (declared.source() == source && declared.target() == target) found.add(declared);
      }
    }
    return found;
  }

  /**
   * The edit mapper {@code declared} holds.
   *
   * @throws IllegalStateException naming the field when it holds none yet, as when a mapper of its
   *     class converts a value while the class is still initialising, or when it cannot be read
   */
  static EditMapper<?, ?> of(final Declared declared) {
    final var field = declared.field();
    final Object value;
    try {
      if (!Modifier.isPublic(field.getModifiers()) || !Modifier.isPublic(field.getDeclaringClass().getModifiers())) {
        field.trySetAccessible();
      }
      value = field.get(null);
    } catch (final IllegalAccessException | RuntimeException e) {
      throw new IllegalStateException(PairingMessages.editElementMapperUnreadable(declared.name()), e);
    }
    if (!(value instanceof EditMapper<?, ?> mapper)) {
      throw new IllegalStateException(PairingMessages.editElementMapperNotBuilt(declared.name()));
    }
    return mapper;
  }
}
