package io.github.eschizoid.telescope.internal.pairing;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * A value type a built-in conversion reads or writes: a number, a boolean, a {@code String}, or any
 * enum. A primitive and its wrapper are one scalar, since a conversion treats them alike apart from
 * what a null becomes, which the side's own type decides.
 *
 * <p>{@link PairingRules#scalarOf} names the scalar of a type, and {@link PairingRules#decidePair}
 * decides which pairs of scalars convert without a row.
 */
public enum Scalar {
  BYTE("byte", "java.lang.Byte"),
  SHORT("short", "java.lang.Short"),
  CHAR("char", "java.lang.Character"),
  INT("int", "java.lang.Integer"),
  LONG("long", "java.lang.Long"),
  FLOAT("float", "java.lang.Float"),
  DOUBLE("double", "java.lang.Double"),
  BOOLEAN("boolean", "java.lang.Boolean"),
  BIG_INTEGER(null, "java.math.BigInteger"),
  BIG_DECIMAL(null, "java.math.BigDecimal"),
  STRING(null, "java.lang.String"),
  ENUM(null, null);

  private final String primitive;
  private final String className;

  Scalar(final String primitive, final String className) {
    this.primitive = primitive;
    this.className = className;
  }

  /** The primitive type's name, or null for a scalar that has none. */
  public String primitive() {
    return primitive;
  }

  /**
   * The binary name of the class that holds the scalar: the wrapper of a primitive, or the class
   * itself. Null for {@link #ENUM}, which is every enum.
   */
  public String className() {
    return className;
  }

  /** Whether the scalar is a number a widening conversion can read: every numeric type and char. */
  public boolean widens() {
    return WIDENS_TO.containsKey(this);
  }

  /**
   * Whether every value of this scalar is a value of {@code wider}, read by Java's widening
   * primitive conversions, which take {@code int} and {@code long} to {@code float} and {@code
   * double} at the nearest representable value, together with the integral types and {@code
   * BigInteger} into the two arbitrary-precision classes, which hold each of their values exactly.
   * A {@code float} or {@code double} has no {@code BigDecimal} for NaN or an infinity, so neither
   * widens to one.
   */
  public boolean widensTo(final Scalar wider) {
    return WIDENS_TO.getOrDefault(this, Set.of()).contains(wider);
  }

  /** The scalar named by a primitive type's name, or null. */
  static Scalar ofPrimitive(final String name) {
    return BY_PRIMITIVE.get(name);
  }

  /** The scalar named by a class's binary name, or null. */
  static Scalar ofClass(final String name) {
    return BY_CLASS.get(name);
  }

  private static final Map<String, Scalar> BY_PRIMITIVE = Map.of(
    "byte",
    BYTE,
    "short",
    SHORT,
    "char",
    CHAR,
    "int",
    INT,
    "long",
    LONG,
    "float",
    FLOAT,
    "double",
    DOUBLE,
    "boolean",
    BOOLEAN
  );

  private static final Map<String, Scalar> BY_CLASS = Map.ofEntries(
    Map.entry("java.lang.Byte", BYTE),
    Map.entry("java.lang.Short", SHORT),
    Map.entry("java.lang.Character", CHAR),
    Map.entry("java.lang.Integer", INT),
    Map.entry("java.lang.Long", LONG),
    Map.entry("java.lang.Float", FLOAT),
    Map.entry("java.lang.Double", DOUBLE),
    Map.entry("java.lang.Boolean", BOOLEAN),
    Map.entry("java.math.BigInteger", BIG_INTEGER),
    Map.entry("java.math.BigDecimal", BIG_DECIMAL),
    Map.entry("java.lang.String", STRING)
  );

  private static final Map<Scalar, Set<Scalar>> WIDENS_TO = Map.of(
    BYTE,
    EnumSet.of(SHORT, INT, LONG, FLOAT, DOUBLE, BIG_INTEGER, BIG_DECIMAL),
    SHORT,
    EnumSet.of(INT, LONG, FLOAT, DOUBLE, BIG_INTEGER, BIG_DECIMAL),
    CHAR,
    EnumSet.of(INT, LONG, FLOAT, DOUBLE),
    INT,
    EnumSet.of(LONG, FLOAT, DOUBLE, BIG_INTEGER, BIG_DECIMAL),
    LONG,
    EnumSet.of(FLOAT, DOUBLE, BIG_INTEGER, BIG_DECIMAL),
    FLOAT,
    EnumSet.of(DOUBLE),
    DOUBLE,
    EnumSet.noneOf(Scalar.class),
    BIG_INTEGER,
    EnumSet.of(BIG_DECIMAL),
    BIG_DECIMAL,
    EnumSet.noneOf(Scalar.class)
  );
}
