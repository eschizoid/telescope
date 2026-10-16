package io.github.eschizoid.telescope.internal;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.HashSet;
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
 * with the parameters reflection reports. A parameter's entry is live from the first instruction,
 * so only entries starting there are read; a later entry that reuses a parameter's slot is a local
 * declared in the body. javac does not reuse a parameter's slot, but the table permits it.
 *
 * <p>The bytes come from {@link Class#getResourceAsStream}, which a loader is free to answer with
 * bytes it did not define the class from. So the names are taken only when the bytes name the class
 * itself, the constructor's descriptor is found in them, every parameter's entry carries that
 * parameter's type, no slot has two entries live from the first instruction, every name is a Java
 * identifier, and no two parameters share a name. What these checks cannot catch is another version
 * of the same class whose constructor has the same descriptor and the same parameter types under
 * different names: those names are taken as they stand.
 *
 * <p>There are no class bytes inside a native image and no table in a class compiled without {@code
 * -g}; either way the answer is that the names are unknown, which is the answer reflection gives
 * too.
 */
final class ParameterNames {

  private ParameterNames() {}

  /** One local variable table entry live from the first instruction: its name and type. */
  record Local(String name, String descriptor) {}

  /**
   * Per class, each constructor's descriptor mapped to its slots' entries. Read once per class;
   * empty when the class has no bytes to read, they are not its own, or they cannot be parsed.
   */
  private static final ClassValue<Map<String, Map<Integer, Local>>> SLOT_NAMES = new ClassValue<>() {
    @Override
    protected Map<String, Map<Integer, Local>> computeValue(final Class<?> type) {
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
   * slot has no entry, an entry of another type, a name that is not a Java identifier, or a name an
   * earlier parameter took. Slot 0 is the receiver, and a {@code long} or {@code double} takes two.
   */
  static String[] fromSlots(final Class<?>[] types, final Map<Integer, Local> slots) {
    final var names = new String[types.length];
    final var seen = new HashSet<String>();
    var slot = 1;
    for (var i = 0; i < types.length; i++) {
      final var local = slots.get(slot);
      if (local == null || !local.descriptor().equals(types[i].descriptorString())) return null;
      if (!isIdentifier(local.name()) || !seen.add(local.name())) return null;
      names[i] = local.name();
      slot += types[i] == long.class || types[i] == double.class ? 2 : 1;
    }
    return names;
  }

  /**
   * Whether {@code name} has the shape of a Java identifier, which every source parameter name has.
   */
  private static boolean isIdentifier(final String name) {
    if (name.isEmpty() || !Character.isJavaIdentifierStart(name.codePointAt(0))) return false;
    return name.codePoints().skip(1).allMatch(Character::isJavaIdentifierPart);
  }

  private static Map<String, Map<Integer, Local>> read(final Class<?> type) {
    if (type.isHidden() || type.isArray() || type.isPrimitive()) return Map.of();
    final var name = type.getName();
    try (final InputStream in = type.getResourceAsStream(name.substring(name.lastIndexOf('.') + 1) + ".class")) {
      return in == null ? Map.of() : constructorSlots(in.readAllBytes(), name);
    } catch (final IOException | RuntimeException e) {
      return Map.of();
    }
  }

  /**
   * Each constructor's descriptor in {@code bytes} mapped to the entries its local variable table
   * gives the slots live from the first instruction, which is where a parameter's entry starts.
   * Empty when the bytes are not those of {@code className}. A constructor with no table, or with
   * two entries for one slot live from the first instruction, maps to nothing.
   */
  static Map<String, Map<Integer, Local>> constructorSlots(final byte[] bytes, final String className)
    throws IOException {
    final var in = new DataInputStream(new ByteArrayInputStream(bytes));
    if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
    in.skipNBytes(4);
    final var pool = ConstantPool.read(in);
    in.skipNBytes(2);
    final var thisClass = pool.className(in.readUnsignedShort());
    if (thisClass == null || !thisClass.replace('/', '.').equals(className)) return Map.of();
    in.skipNBytes(2);
    in.skipNBytes(2L * in.readUnsignedShort());
    final var fields = in.readUnsignedShort();
    for (var i = 0; i < fields; i++) {
      in.skipNBytes(6);
      skipAttributes(in);
    }
    final var out = new HashMap<String, Map<Integer, Local>>();
    final var methods = in.readUnsignedShort();
    for (var i = 0; i < methods; i++) {
      in.skipNBytes(2);
      final var methodName = pool.utf8(in.readUnsignedShort());
      final var descriptor = pool.utf8(in.readUnsignedShort());
      if (!"<init>".equals(methodName)) {
        skipAttributes(in);
        continue;
      }
      final var slots = new HashMap<Integer, Local>();
      var clean = true;
      final var attributes = in.readUnsignedShort();
      for (var a = 0; a < attributes; a++) {
        final var attribute = pool.utf8(in.readUnsignedShort());
        final var body = readBody(in);
        if ("Code".equals(attribute)) clean &= codeSlots(body, pool, slots);
      }
      if (clean && !slots.isEmpty()) out.put(descriptor, Map.copyOf(slots));
    }
    return Map.copyOf(out);
  }

  /**
   * Adds the entries a {@code Code} attribute's local variable table makes live from the first
   * instruction. False when two of them claim one slot. The body is read within its declared
   * length, so a malformed attribute contributes nothing and leaves the rest of the class readable.
   */
  private static boolean codeSlots(final byte[] code, final ConstantPool pool, final Map<Integer, Local> slots) {
    try {
      final var in = new DataInputStream(new ByteArrayInputStream(code));
      in.skipNBytes(4);
      in.skipNBytes(in.readInt() & 0xFFFFFFFFL);
      in.skipNBytes(8L * in.readUnsignedShort());
      final var attributes = in.readUnsignedShort();
      for (var a = 0; a < attributes; a++) {
        final var attribute = pool.utf8(in.readUnsignedShort());
        final var body = readBody(in);
        if (!"LocalVariableTable".equals(attribute)) continue;
        final var table = new DataInputStream(new ByteArrayInputStream(body));
        final var entries = table.readUnsignedShort();
        for (var e = 0; e < entries; e++) {
          final var startPc = table.readUnsignedShort();
          table.skipNBytes(2);
          final var local = new Local(pool.utf8(table.readUnsignedShort()), pool.utf8(table.readUnsignedShort()));
          final var slot = table.readUnsignedShort();
          if (startPc != 0 || slot == 0) continue;
          if (local.name() == null || local.descriptor() == null || slots.putIfAbsent(slot, local) != null) {
            return false;
          }
        }
      }
      return true;
    } catch (final IOException e) {
      return false;
    }
  }

  /** An attribute's body, read as its declared length says; fails when the file ends first. */
  private static byte[] readBody(final DataInputStream in) throws IOException {
    final var length = in.readInt() & 0xFFFFFFFFL;
    if (length > Integer.MAX_VALUE) throw new IOException("attribute too long");
    final var body = in.readNBytes((int) length);
    if (body.length != length) throw new IOException("attribute runs past the end of the file");
    return body;
  }

  private static void skipAttributes(final DataInputStream in) throws IOException {
    final var attributes = in.readUnsignedShort();
    for (var a = 0; a < attributes; a++) {
      in.skipNBytes(2);
      in.skipNBytes(in.readInt() & 0xFFFFFFFFL);
    }
  }

  /** The constant pool's UTF-8 entries and the name each class entry points at, by index. */
  private record ConstantPool(String[] utf8, int[] classNames) {
    static ConstantPool read(final DataInputStream in) throws IOException {
      final var count = in.readUnsignedShort();
      final var utf8 = new String[count];
      final var classNames = new int[count];
      for (var i = 1; i < count; i++) {
        final var tag = in.readUnsignedByte();
        switch (tag) {
          case 1 -> utf8[i] = in.readUTF();
          case 7 -> classNames[i] = in.readUnsignedShort();
          case 8, 16, 19, 20 -> in.skipNBytes(2);
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
      return new ConstantPool(utf8, classNames);
    }

    String utf8(final int index) {
      return index > 0 && index < utf8.length ? utf8[index] : null;
    }

    String className(final int index) {
      return index > 0 && index < classNames.length ? utf8(classNames[index]) : null;
    }
  }
}
