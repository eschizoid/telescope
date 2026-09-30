package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One input through both paths, for every way a bean target can be rebuilt.
 *
 * <p>The container grid pairs two records in every cell, so nothing in it reaches the bean half of
 * either path. The two halves enumerate write strategies separately — the runtime's {@code
 * BeanWriter} permits four and the processor's rebuild kinds name three of them — and neither table
 * knows what the other holds, which is the shape every divergence closed so far has had.
 *
 * <p>A cell agrees when both paths produce the same rendering, or when both refuse. A disagreement
 * is a defect unless {@link #KNOWN_DIVERGENCES} carries it, and a mutual refusal owes an entry in
 * {@link #KNOWN_REFUSALS}, because two refusals agree while measuring nothing.
 *
 * <p>Sources compile without {@code -parameters}, which is what an ordinary build passes. The flag
 * changes one route's outcome, and {@link #theConstructorRouteAgreesWhenParameterNamesArePresent}
 * pins that rather than hiding it behind a compile option no adopter sets by default.
 */
class BeanRebuildCorpusTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  /** A way a bean target offers to be rebuilt, written as the body of the target class. */
  private record Route(String name, String body) {}

  /**
   * One route per strategy either side names. {@code %1$s} is the cell's prefix and {@code %2$s}
   * the property type, so a route is written once and holds for every property shape.
   */
  private static final List<Route> ROUTES = List.of(
    new Route(
      "setters",
      "private %2$s p;\n  public %2$s getP() { return p; }\n  public void setP(%2$s p) { this.p = p; }\n" +
        "  public %1$sTgt() {}\n"
    ),
    new Route(
      "ctor",
      "private final %2$s p;\n  public %1$sTgt(%2$s p) { this.p = p; }\n  public %2$s getP() { return p; }\n"
    ),
    new Route(
      "builder",
      "private final %2$s p;\n  private %1$sTgt(%2$s p) { this.p = p; }\n  public %2$s getP() { return p; }\n" +
        "  public static Builder builder() { return new Builder(); }\n" +
        "  public static final class Builder {\n    private %2$s p;\n" +
        "    public Builder p(%2$s p) { this.p = p; return this; }\n" +
        "    public %1$sTgt build() { return new %1$sTgt(p); }\n  }\n"
    ),
    new Route("fields", "private %2$s p;\n  public %2$s getP() { return p; }\n  public %1$sTgt() {}\n")
  );

  /** The property a cell carries: its type on each side, and the rendering both paths owe. */
  private record Shape(String name, String srcType, String tgtType, String rendered) {}

  private static final List<Shape> SHAPES = List.of(
    new Shape("scalar", "java.lang.String", "java.lang.String", "hello"),
    new Shape("record", "%1$sLeaf", "%1$sLeafDto", "%1$sLeafDto[v=x]"),
    new Shape("list", "java.util.List<%1$sLeaf>", "java.util.List<%1$sLeafDto>", "[%1$sLeafDto[v=x]]")
  );

  /** Which paths converted, so a recorded divergence pins its shape rather than its text. */
  private record Verdict(boolean generated, boolean reflective) {}

  /**
   * Cells whose two paths differ, recorded by what each does.
   *
   * <p>An entry keeps a cell from failing and nothing else: a cell that starts differing without
   * one fails, and an entry whose cell has stopped differing fails too.
   */
  private static final Map<String, Verdict> KNOWN_DIVERGENCES = Map.of(
    // The processor has no field-writing rebuild, so a target offering only fields falls to
    // no
    // strategy at all; the runtime's FieldsWriter writes the field directly.
    "fields/scalar",
    new Verdict(false, true),
    "fields/record",
    new Verdict(false, true),
    "fields/list",
    new Verdict(false, true),
    // The runtime matches constructor arguments by parameter name, which javac keeps only
    // under
    // -parameters; the processor reads them from the compilation unit and needs no flag.
    "ctor/scalar",
    new Verdict(true, false),
    "ctor/record",
    new Verdict(true, false),
    "ctor/list",
    new Verdict(true, false)
  );

  /** Cells both paths refuse, each naming a fragment of what each says. */
  private record Refusal(String generatedSays, String reflectiveSays) {}

  private static final Map<String, Refusal> KNOWN_REFUSALS = Map.of();

  @Test
  @DisplayName("every bean rebuild route is driven the same way by both paths")
  void bothPathsAgreeOnEveryRoute() {
    final var failures = new ArrayList<String>();
    final var checked = new LinkedHashSet<String>();
    final var diverged = new LinkedHashSet<String>();
    final var refused = new LinkedHashSet<String>();
    var index = 0;

    for (final var route : ROUTES) {
      for (final var shape : SHAPES) {
        final var cell = route.name() + "/" + shape.name();
        final var prefix = "Br" + index++;
        checked.add(cell);
        final var outcomes = run(prefix, route, shape, List.of());
        final var generated = outcomes.get(0);
        final var reflective = outcomes.get(1);

        if (!generated.equals(reflective)) {
          diverged.add(cell);
          final var verdict = new Verdict(generated.converted(), reflective.converted());
          if (!verdict.equals(KNOWN_DIVERGENCES.get(cell))) {
            failures.add(cell + ": generated " + generated + ", reflective " + reflective);
          }
          continue;
        }
        if (!generated.converted()) {
          refused.add(cell);
          final var recorded = KNOWN_REFUSALS.get(cell);
          if (recorded == null) {
            failures.add(cell + ": both paths refused, and no reason is recorded — " + generated);
          } else if (
            !generated.text().contains(recorded.generatedSays()) ||
            !reflective.text().contains(recorded.reflectiveSays())
          ) {
            failures.add(cell + ": refused for a reason the register does not name — " + generated);
          }
          continue;
        }
        final var want = shape.rendered().formatted(prefix);
        if (!want.equals(generated.text())) {
          failures.add(cell + ": both paths produced " + generated.text() + ", expected " + want);
        }
      }
    }

    assertTrue(checked.size() == ROUTES.size() * SHAPES.size(), () -> "every cell should be reached, saw " + checked);
    assertTrue(failures.isEmpty(), () -> failures.size() + " cell(s) failed:\n  " + String.join("\n  ", failures));
    final var stale = new LinkedHashSet<>(KNOWN_DIVERGENCES.keySet());
    stale.removeAll(diverged);
    assertTrue(stale.isEmpty(), () -> "recorded as diverging, but not observed to:\n  " + stale);
    final var staleRefusals = new LinkedHashSet<>(KNOWN_REFUSALS.keySet());
    staleRefusals.removeAll(refused);
    assertTrue(staleRefusals.isEmpty(), () -> "recorded as refused, but not observed to be:\n  " + staleRefusals);
  }

  @Test
  @DisplayName("the constructor route agrees once the source carries parameter names")
  void theConstructorRouteAgreesWhenParameterNamesArePresent() {
    // The register above records this route diverging, which is true of an ordinary build. The flag
    // is the whole of the difference, so the same cell is run again with it to say so — and to fail
    // if the runtime ever stops reading names it was given.
    final var ctor = ROUTES.stream()
      .filter(r -> r.name().equals("ctor"))
      .findFirst()
      .orElseThrow();
    final var outcomes = run("BrParam", ctor, SHAPES.getFirst(), List.of("-parameters"));

    assertTrue(outcomes.get(0).converted(), () -> "the generated path should convert: " + outcomes.get(0));
    assertTrue(outcomes.get(1).converted(), () -> "the reflective path should convert: " + outcomes.get(1));
    assertTrue(
      "hello".equals(outcomes.get(0).text()) && "hello".equals(outcomes.get(1).text()),
      () -> "both should carry the value: " + outcomes
    );
  }

  /** What one path did: the rendering it produced, or the refusal it made instead. */
  private record Outcome(String text, boolean converted) {
    @Override
    public String toString() {
      return converted ? text : "refused(" + text + ")";
    }

    @Override
    public boolean equals(final Object other) {
      // Two refusals agree without their messages matching, because the two paths word their own.
      return other instanceof Outcome o && (converted == o.converted) && (!converted || text.equals(o.text));
    }

    @Override
    public int hashCode() {
      return converted ? text.hashCode() : 0;
    }
  }

  /** Both paths' outcomes for one cell, generated first. */
  private static List<Outcome> run(
    final String prefix,
    final Route route,
    final Shape shape,
    final List<String> options
  ) {
    final var sources = sources(prefix, route, shape);
    final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), options, sources);
    final var plain = ProcessorHarness.compileFully(List.of(), options, sources);
    assertTrue(plain.success(), () -> prefix + " should compile without the processor: " + plain.errorMessages());
    try {
      final var classes = plain.define(MethodHandles.lookup());
      final var src = classes.get(PACKAGE + "." + prefix + "Src");
      final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
      final var getP = tgt.getMethod("getP");
      final var source = src.getConstructor().newInstance();
      src.getMethod("setP", src.getMethod("getP").getReturnType()).invoke(source, property(classes, prefix, shape));

      final var forward = processed.success() ? bridgeOf(processed, plain, prefix).getMethod("forward", src) : null;
      final var generated =
        forward == null
          ? new Outcome(processed.errorMessages().strip(), false)
          : attempt(() -> getP.invoke(forward.invoke(null, source)));
      final var reflective = attempt(() -> getP.invoke(Telescope.mapper(cast(src), cast(tgt)).forward(source)));
      return List.of(generated, reflective);
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(prefix + " could not be built", e);
    }
  }

  private static Object property(final Map<String, Class<?>> classes, final String prefix, final Shape shape)
    throws ReflectiveOperationException {
    if (shape.name().equals("scalar")) return "hello";
    final var leaf = classes.get(PACKAGE + "." + prefix + "Leaf").getConstructor(String.class).newInstance("x");
    return shape.name().equals("record") ? leaf : new ArrayList<>(List.of(leaf));
  }

  private static JavaFileObject[] sources(final String prefix, final Route route, final Shape shape) {
    final var head = "package " + PACKAGE + ";\n";
    final var srcType = shape.srcType().formatted(prefix);
    final var tgtType = shape.tgtType().formatted(prefix);
    final var files = new ArrayList<JavaFileObject>();
    if (!shape.name().equals("scalar")) {
      files.add(source(prefix + "Leaf", head + "public record " + prefix + "Leaf(String v) {}\n"));
      files.add(source(prefix + "LeafDto", head + "public record " + prefix + "LeafDto(String v) {}\n"));
    }
    files.add(
      source(
        prefix + "Src",
        head +
          "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
          prefix +
          "Tgt.class)\npublic class " +
          prefix +
          "Src {\n  private " +
          srcType +
          " p;\n  public " +
          srcType +
          " getP() { return p; }\n  public void setP(" +
          srcType +
          " p) { this.p = p; }\n  public " +
          prefix +
          "Src() {}\n}\n"
      )
    );
    files.add(
      source(
        prefix + "Tgt",
        head + "public class " + prefix + "Tgt {\n  " + route.body().formatted(prefix, tgtType) + "}\n"
      )
    );
    return files.toArray(new JavaFileObject[0]);
  }

  private static JavaFileObject source(final String simpleName, final String code) {
    return ProcessorHarness.source(PACKAGE + "." + simpleName, code);
  }

  private static Class<?> bridgeOf(
    final ProcessorHarness.Compilation processed,
    final ProcessorHarness.Compilation plain,
    final String prefix
  ) {
    final var added = new java.util.LinkedHashMap<>(processed.classes());
    plain.classes().keySet().forEach(added::remove);
    final var defined = new ProcessorHarness.Compilation(
      processed.success(),
      processed.diagnostics(),
      processed.generated(),
      processed.resources(),
      added
    ).define(MethodHandles.lookup());
    final var bridge = defined.get(PACKAGE + "." + prefix + "SrcBridge");
    if (bridge == null) throw new IllegalStateException("the processor emitted no bridge, only " + defined.keySet());
    return bridge;
  }

  private interface Attempt {
    Object get() throws ReflectiveOperationException;
  }

  private static Outcome attempt(final Attempt attempt) {
    try {
      return new Outcome(String.valueOf(attempt.get()), true);
    } catch (final InvocationTargetException e) {
      final var cause = e.getCause() == null ? e : e.getCause();
      return new Outcome(cause.getClass().getSimpleName() + ": " + cause.getMessage(), false);
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return new Outcome(e.getClass().getSimpleName() + ": " + e.getMessage(), false);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> cast(final Class<?> type) {
    return (Class<T>) type;
  }
}
