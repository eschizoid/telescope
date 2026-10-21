package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A user-supplied bridge is called through a null gate, because it is user code with no guaranteed
 * null tolerance. A primitive field has no null to guard against, and {@code int == null} is not
 * legal Java, so the gate has to be skipped there rather than emitted and hoped over.
 *
 * <p>These compile through the full pipeline. The gate lands in a method body, which the
 * processing-only harness does not attribute, so an assertion made there would hold no matter what
 * was emitted — which is why every existing {@code @ViaMapper} fixture, all of them
 * reference-typed, could not have caught this.
 */
class ViaMapperPrimitiveTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
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
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.ViaMapper;
        @Bridge(value = demo.PDst.class, viaMappers = {
          @ViaMapper(field = "qty", using = demo.QtyBridge.class)
        })
        public record PSrc(String id, %s qty) {}
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
    final var generated = compilation.generated().get("demo.PSrcBridge");
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
    final var generated = compilation.generated().get("demo.PSrcBridge");
    assertFalse(generated.contains("__bt_qty == null"), () -> "the backward local is primitive; saw " + generated);
  }

  @Test
  @DisplayName("a reference field keeps its gate, since a user bridge need not tolerate null")
  void referenceFieldKeepsTheGate() {
    // The control. Without it a fix that dropped the gate everywhere would pass the two above.
    final var compilation = compile(pair("String", "String"));

    assertTrue(compilation.success(), () -> "the reference pair still bridges: " + compilation.errorMessages());
    final var generated = compilation.generated().get("demo.PSrcBridge");
    assertTrue(generated.contains("__fs_qty == null"), () -> "a reference local keeps its gate; saw " + generated);
  }

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  /**
   * A {@code @ViaMapper} on {@code qty} from a source record holding {@code srcType} into a target
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
            "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sDst.class, lenient = true, viaMappers =" +
            " @io.github.eschizoid.telescope.annotations.ViaMapper(field = \"qty\", using = %1$sMap.class))\n" +
            "public record %1$sSrc(" +
            srcType +
            " qty) {}\n"
          ).formatted(prefix)
      ),
      ProcessorHarness.source(PACKAGE + "." + prefix + "Dst", head + target.formatted(prefix)),
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
    final var target = classes.get(PACKAGE + "." + prefix + "SrcBridge").getMethod("forward", src).invoke(null, source);
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
  }
}
