package io.github.eschizoid.telescope.internal;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

/**
 * The parameter names of a constructor, read for discovery and never for dispatch.
 *
 * <p>javac keeps a parameter's name in the {@code MethodParameters} attribute only under {@code
 * -parameters}, which is what {@link java.lang.reflect.Parameter#isNamePresent} reports. It also
 * keeps it in the {@code LocalVariableTable} of the constructor's code under {@code -g}, the debug
 * level Gradle and Maven compile with by default, and reflection does not read that table. So when
 * reflection has no names, the class's own bytes are read once and the table supplies them.
 *
 * <p>A local variable table records slots, not parameters. Slot 0 of a constructor is {@code this};
 * each parameter then takes one slot, or two for a {@code long} or a {@code double}, in descriptor
 * order. The descriptor is the full one, so the enclosing instance an inner class's constructor
 * takes comes first, as it does in {@link Constructor#getParameterTypes}, and the names line up
 * with the parameters reflection reports.
 *
 * <p>There are no class bytes inside a native image and no table in a class compiled without {@code
 * -g}; either way the answer is that the names are unknown, which is the answer reflection gives
 * too.
 */
final class ParameterNames {

  private ParameterNames() {}

  /**
   * Per class, each constructor's descriptor mapped to its parameter-slot names. Read once per
   * class; empty when the class has no bytes to read or they cannot be parsed.
   */
  private static final ClassValue<Map<String, Map<Integer, String>>> SLOT_NAMES = new ClassValue<>() {
    @Override
    protected Map<String, Map<Integer, String>> computeValue(final Class<?> type) {
      return NativeImage.IN_IMAGE ? Map.of() : read(type);
    }
  };

  /**
   * The names of {@code ctor}'s parameters in parameter order, or {@code null} when they are not
   * known: reflection's names when the class kept them, else the constructor's local variable
   * table.
   */
  static String[] of(final Constructor<?> ctor) {
    final var parameters = ctor.getParameters();
    final var names = new String[parameters.length];
    var reflective = true;
    for (var i = 0; i < parameters.length && reflective; i++) {
      reflective = parameters[i].isNamePresent();
      names[i] = parameters[i].getName();
    }
    if (reflective) return names;
    final var types = ctor.getParameterTypes();
    final var slots = SLOT_NAMES.get(ctor.getDeclaringClass()).get(
      MethodType.methodType(void.class, types).toMethodDescriptorString()
    );
    return slots == null ? null : fromSlots(types, slots);
  }

  /**
   * The parameter names {@code slots} gives, in parameter order, or {@code null} when a parameter's
   * slot is unnamed. Slot 0 is the receiver, and a {@code long} or {@code double} takes two.
   */
  static String[] fromSlots(final Class<?>[] types, final Map<Integer, String> slots) {
    final var names = new String[types.length];
    var slot = 1;
    for (var i = 0; i < types.length; i++) {
      names[i] = slots.get(slot);
      if (names[i] == null) return null;
      slot += types[i] == long.class || types[i] == double.class ? 2 : 1;
    }
    return names;
  }

  private static Map<String, Map<Integer, String>> read(final Class<?> type) {
    if (type.isHidden() || type.isArray() || type.isPrimitive()) return Map.of();
    final var name = type.getName();
    try (final InputStream in = type.getResourceAsStream(name.substring(name.lastIndexOf('.') + 1) + ".class")) {
      return in == null ? Map.of() : constructorSlotNames(in.readAllBytes());
    } catch (final IOException | RuntimeException e) {
      return Map.of();
    }
  }

  /**
   * Each constructor's descriptor in {@code bytes} mapped to the names its local variable table
   * gives the slots live from the first instruction, which is where a parameter's entry starts. A
   * constructor with no table maps to nothing.
   */
  static Map<String, Map<Integer, String>> constructorSlotNames(final byte[] bytes) throws IOException {
    final var in = new DataInputStream(new ByteArrayInputStream(bytes));
    if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
    in.skipNBytes(4);
    final var utf8 = constantPoolUtf8(in);
    in.skipNBytes(6);
    in.skipNBytes(2L * in.readUnsignedShort());
    final var fields = in.readUnsignedShort();
    for (var i = 0; i < fields; i++) {
      in.skipNBytes(6);
      skipAttributes(in);
    }
    final var out = new HashMap<String, Map<Integer, String>>();
    final var methods = in.readUnsignedShort();
    for (var i = 0; i < methods; i++) {
      in.skipNBytes(2);
      final var methodName = utf8[in.readUnsignedShort()];
      final var descriptor = utf8[in.readUnsignedShort()];
      if (!"<init>".equals(methodName)) {
        skipAttributes(in);
        continue;
      }
      final var slots = new HashMap<Integer, String>();
      final var attributes = in.readUnsignedShort();
      for (var a = 0; a < attributes; a++) {
        final var attribute = utf8[in.readUnsignedShort()];
        final var length = in.readInt() & 0xFFFFFFFFL;
        if ("Code".equals(attribute)) {
          readCodeSlots(in, utf8, slots);
        } else {
          in.skipNBytes(length);
        }
      }
      if (!slots.isEmpty()) out.put(descriptor, Map.copyOf(slots));
    }
    return Map.copyOf(out);
  }

  /** The constant pool's UTF-8 entries by index; every other entry is skipped and left null. */
  private static String[] constantPoolUtf8(final DataInputStream in) throws IOException {
    final var count = in.readUnsignedShort();
    final var utf8 = new String[count];
    for (var i = 1; i < count; i++) {
      final var tag = in.readUnsignedByte();
      switch (tag) {
        case 1 -> utf8[i] = in.readUTF();
        case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
        case 15 -> in.skipNBytes(3);
        case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
        case 5, 6 -> {
          // A long or a double takes two entries of the pool, and the second is never written.
          in.skipNBytes(8);
          i++;
        }
        default -> throw new IOException("unknown constant pool tag " + tag);
      }
    }
    return utf8;
  }

  /** Reads a {@code Code} attribute's body, adding its local variable table's parameter entries. */
  private static void readCodeSlots(final DataInputStream in, final String[] utf8, final Map<Integer, String> slots)
    throws IOException {
    in.skipNBytes(4);
    in.skipNBytes(in.readInt() & 0xFFFFFFFFL);
    in.skipNBytes(8L * in.readUnsignedShort());
    final var attributes = in.readUnsignedShort();
    for (var a = 0; a < attributes; a++) {
      final var attribute = utf8[in.readUnsignedShort()];
      final var length = in.readInt() & 0xFFFFFFFFL;
      if (!"LocalVariableTable".equals(attribute)) {
        in.skipNBytes(length);
        continue;
      }
      final var entries = in.readUnsignedShort();
      for (var e = 0; e < entries; e++) {
        final var startPc = in.readUnsignedShort();
        in.skipNBytes(2);
        final var variable = utf8[in.readUnsignedShort()];
        in.skipNBytes(2);
        final var slot = in.readUnsignedShort();
        if (startPc == 0 && slot > 0) slots.putIfAbsent(slot, variable);
      }
    }
  }

  private static void skipAttributes(final DataInputStream in) throws IOException {
    final var attributes = in.readUnsignedShort();
    for (var a = 0; a < attributes; a++) {
      in.skipNBytes(2);
      in.skipNBytes(in.readInt() & 0xFFFFFFFFL);
    }
  }
}
