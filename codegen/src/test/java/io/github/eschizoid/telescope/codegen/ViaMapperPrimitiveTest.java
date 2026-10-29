package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A class written by hand that a {@code via} row names is called through a null gate, because it
 * is user code with no guaranteed null tolerance. A primitive field has no null to guard against,
 * and {@code int == null} is not legal Java, so the gate has to be skipped there rather than
 * emitted and hoped over.
 *
 * <p>These compile through the full pipeline. The gate lands in a method body, which the
 * processing-only harness does not attribute, so an assertion made there would hold no matter what
 * was emitted, and a reference-typed fixture cannot reach the primitive's case at all.
 */
class ViaMapperPrimitiveTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  /**
   * {@code %1$sCarrier}, beside {@code %1$sSrc} and {@code %1$sDst}, whose {@code @Bridge} field
   * converts {@code qty}, read by {@code srcAccessor} and {@code dstAccessor}, through the class
   * {@code %1$sMap}: both ways through {@code Telescope.mapper}, or forward through the lenient
   * {@code Telescope.mapperForward}.
   */
  private static JavaFileObject carrier(
    final String prefix,
    final String srcAccessor,
    final String dstAccessor,
    final boolean lenient
  ) {
    return ProcessorHarness.source(
      PACKAGE + "." + prefix + "Carrier",
      (
        "package " +
        PACKAGE +
        ";\npublic final class %1$sCarrier {\n" +
        "  @io.github.eschizoid.telescope.annotations.Bridge\n" +
        "  public static final io.github.eschizoid.telescope.conversion." +
        (lenient ? "ForwardMapper" : "Mapper") +
        "<%1$sSrc, %1$sDst> MAPPER = io.github.eschizoid.telescope.Telescope." +
        (lenient ? "mapperForward" : "mapper") +
        "(%1$sSrc.class, %1$sDst.class," +
        " io.github.eschizoid.telescope.mapping.Mapping.via(%1$sSrc::" +
        srcAccessor +
        ", %1$sDst::" +
        dstAccessor +
        ", %1$sMap.class));\n}\n"
      ).formatted(prefix)
    );
  }

  private static JavaFileObject bridge(final String fromType, final String toType) {
    return ProcessorHarness.source(
      "demo.QtyBridge",
      """
      package demo;
      public final class QtyBridge {
        public static %s forward(final %s s) { return %s; }
        public static %s backward(final %s t) { return %s; }
      }
      """.formatted(
          toType,
          fromType,
          "int".equals(toType) ? "Integer.parseInt(s)" : "String.valueOf(s)",
          fromType,
          toType,
          "int".equals(fromType) ? "Integer.parseInt(t)" : "String.valueOf(t)"
        )
    );
  }

  private static JavaFileObject[] pair(final String srcQty, final String tgtQty) {
    return new JavaFileObject[] {
      ProcessorHarness.source(
        "demo.PSrc",
        """
        package demo;
        import static io.github.eschizoid.telescope.mapping.Mapping.via;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.conversion.Mapper;
        public record PSrc(String id, %s qty) {
          @Bridge
          public static final Mapper<PSrc, PDst> MAPPER = Telescope.mapper(PSrc.class, PDst.class,
            via(PSrc::qty, PDst::qty, QtyBridge.class));
        }
        """.formatted(srcQty)
      ),
      ProcessorHarness.source("demo.PDst", "package demo; public record PDst(String id, %s qty) {}".formatted(tgtQty)),
      bridge(srcQty, tgtQty),
    };
  }

  @Test
  @DisplayName("a primitive source field is handed to the user bridge without a null gate")
  void primitiveSourceCompiles() {
    final var compilation = compile(pair("int", "String"));

    assertTrue(compilation.success(), () -> "an int cannot be null-checked: " + compilation.errorMessages());
    final var generated = compilation.generated().get("demo.PSrcToPDstBridge");
    assertFalse(
      generated.contains("__fs_qty == null"),
      () -> "a primitive local has no null to test for; saw " + generated
    );
  }

  @Test
  @DisplayName("a primitive target field is handed to the user bridge without a null gate on backward")
  void primitiveTargetCompiles() {
    // The mirror. The forward direction reads a reference here, so only the backward local is
    // primitive — a fix applied to one direction alone would leave this one failing.
    final var compilation = compile(pair("String", "int"));

    assertTrue(compilation.success(), () -> "an int cannot be null-checked: " + compilation.errorMessages());
    final var generated = compilation.generated().get("demo.PSrcToPDstBridge");
    assertFalse(generated.contains("__bt_qty == null"), () -> "the backward local is primitive; saw " + generated);
  }

  @Test
  @DisplayName("a reference field keeps its gate, since a user bridge need not tolerate null")
  void referenceFieldKeepsTheGate() {
    // The control. Without it a fix that dropped the gate everywhere would pass the two above.
    final var compilation = compile(pair("String", "String"));

    assertTrue(compilation.success(), () -> "the reference pair still bridges: " + compilation.errorMessages());
    final var generated = compilation.generated().get("demo.PSrcToPDstBridge");
    assertTrue(generated.contains("__fs_qty == null"), () -> "a reference local keeps its gate; saw " + generated);
  }

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  /**
   * A {@code via} row on {@code qty} from a source record holding {@code srcType} into a target
   * holding an {@code int}, a record or a bean whose setter records what it is handed, through a
   * mapper declaring {@code forward}; each class named with {@code prefix}.
   */
  private static JavaFileObject[] intTarget(
    final String prefix,
    final String srcType,
    final boolean bean,
    final String forward
  ) {
    final var head = "package " + PACKAGE + ";\n";
    final var target = bean
      ? "public class %1$sDst {\n" +
        "  private int qty = -1;\n" +
        "  public %1$sDst() {}\n" +
        "  public int getQty() { return qty; }\n" +
        "  public void setQty(final int qty) { this.qty = qty; }\n}\n"
      : "public record %1$sDst(int qty) {}\n";
    return new JavaFileObject[] {
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Src",
        head +
          (
            "public record %1$sSrc(" +
            srcType +
            " qty) {}\n"
          ).formatted(prefix)
      ),
      ProcessorHarness.source(PACKAGE + "." + prefix + "Dst", head + target.formatted(prefix)),
      carrier(prefix, "qty", bean ? "getQty" : "qty", true),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Map",
        head +
          (
            "public class %1$sMap {\n" +
            forward +
            "  public static " +
            srcType +
            " backward(final int q) { return null; }\n}\n"
          ).formatted(prefix)
      ),
    };
  }

  /**
   * The {@code qty} the generated bridge among {@code classes} writes forward from a source holding
   * {@code value}.
   */
  private static Object forwardQty(final Map<String, Class<?>> classes, final String prefix, final Object value)
    throws ReflectiveOperationException {
    final var src = classes.get(PACKAGE + "." + prefix + "Src");
    final var source = src.getDeclaredConstructors()[0].newInstance(value);
    final var target = classes.get(PACKAGE + "." + prefix + "SrcTo" + prefix + "DstBridge").getMethod("forward", src).invoke(null, source);
    return target.getClass().getMethod(target.getClass().isRecord() ? "qty" : "getQty").invoke(target);
  }

  @Test
  @DisplayName(
    "a forward returning its own variable, handed an Integer, writes an int record component, and a null its default"
  )
  void inferredResultIntoAnIntComponent() throws ReflectiveOperationException {
    final var compilation = compile(
      intTarget("Zh", "Integer", false, "  public static <T> T forward(final T t) { return t; }\n")
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    assertEquals(4, forwardQty(classes, "Zh", 4));
    assertEquals(0, forwardQty(classes, "Zh", null));
  }

  @Test
  @DisplayName("a forward returning its own variable, handed an Integer, writes an int setter, and skips a null")
  void inferredResultIntoAnIntSetter() throws ReflectiveOperationException {
    final var compilation = compile(
      intTarget("Zs", "Integer", true, "  public static <T> T forward(final T t) { return t; }\n")
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    assertEquals(4, forwardQty(classes, "Zs", 4));
    assertEquals(-1, forwardQty(classes, "Zs", null));
  }

  @Test
  @DisplayName(
    "a forward returning a variable bounded by Number writes an int component after a check of the value's class"
  )
  void boundedResultIntoAnIntComponent() throws ReflectiveOperationException {
    final var compilation = compile(
      intTarget(
        "Ze",
        "String",
        false,
        "  @SuppressWarnings(\"unchecked\")\n" +
          "  public static <T extends Number> T forward(final String s) {" +
          " return (T) (s.length() > 3 ? (Number) Long.valueOf(9) : Integer.valueOf(s.length())); }\n"
      )
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    assertEquals(3, forwardQty(classes, "Ze", "abc"));
    final var refused = assertThrows(InvocationTargetException.class, () -> forwardQty(classes, "Ze", "abcd"));
    assertInstanceOf(ClassCastException.class, refused.getCause());
    assertTrue(refused.getCause().getMessage().contains("property 'qty'"), refused.getCause()::getMessage);
  }

  /**
   * The {@code qty} the generated bridge of a source record holding an {@code int} writes back from
   * a target record holding {@code value} as an {@code Integer}, through a mapper whose {@code
   * backward} is {@code backward}.
   */
  private static Object backwardQty(final String prefix, final String backward, final Integer value)
    throws ReflectiveOperationException {
    final var head = "package " + PACKAGE + ";\n";
    final var compilation = compile(
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Src",
        head +
          "public record %1$sSrc(int qty) {}\n".formatted(prefix)
      ),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Dst",
        head + "public record %1$sDst(Integer qty) {}\n".formatted(prefix)
      ),
      carrier(prefix, "qty", "qty", false),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Map",
        head +
          (
            "public class %1$sMap {\n" +
            "  public static <T> T forward(final T t) { return t; }\n" +
            backward +
            "}\n"
          ).formatted(prefix)
      )
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var dst = classes.get(PACKAGE + "." + prefix + "Dst");
    final var target = dst.getDeclaredConstructors()[0].newInstance(value);
    final var source = classes
      .get(PACKAGE + "." + prefix + "SrcTo" + prefix + "DstBridge")
      .getMethod("backward", dst)
      .invoke(null, target);
    return source.getClass().getMethod("qty").invoke(source);
  }

  @Test
  @DisplayName(
    "a backward returning a variable bounded by Number writes an int component after a check of the value's class"
  )
  void boundedBackwardIntoAnIntComponent() throws ReflectiveOperationException {
    assertEquals(
      6,
      backwardQty(
        "Ri",
        "  @SuppressWarnings(\"unchecked\")\n" +
          "  public static <T extends Number> T backward(final Integer i) { return (T) Integer.valueOf(i + 1); }\n",
        5
      )
    );
    final var refused = assertThrows(InvocationTargetException.class, () ->
      backwardQty(
        "Rh",
        "  @SuppressWarnings(\"unchecked\")\n" +
          "  public static <T extends Number> T backward(final Integer i) { return (T) Long.valueOf(3); }\n",
        5
      )
    );
    assertInstanceOf(ClassCastException.class, refused.getCause());
    assertTrue(refused.getCause().getMessage().contains("property 'qty'"), refused.getCause()::getMessage);
  }

  /**
   * A backward that returns its variable, bounded by Number, as a null for 1, a Long for 2 and
   * otherwise the value it is handed plus one.
   */
  private static final String PATCHED_BACKWARD =
    "  @SuppressWarnings(\"unchecked\")\n" +
    "  public static <T extends Number> T backward(final Integer i) {\n" +
    "    return (T) (i == 1 ? null : i == 2 ? (Number) Long.valueOf(i) : Integer.valueOf(i + 1));\n  }\n";

  /**
   * The classes of a {@code via} row's pair from a source holding an {@code int}, a record or a
   * bean written through its setter whose field starts at -1, to a target record holding an {@code
   * Integer}, through {@link #PATCHED_BACKWARD}; each class named with {@code prefix}.
   */
  private static Map<String, Class<?>> patchedPair(final String prefix, final boolean bean) {
    final var head = "package " + PACKAGE + ";\n";
    final var source = bean
      ? "public class %1$sSrc {\n" +
        "  private int qty = -1;\n" +
        "  public %1$sSrc() {}\n" +
        "  public int getQty() { return qty; }\n" +
        "  public void setQty(final int qty) { this.qty = qty; }\n}\n"
      : "public record %1$sSrc(int qty) {}\n";
    final var compilation = compile(
      ProcessorHarness.source(PACKAGE + "." + prefix + "Src", head + source.formatted(prefix)),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Dst",
        head + "public record %1$sDst(Integer qty) {}\n".formatted(prefix)
      ),
      carrier(prefix, bean ? "getQty" : "qty", "qty", false),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Map",
        head +
          (
            "public class %1$sMap {\n" +
            "  public static <T> T forward(final T t) { return t; }\n" +
            PATCHED_BACKWARD +
            "}\n"
          ).formatted(prefix)
      )
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    return compilation.define(MethodHandles.lookup());
  }

  /**
   * The {@code qty} the generated bridge among {@code classes} patches onto a source holding 9 from
   * a target holding {@code partial}.
   */
  private static Object patchedQty(final Map<String, Class<?>> classes, final String prefix, final Integer partial)
    throws ReflectiveOperationException {
    final var src = classes.get(PACKAGE + "." + prefix + "Src");
    final var dst = classes.get(PACKAGE + "." + prefix + "Dst");
    final Object base;
    if (src.isRecord()) {
      base = src.getConstructor(int.class).newInstance(9);
    } else {
      base = src.getConstructor().newInstance();
      src.getMethod("setQty", int.class).invoke(base, 9);
    }
    final var target = dst.getDeclaredConstructors()[0].newInstance(partial);
    final var patched = classes
      .get(PACKAGE + "." + prefix + "SrcTo" + prefix + "DstBridge")
      .getMethod("patch", src, dst)
      .invoke(null, base, target);
    return patched.getClass().getMethod(src.isRecord() ? "qty" : "getQty").invoke(patched);
  }

  @Test
  @DisplayName(
    "patch writes a backward's variable bounded by Number into an int component through the named check, a null its default"
  )
  void patchedBackwardIntoAnIntComponent() throws ReflectiveOperationException {
    final var classes = patchedPair("Pr", false);
    assertEquals(6, patchedQty(classes, "Pr", 5));
    assertEquals(9, patchedQty(classes, "Pr", null));
    assertEquals(0, patchedQty(classes, "Pr", 1));
    final var refused = assertThrows(InvocationTargetException.class, () -> patchedQty(classes, "Pr", 2));
    assertInstanceOf(ClassCastException.class, refused.getCause());
    assertTrue(refused.getCause().getMessage().contains("property 'qty'"), refused.getCause()::getMessage);
  }

  @Test
  @DisplayName(
    "patch writes a backward's variable bounded by Number into an int setter through the named check, skipping a null"
  )
  void patchedBackwardIntoAnIntSetter() throws ReflectiveOperationException {
    final var classes = patchedPair("Pb", true);
    assertEquals(6, patchedQty(classes, "Pb", 5));
    assertEquals(-1, patchedQty(classes, "Pb", 1));
    final var refused = assertThrows(InvocationTargetException.class, () -> patchedQty(classes, "Pb", 2));
    assertInstanceOf(ClassCastException.class, refused.getCause());
    assertTrue(refused.getCause().getMessage().contains("property 'qty'"), refused.getCause()::getMessage);
  }

  /**
   * How a source holding a primitive {@code qty} is built, which decides how its bridge writes it.
   * {@code %1$s} is the prefix and {@code %2$s} the primitive.
   */
  enum Member {
    RECORD("public record %1$sSrc(%2$s qty) {}\n"),
    CONSTRUCTOR(
      "public class %1$sSrc {\n" +
        "  private final %2$s qty;\n" +
        "  public %1$sSrc(final %2$s qty) { this.qty = qty; }\n" +
        "  public %2$s getQty() { return qty; }\n}\n"
    ),
    BUILDER(
      "public class %1$sSrc {\n" +
        "  private final %2$s qty;\n" +
        "  private %1$sSrc(final %2$s qty) { this.qty = qty; }\n" +
        "  public %2$s getQty() { return qty; }\n" +
        "  public static Builder builder() { return new Builder(); }\n" +
        "  public static final class Builder {\n" +
        "    private %2$s qty = -1;\n" +
        "    public Builder qty(final %2$s qty) { this.qty = qty; return this; }\n" +
        "    public %1$sSrc build() { return new %1$sSrc(qty); }\n  }\n}\n"
    ),
    SETTER(
      "public class %1$sSrc {\n" +
        "  private %2$s qty = -1;\n" +
        "  public %1$sSrc() {}\n" +
        "  public %2$s getQty() { return qty; }\n" +
        "  public void setQty(final %2$s qty) { this.qty = qty; }\n}\n"
    );

    private final String declaration;

    Member(final String declaration) {
      this.declaration = declaration;
    }
  }

  /**
   * A wrapper class and a primitive its value is written into: its own, or one it widens to. {@code
   * primitive} is the primitive's class, {@code narrowing} the cast from {@code int} to the
   * wrapper's own primitive.
   */
  record Wrapping(String wrapper, String primitive, Class<?> type, String narrowing) {}

  private static final List<Wrapping> WRAPPINGS = List.of(
    new Wrapping("Integer", "int", int.class, ""),
    new Wrapping("Integer", "long", long.class, ""),
    new Wrapping("Short", "int", int.class, "(short) ")
  );

  /** Every member kind with every wrapping. */
  static List<Arguments> memberWrappings() {
    final var all = new ArrayList<Arguments>();
    for (final var member : Member.values()) {
      for (final var wrapping : WRAPPINGS) all.add(Arguments.of(member, wrapping));
    }
    return all;
  }

  /**
   * The classes of a {@code via} row's pair from a source of {@code member} kind holding {@code
   * wrapping}'s primitive to a target record holding its wrapper, through a mapper whose
   * non-generic {@code backward} returns the wrapper: null for 1, and otherwise the value plus one.
   */
  private static Map<String, Class<?>> wrapperPair(final String prefix, final Member member, final Wrapping wrapping) {
    final var head = "package " + PACKAGE + ";\n";
    final var wrapper = wrapping.wrapper();
    final var compilation = compile(
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Src",
        head + member.declaration.formatted(prefix, wrapping.primitive())
      ),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Dst",
        head + "public record %1$sDst(%2$s qty) {}\n".formatted(prefix, wrapper)
      ),
      carrier(prefix, member == Member.RECORD ? "qty" : "getQty", "qty", false),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Map",
        head +
          (
            "public class %1$sMap {\n" +
            "  public static %2$s forward(final %3$s q) { return %4$s(int) q; }\n" +
            "  public static %2$s backward(final %2$s i) { return i == 1 ? null : %4$s(i + 1); }\n}\n"
          ).formatted(prefix, wrapper, wrapping.primitive(), wrapping.narrowing())
      )
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    return compilation.define(MethodHandles.lookup());
  }

  /** A source of {@code member} kind among {@code classes} holding {@code qty} as {@code type}. */
  private static Object wrapperSource(
    final Map<String, Class<?>> classes,
    final String prefix,
    final Member member,
    final Class<?> type,
    final int qty
  ) throws ReflectiveOperationException {
    final var src = classes.get(PACKAGE + "." + prefix + "Src");
    final Object value = type == long.class ? (Object) (long) qty : (Object) qty;
    return switch (member) {
      case RECORD, CONSTRUCTOR -> src.getConstructor(type).newInstance(value);
      case BUILDER -> {
        final var builder = src.getMethod("builder").invoke(null);
        builder.getClass().getMethod("qty", type).invoke(builder, value);
        yield builder.getClass().getMethod("build").invoke(builder);
      }
      case SETTER -> {
        final var bean = src.getConstructor().newInstance();
        src.getMethod("setQty", type).invoke(bean, value);
        yield bean;
      }
    };
  }

  /**
   * The {@code qty}, as a {@code long}, of what the generated bridge among {@code classes} writes
   * back from a target holding {@code partial}: by {@code backward}, or by {@code patch} onto a
   * source holding 9.
   */
  private static long wrapperBack(
    final Map<String, Class<?>> classes,
    final String prefix,
    final Member member,
    final Wrapping wrapping,
    final boolean patch,
    final Integer partial
  ) throws ReflectiveOperationException {
    final var src = classes.get(PACKAGE + "." + prefix + "Src");
    final var dst = classes.get(PACKAGE + "." + prefix + "Dst");
    final var bridge = classes.get(PACKAGE + "." + prefix + "SrcTo" + prefix + "DstBridge");
    final Object held =
      partial == null || wrapping.wrapper().equals("Integer") ? partial : (Object) partial.shortValue();
    final var target = dst.getDeclaredConstructors()[0].newInstance(held);
    final var written = patch
      ? bridge
          .getMethod("patch", src, dst)
          .invoke(null, wrapperSource(classes, prefix, member, wrapping.type(), 9), target)
      : bridge.getMethod("backward", dst).invoke(null, target);
    return (
      (Number) written.getClass().getMethod(member == Member.RECORD ? "qty" : "getQty").invoke(written)
    ).longValue();
  }

  @Test
  @DisplayName(
    "a wrapper result into its primitive is coalesced with no class check, and patch reads the base's primitive unboxed"
  )
  void wrapperResultIsCoalescedWithoutACheckOrABoxedBase() {
    // No value of a wrapper class fails a check of that class, and the base's own value is a
    // primitive already: boxing it to coalesce it would allocate on every write.
    final var head = "package " + PACKAGE + ";\n";
    final var compilation = compile(
      ProcessorHarness.source(
        PACKAGE + ".VmpCoSrc",
        head + "public record VmpCoSrc(int qty) {}\n"
      ),
      carrier("VmpCo", "qty", "qty", false),
      ProcessorHarness.source(PACKAGE + ".VmpCoDst", head + "public record VmpCoDst(Integer qty) {}\n"),
      ProcessorHarness.source(
        PACKAGE + ".VmpCoMap",
        head +
          "public class VmpCoMap {\n" +
          "  public static Integer forward(final int q) { return q; }\n" +
          "  public static Integer backward(final Integer i) { return i; }\n}\n"
      )
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var generated = compilation.generated().get(PACKAGE + ".VmpCoSrcToVmpCoDstBridge");
    final var backward = generated.substring(generated.indexOf(" backward(final "), generated.indexOf(" patch(final "));
    final var patch = generated.substring(generated.indexOf(" patch(final "), generated.indexOf("class Fn"));
    for (final var body : List.of(backward, patch)) {
      assertFalse(body.contains("FixedClass"), () -> "no check of a wrapper's class is owed; saw " + body);
      assertTrue(body.contains("__orElse("), () -> "a null result takes the default; saw " + body);
    }
    assertTrue(patch.contains(": base.qty()"), () -> "patch reads the base's primitive as it is; saw " + patch);
  }

  @ParameterizedTest(name = "{0}, {1}")
  @MethodSource("memberWrappings")
  @DisplayName(
    "a non-generic backward's null wrapper result into its primitive, or one it widens to, is the default, or skips a" +
      " setter, in backward and patch"
  )
  void wrapperResultIntoAPrimitive(final Member member, final Wrapping wrapping) throws ReflectiveOperationException {
    final var prefix = "VmpWn" + member.ordinal() + WRAPPINGS.indexOf(wrapping);
    final var classes = wrapperPair(prefix, member, wrapping);
    // A setter the null result skips leaves the value the bean starts with.
    final var skipped = member == Member.SETTER ? -1 : 0;
    assertEquals(6, wrapperBack(classes, prefix, member, wrapping, false, 5));
    assertEquals(skipped, wrapperBack(classes, prefix, member, wrapping, false, 1));
    assertEquals(6, wrapperBack(classes, prefix, member, wrapping, true, 5));
    assertEquals(skipped, wrapperBack(classes, prefix, member, wrapping, true, 1));
    assertEquals(9, wrapperBack(classes, prefix, member, wrapping, true, null));
  }

  @Test
  @DisplayName("a non-generic forward's null wrapper result into an int is the default for a record and skips a setter")
  void wrapperForwardIntoAnInt() throws ReflectiveOperationException {
    final var forward = "  public static Integer forward(final Integer t) { return t; }\n";
    final var record = compile(intTarget("VmpWf", "Integer", false, forward));
    assertTrue(record.success(), record::errorMessages);
    final var recordClasses = record.define(MethodHandles.lookup());
    assertEquals(4, forwardQty(recordClasses, "VmpWf", 4));
    assertEquals(0, forwardQty(recordClasses, "VmpWf", null));
    final var bean = compile(intTarget("VmpWg", "Integer", true, forward));
    assertTrue(bean.success(), bean::errorMessages);
    final var beanClasses = bean.define(MethodHandles.lookup());
    assertEquals(4, forwardQty(beanClasses, "VmpWg", 4));
    assertEquals(-1, forwardQty(beanClasses, "VmpWg", null));
  }
}
