package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * BeanWriter} permits and the processor's rebuild kinds — and neither table knows what the other
 * holds, so a strategy one side gains and the other lacks shows up here as a diverging cell.
 *
 * <p>A cell agrees when both paths produce the same rendering, or when both refuse. A disagreement
 * is a defect unless {@link #KNOWN_DIVERGENCES} carries it, and a mutual refusal owes an entry in
 * {@link #KNOWN_REFUSALS}, because two refusals agree while measuring nothing.
 *
 * <p>Sources compile without {@code -parameters}, which is what an ordinary build passes. The
 * constructor route is the one whose outcome turns on how the class was compiled, because the
 * runtime matches its arguments by parameter name and reads the names from {@code -parameters} or
 * from the debug information {@code -g} keeps. The grid defines its classes through a lookup, which
 * leaves no class bytes to read, so that route runs in its own tests, each under the compile
 * options it is about and from a loader that serves each class's bytes as a class-path directory
 * does.
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
      "builder",
      "private final %2$s p;\n  private %1$sTgt(%2$s p) { this.p = p; }\n  public %2$s getP() { return p; }\n" +
        "  public static Builder builder() { return new Builder(); }\n" +
        "  public static final class Builder {\n    private %2$s p;\n" +
        "    public Builder p(%2$s p) { this.p = p; return this; }\n" +
        "    public %1$sTgt build() { return new %1$sTgt(p); }\n  }\n"
    ),
    new Route("fields", "private %2$s p;\n  public %2$s getP() { return p; }\n  public %1$sTgt() {}\n"),
    new Route(
      "protected-setters",
      "private %2$s p;\n  public %2$s getP() { return p; }\n  public void setP(%2$s p) { this.p = p; }\n" +
        "  protected %1$sTgt() {}\n"
    ),
    new Route(
      "private-setters",
      "private %2$s p;\n  public %2$s getP() { return p; }\n  public void setP(%2$s p) { this.p = p; }\n" +
        "  private %1$sTgt() {}\n"
    ),
    new Route(
      "memberless-builder",
      "private %2$s p;\n  private %1$sTgt(%2$s p) { this.p = p; }\n  public %2$s getP() { return p; }\n" +
        "  public static Builder builder() { return new Builder(); }\n" +
        "  public static final class Builder {\n    public %1$sTgt build() { return new %1$sTgt(null); }\n  }\n"
    ),
    new Route(
      "memberless-builder-final",
      "private final %2$s p;\n  private %1$sTgt(%2$s p) { this.p = p; }\n  public %2$s getP() { return p; }\n" +
        "  public static Builder builder() { return new Builder(); }\n" +
        "  public static final class Builder {\n    public %1$sTgt build() { return new %1$sTgt(null); }\n  }\n"
    )
  );

  /** A public constructor named after the property, the route the compile options decide. */
  private static final Route CTOR = new Route(
    "ctor",
    "private final %2$s p;\n  public %1$sTgt(%2$s p) { this.p = p; }\n  public %2$s getP() { return p; }\n"
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
  private static final Map<String, Verdict> KNOWN_DIVERGENCES = Map.ofEntries(
    // Inherent, in this entry and the two after it: a private constructor. The runtime
    // calls it through a private lookup; the bridge is ordinary source in another class and
    // cannot.
    Map.entry("private-setters/scalar", new Verdict(false, true)),
    Map.entry("private-setters/record", new Verdict(false, true)),
    Map.entry("private-setters/list", new Verdict(false, true)),
    // Inherent, in this entry and the two after it: a final field with no initializer that
    // the builder has no member for. The processor sees from source that only a strategy
    // gives it a value, and refuses the builder. The runtime cannot tell such a field from
    // a final one given a non-constant value where it is declared, because neither leaves a
    // trace in the class file, so it builds the bean.
    Map.entry("memberless-builder-final/scalar", new Verdict(false, true)),
    Map.entry("memberless-builder-final/record", new Verdict(false, true)),
    Map.entry("memberless-builder-final/list", new Verdict(false, true))
  );

  /** Cells both paths refuse, each naming a fragment of what each says. */
  private record Refusal(String generatedSays, String reflectiveSays) {}

  // Neither path writes a private field, so a target whose only write path is its fields offers
  // both of them nothing to call.
  private static final Refusal NO_WRITE_SURFACE = new Refusal(
    "has a no-arg constructor but no setter for 'p'",
    "does not write private fields"
  );

  // A builder is the only strategy offered and it has no member for `p`, a field nothing but a
  // strategy can set. Building through it would drop the value, so both paths refuse the target
  // rather than return it without `p`.
  private static final Refusal BUILDER_CANNOT_CARRY = new Refusal(
    "has no usable construction strategy",
    "has no member that takes [p]"
  );

  private static final Map<String, Refusal> KNOWN_REFUSALS = Map.of(
    // Agreement, not a difference, in this entry and the two after it: both paths refuse by
    // name a target they could only write through its private fields.
    "fields/scalar",
    NO_WRITE_SURFACE,
    "fields/record",
    NO_WRITE_SURFACE,
    "fields/list",
    NO_WRITE_SURFACE,
    // Agreement, not a difference, in this entry and the two after it: both paths refuse by
    // name a builder that cannot carry a property only a strategy sets.
    "memberless-builder/scalar",
    BUILDER_CANNOT_CARRY,
    "memberless-builder/record",
    BUILDER_CANNOT_CARRY,
    "memberless-builder/list",
    BUILDER_CANNOT_CARRY
  );

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
  @DisplayName("the constructor route agrees in an ordinary build, which keeps parameter names as debug info")
  void theConstructorRouteAgreesWhenDebugInfoKeepsTheNames() {
    // Gradle and Maven compile with -g and without -parameters. The names are then in the
    // constructor's local variable table alone, which the runtime reads from the class's bytes.
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var shape : SHAPES) {
      final var prefix = "Bg" + index++;
      final var outcomes = run(prefix, CTOR, shape, List.of("-g"), true);
      final var want = new Outcome(shape.rendered().formatted(prefix), true);
      if (!want.equals(outcomes.get(0)) || !want.equals(outcomes.get(1))) {
        failures.add(shape.name() + ": generated " + outcomes.get(0) + ", reflective " + outcomes.get(1));
      }
    }
    assertTrue(failures.isEmpty(), () -> failures.size() + " shape(s) failed:\n  " + String.join("\n  ", failures));
  }

  @Test
  @DisplayName("the constructor route diverges only when the class keeps no parameter names at all")
  void theConstructorRouteDivergesWhenNoNamesAreKept() {
    // Inherent: with neither -parameters nor a local variable table the class file holds no names,
    // so the runtime has nothing to match the arguments by and refuses by name. The processor reads
    // them from the compilation unit and converts.
    final var outcomes = run("BgNone", CTOR, SHAPES.getFirst(), List.of("-g:none"), true);

    assertTrue("hello".equals(outcomes.get(0).text()), () -> "the generated path should convert: " + outcomes);
    assertTrue(
      !outcomes.get(1).converted() && outcomes.get(1).text().contains("javac -parameters or -g"),
      () -> "the runtime should refuse and name both ways to keep the names: " + outcomes.get(1)
    );
  }

  @Test
  @DisplayName("the constructor route agrees once the source carries parameter names")
  void theConstructorRouteAgreesWhenParameterNamesArePresent() {
    // -parameters keeps the names where reflection reads them, so no class bytes are needed.
    final var outcomes = run("BrParam", CTOR, SHAPES.getFirst(), List.of("-parameters"));

    assertTrue(outcomes.get(0).converted(), () -> "the generated path should convert: " + outcomes.get(0));
    assertTrue(outcomes.get(1).converted(), () -> "the reflective path should convert: " + outcomes.get(1));
    assertTrue(
      "hello".equals(outcomes.get(0).text()) && "hello".equals(outcomes.get(1).text()),
      () -> "both should carry the value: " + outcomes
    );
  }

  /**
   * A target offering several routes at once, each tagging what it built, and the tag both paths
   * owe. {@code %1$s} is the cell's prefix.
   *
   * <p>The routes above each offer one strategy, so they say nothing about which one wins when a
   * bean offers more than one. These do, and the tag makes the choice visible in the value: a
   * builder or a constructor that normalises, validates or derives a value is exactly where two
   * paths picking differently return different objects.
   */
  private record Offer(String name, String body, String tag) {}

  private static final String TAGGING_BUILDER =
    "  private %1$sTgt(final String p, final boolean built) { this.p = p; }\n" +
    "  public static Builder builder() { return new Builder(); }\n" +
    "  public static final class Builder {\n    private String p;\n" +
    "    public Builder p(final String p) { this.p = p; return this; }\n" +
    "    public %1$sTgt build() { return new %1$sTgt(p + \"[builder]\", true); }\n  }\n";

  private static final String TAGGING_CTOR = "  public %1$sTgt(final String p) { this.p = p + \"[ctor]\"; }\n";

  private static final String TAGGING_SETTERS =
    "  public %1$sTgt() {}\n  public void setP(final String p) { this.p = p + \"[setters]\"; }\n";

  private static final String VARARGS_BUILDER =
    "  private %1$sTgt(final String p, final boolean built) { this.p = p; }\n" +
    "  public static Builder builder() { return new Builder(); }\n" +
    "  public static final class Builder {\n    private String p;\n" +
    "    public Builder p(final String... p) { this.p = String.join(\",\", p); return this; }\n" +
    "    public %1$sTgt build() { return new %1$sTgt(p + \"[builder]\", true); }\n  }\n";

  private static final String MEMBERLESS_BUILDER =
    "  private %1$sTgt(final String p, final boolean built) { this.p = p; }\n" +
    "  public static Builder builder() { return new Builder(); }\n" +
    "  public static final class Builder {\n" +
    "    public %1$sTgt build() { return new %1$sTgt(\"[builder]\", true); }\n  }\n";

  private static final List<Offer> OFFERS = List.of(
    new Offer("builder+ctor+setters", TAGGING_BUILDER + TAGGING_CTOR + TAGGING_SETTERS, "[builder]"),
    new Offer("builder+setters", TAGGING_BUILDER + TAGGING_SETTERS, "[builder]"),
    new Offer("builder+ctor", TAGGING_BUILDER + TAGGING_CTOR, "[builder]"),
    new Offer("ctor+setters", TAGGING_CTOR + TAGGING_SETTERS, "[ctor]"),
    new Offer("setters", TAGGING_SETTERS, "[setters]"),
    // A member that answers to `p` by name and takes a String[]: the builder cannot hold the
    // value, so it is passed over for the setters on both paths.
    new Offer("varargs-builder+setters", VARARGS_BUILDER + TAGGING_SETTERS, "[setters]"),
    // A builder with no member for `p` beside a constructor that writes it: taking the
    // builder
    // would drop `p`, so it is passed over for the constructor on both paths.
    new Offer("memberless-builder+ctor", MEMBERLESS_BUILDER + TAGGING_CTOR, "[ctor]")
  );

  @Test
  @DisplayName("a target offering several routes is built through the same one by both paths")
  void bothPathsPickTheSameRouteWhenATargetOffersSeveral() {
    // Compiled with -parameters, which the runtime needs to match a constructor's arguments by
    // name; without it the constructor is not on offer to the runtime at all, and the routes
    // above pin that difference separately.
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var offer : OFFERS) {
      final var prefix = "Bo" + index++;
      final var route = new Route(
        offer.name(),
        "private String p;\n  public String getP() { return p; }\n" + offer.body()
      );
      final var outcomes = run(prefix, route, SHAPES.getFirst(), List.of("-parameters"));
      final var want = new Outcome("hello" + offer.tag(), true);
      if (!want.equals(outcomes.get(0)) || !want.equals(outcomes.get(1))) {
        failures.add(
          offer.name() + ": generated " + outcomes.get(0) + ", reflective " + outcomes.get(1) + ", want " + want
        );
      }
    }
    assertTrue(failures.isEmpty(), () -> failures.size() + " offer(s) failed:\n  " + String.join("\n  ", failures));
  }

  @Test
  @DisplayName("a navigator rebuilds a target offering several routes through the one the runtime picks")
  void theNavigatorPicksTheSameRouteAsTheRuntime() {
    // The navigator's holder stands in for the runtime writer on an annotated bean, so it owes the
    // same choice. Each write below rebuilds the bean, and the tag says which route did it.
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var offer : OFFERS) {
      final var prefix = "Bn" + index++;
      final var route = new Route(
        offer.name(),
        "private String p;\n  public String getP() { return p; }\n" + offer.body()
      );
      final var sources = sources(prefix, route, SHAPES.getFirst());
      final var plain = ProcessorHarness.compileFully(List.of(), List.of("-parameters"), sources);
      final var processed = ProcessorHarness.compileFully(
        List.of(new BeanFocusProcessor()),
        List.of("-parameters"),
        sources
      );
      assertTrue(plain.success(), () -> prefix + " should compile: " + plain.errorMessages());
      assertTrue(
        processed.success(),
        () -> prefix + " should compile with the processor: " + processed.errorMessages()
      );
      try {
        final var classes = plain.define(MethodHandles.lookup());
        final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
        final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
        final var source = src.getConstructor().newInstance();
        src.getMethod("setP", String.class).invoke(source, "hello");
        final var seed = Telescope.mapper(src, tgt).forward(source);
        // The runtime write runs before the generated classes exist, so the holder probe finds no
        // holder for this class and the write goes through the reflective writer.
        final var reflective = attempt(() ->
          tgt.getMethod("getP").invoke(Telescope.ofBean(tgt).fieldByName("p").set(seed, "v"))
        );
        final var navigator = definedAdditions(processed, plain).get(PACKAGE + "." + prefix + "TgtTelescope");
        if (navigator == null) throw new IllegalStateException("the processor emitted no navigator for " + prefix);
        final var generated = attempt(() -> {
          @SuppressWarnings("unchecked")
          final var path = (Telescope<Object, Object>) navigator
            .getMethod("p")
            .invoke(navigator.getMethod("of").invoke(null));
          return tgt.getMethod("getP").invoke(path.set(seed, "v"));
        });
        final var want = new Outcome("v" + offer.tag(), true);
        if (!want.equals(generated) || !want.equals(reflective)) {
          failures.add(offer.name() + ": generated " + generated + ", reflective " + reflective + ", want " + want);
        }
      } catch (final ReflectiveOperationException e) {
        throw new IllegalStateException(prefix + " could not be built", e);
      }
    }
    assertTrue(failures.isEmpty(), () -> failures.size() + " offer(s) failed:\n  " + String.join("\n  ", failures));
  }

  @Test
  @DisplayName("a builder with no member for a final field given its value in place builds on both paths")
  void anInitialisedFinalAsksNothingOfTheBuilder() throws ReflectiveOperationException {
    // `kind` is set where it is declared, so no strategy writes it and the builder, which has no
    // member for it, still carries the bean. Both paths build it and keep "K".
    final var prefix = "Bk";
    final var head = "package " + PACKAGE + ";\n";
    final JavaFileObject[] sources = {
      source(
        prefix + "Src",
        head +
          "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
          prefix +
          "Tgt.class)\npublic class " +
          prefix +
          "Src {\n  private int i;\n  private String kind;\n  public " +
          prefix +
          "Src() {}\n  public int getI() { return i; }\n  public void setI(int i) { this.i = i; }\n" +
          "  public String getKind() { return kind; }\n  public void setKind(String kind) { this.kind = kind; }\n}\n"
      ),
      source(
        prefix + "Tgt",
        head +
          "public class " +
          prefix +
          "Tgt {\n  private final int i;\n  private final String kind = \"K\";\n  private " +
          prefix +
          "Tgt(int i) { this.i = i; }\n  public int getI() { return i; }\n  public String getKind() { return kind; }\n" +
          "  public static Builder builder() { return new Builder(); }\n" +
          "  public static final class Builder {\n    private int i;\n" +
          "    public Builder i(int i) { this.i = i; return this; }\n" +
          "    public " +
          prefix +
          "Tgt build() { return new " +
          prefix +
          "Tgt(i); }\n  }\n  @Override public String toString() { return i + \",\" + kind; }\n}\n"
      ),
    };
    final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
    assertTrue(processed.success(), () -> "the bridge should build it: " + processed.errorMessages());
    final var classes = plain.define(MethodHandles.lookup());
    final Class<Object> src = cast(classes.get(PACKAGE + "." + prefix + "Src"));
    final Class<Object> tgt = cast(classes.get(PACKAGE + "." + prefix + "Tgt"));
    final var source = src.getConstructor().newInstance();
    src.getMethod("setI", int.class).invoke(source, 7);
    src.getMethod("setKind", String.class).invoke(source, "ignored");

    final var bridge = definedAdditions(processed, plain).get(PACKAGE + "." + prefix + "SrcBridge");
    final var generated = String.valueOf(bridge.getMethod("forward", src).invoke(null, source));
    final var reflective = String.valueOf(Telescope.mapperForward(src, tgt).forward(source));

    assertTrue("7,K".equals(generated), () -> "generated " + generated);
    assertTrue("7,K".equals(reflective), () -> "reflective " + reflective);
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

  /** Both paths' outcomes for one cell, generated first, over classes defined through a lookup. */
  private static List<Outcome> run(
    final String prefix,
    final Route route,
    final Shape shape,
    final List<String> options
  ) {
    return run(prefix, route, shape, options, false);
  }

  /**
   * Both paths' outcomes for one cell, generated first. With {@code servesBytes} the classes come
   * from a loader that answers each one's {@code .class} resource with its bytes, as a class-path
   * directory does; otherwise they are defined through this class's lookup, which keeps no bytes.
   */
  private static List<Outcome> run(
    final String prefix,
    final Route route,
    final Shape shape,
    final List<String> options,
    final boolean servesBytes
  ) {
    final var sources = sources(prefix, route, shape);
    final var processed = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), options, sources);
    final var plain = ProcessorHarness.compileFully(List.of(), options, sources);
    assertTrue(plain.success(), () -> prefix + " should compile without the processor: " + plain.errorMessages());
    try {
      final var classes = servesBytes ? loadServingBytes(processed, plain) : plain.define(MethodHandles.lookup());
      final var src = classes.get(PACKAGE + "." + prefix + "Src");
      final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
      final var getP = tgt.getMethod("getP");
      final var source = src.getConstructor().newInstance();
      src.getMethod("setP", src.getMethod("getP").getReturnType()).invoke(source, property(classes, prefix, shape));

      final var bridge = !processed.success()
        ? null
        : servesBytes
          ? classes.get(PACKAGE + "." + prefix + "SrcBridge")
          : bridgeOf(processed, plain, prefix);
      final var forward = bridge == null ? null : bridge.getMethod("forward", src);
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

  /**
   * Loads every class both compilations produced through one {@link BytesServingLoader}, so the
   * bridge sits in the same runtime package as the classes it converts.
   */
  private static Map<String, Class<?>> loadServingBytes(
    final ProcessorHarness.Compilation processed,
    final ProcessorHarness.Compilation plain
  ) throws ClassNotFoundException {
    final var bytes = new LinkedHashMap<>(processed.classes());
    bytes.putAll(plain.classes());
    final var loader = new BytesServingLoader(bytes);
    final var loaded = new LinkedHashMap<String, Class<?>>();
    for (final var name : bytes.keySet()) loaded.put(name, Class.forName(name, false, loader));
    return loaded;
  }

  /** Defines compiled classes and answers each one's {@code .class} resource with its bytes. */
  private static final class BytesServingLoader extends ClassLoader {

    private final Map<String, byte[]> classes;

    BytesServingLoader(final Map<String, byte[]> classes) {
      super(BeanRebuildCorpusTest.class.getClassLoader());
      this.classes = Map.copyOf(classes);
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
      final var bytes = classes.get(name);
      if (bytes == null) throw new ClassNotFoundException(name);
      return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    public InputStream getResourceAsStream(final String name) {
      final var bytes = name.endsWith(".class")
        ? classes.get(name.substring(0, name.length() - ".class".length()).replace('/', '.'))
        : null;
      return bytes != null ? new ByteArrayInputStream(bytes) : super.getResourceAsStream(name);
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
    final var focus = "import io.github.eschizoid.telescope.annotations.BeanFocus;\n@BeanFocus\n";
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
        head + focus + "public class " + prefix + "Tgt {\n  " + route.body().formatted(prefix, tgtType) + "}\n"
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
    final var defined = definedAdditions(processed, plain);
    final var bridge = defined.get(PACKAGE + "." + prefix + "SrcBridge");
    if (bridge == null) throw new IllegalStateException("the processor emitted no bridge, only " + defined.keySet());
    return bridge;
  }

  /** Defines what the processor added to a compilation, against the classes already defined. */
  private static Map<String, Class<?>> definedAdditions(
    final ProcessorHarness.Compilation processed,
    final ProcessorHarness.Compilation plain
  ) {
    final var added = new LinkedHashMap<>(processed.classes());
    plain.classes().keySet().forEach(added::remove);
    return new ProcessorHarness.Compilation(
      processed.success(),
      processed.diagnostics(),
      processed.generated(),
      processed.resources(),
      added
    ).define(MethodHandles.lookup());
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
