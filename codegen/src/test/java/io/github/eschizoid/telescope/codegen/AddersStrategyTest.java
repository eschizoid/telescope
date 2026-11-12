package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.CollectionWrites;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.lang.invoke.MethodHandles;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The adders strategy named on the annotation of a model-anchored {@code @Bridge}, and on a {@code
 * writeBean} row the verifier replays: the generated bridge fills a collection through its adder,
 * and a class with a collection and no adder is refused at compile time in the runtime's words.
 */
class AddersStrategyTest {

  private static final String LINE =
    "package io.github.eschizoid.telescope.codegen;\npublic class AdLine {\n  private String sku;\n  private Object order;\n  public AdLine() {}\n" +
    "  public String getSku() { return sku; }\n  public void setSku(final String v) { sku = v; }\n" +
    "  public Object getOrder() { return order; }\n  public void attach(final Object o) { order = o; }\n}\n";

  private static final String ORDER =
    "package io.github.eschizoid.telescope.codegen;\npublic class AdOrder {\n  private final java.util.List<AdLine> lines = new java.util.ArrayList<>();\n" +
    "  public AdOrder() {}\n  public java.util.List<AdLine> getLines() { return lines; }\n" +
    "  public void addLine(final AdLine line) { line.attach(this); lines.add(line); }\n}\n";

  private static final String ADDERLESS =
    "package io.github.eschizoid.telescope.codegen;\npublic class AdOrder {\n  private java.util.List<AdLine> lines = new java.util.ArrayList<>();\n" +
    "  public AdOrder() {}\n  public java.util.List<AdLine> getLines() { return lines; }\n" +
    "  public void setLines(final java.util.List<AdLine> v) { lines = v; }\n}\n";

  private static final String LINE_DTO =
    "package io.github.eschizoid.telescope.codegen;\n@io.github.eschizoid.telescope.annotations.Bridge(AdLine.class)\npublic record AdLineDto(String sku) {}\n";

  private static final String ORDER_DTO =
    "package io.github.eschizoid.telescope.codegen;\n@io.github.eschizoid.telescope.annotations.Bridge(value = AdOrder.class, writeStrategy =" +
    " io.github.eschizoid.telescope.annotations.WriteStrategy.ADDERS, lenient = true)\n" +
    "public record AdOrderDto(java.util.List<AdLineDto> lines) {}\n";

  @Test
  @DisplayName("@Bridge(writeStrategy = ADDERS) fills the target's collection through its adder")
  void theAnnotationFillsThroughTheAdder() throws ReflectiveOperationException {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdLine", LINE),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdOrder", ORDER),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdLineDto", LINE_DTO),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdOrderDto", ORDER_DTO)
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var line = classes
      .get("io.github.eschizoid.telescope.codegen.AdLineDto")
      .getConstructor(String.class)
      .newInstance("a");
    final var dto = classes
      .get("io.github.eschizoid.telescope.codegen.AdOrderDto")
      .getConstructor(List.class)
      .newInstance(List.of(line));
    final var bridge = classes.get("io.github.eschizoid.telescope.codegen.AdOrderDtoBridge");
    final var order = bridge
      .getMethod("forward", classes.get("io.github.eschizoid.telescope.codegen.AdOrderDto"))
      .invoke(null, dto);
    final var lines = (List<?>) classes
      .get("io.github.eschizoid.telescope.codegen.AdOrder")
      .getMethod("getLines")
      .invoke(order);
    assertEquals(1, lines.size());
    assertSame(
      order,
      classes.get("io.github.eschizoid.telescope.codegen.AdLine").getMethod("getOrder").invoke(lines.getFirst())
    );
  }

  @Test
  @DisplayName("@Bridge(writeStrategy = ADDERS) on a target with no adder is refused by the property's name")
  void theAnnotationRefusesATargetWithNoAdder() {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdLine", LINE),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdOrder", ADDERLESS),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdLineDto", LINE_DTO),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdOrderDto", ORDER_DTO)
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(
        PairingMessages.adderMissing("AdOrder", "lines", "AdLine", CollectionWrites.adderNames("lines"))
      ),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("the verifier refuses a writeBean(..., ADDERS) row on a class with no adder, in the runtime's words")
  void theVerifierRefusesAMissingAdder() {
    final var holder =
      "package io.github.eschizoid.telescope.codegen;\npublic class AdHolder {\n  public record Src(java.util.List<AdLine> lines) {}\n" +
      "  static final Object MAPPER = io.github.eschizoid.telescope.Telescope.mapper(Src.class, AdOrder.class,\n" +
      "    io.github.eschizoid.telescope.mapping.WriteHint.writeBean(AdOrder.class," +
      " io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.ADDERS));\n}\n";
    final var compilation = ProcessorHarness.compileFully(
      List.of(new MapperVerifierProcessor()),
      List.of(),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdLine", LINE),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdOrder", ADDERLESS),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdHolder", holder)
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(
        PairingMessages.adderMissing("AdOrder", "lines", "AdLine", CollectionWrites.adderNames("lines"))
      ),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("the verifier passes a writeBean(..., ADDERS) row on a class whose collection has an adder")
  void theVerifierPassesAnAdder() {
    final var holder =
      "package io.github.eschizoid.telescope.codegen;\npublic class AdHolder {\n  public record Src(java.util.List<AdLine> lines) {}\n" +
      "  static final Object MAPPER = io.github.eschizoid.telescope.Telescope.mapper(Src.class, AdOrder.class,\n" +
      "    io.github.eschizoid.telescope.mapping.WriteHint.writeBean(AdOrder.class," +
      " io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.ADDERS));\n}\n";
    final var compilation = ProcessorHarness.compileFully(
      List.of(new MapperVerifierProcessor()),
      List.of(),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdLine", LINE),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdOrder", ORDER),
      ProcessorHarness.source("io.github.eschizoid.telescope.codegen.AdHolder", holder)
    );
    assertFalse(compilation.hasError("ADDERS"), compilation::errorMessages);
  }

  private static final String PKG = "io.github.eschizoid.telescope.codegen";

  /** A bean with an adder and only a constructor taking an argument. */
  private static final String CONSTRUCTED =
    "package " +
    PKG +
    ";\npublic class AdCtorOrder {\n  private final java.util.List<String> lines = new java.util.ArrayList<>();\n" +
    "  public AdCtorOrder(final String ignored) {}\n  public java.util.List<String> getLines() { return lines; }\n" +
    "  public void addLine(final String line) { lines.add(line); }\n}\n";

  @Test
  @DisplayName(
    "@Bridge(writeStrategy = ADDERS) on a target with no no-argument constructor is refused in the runtime's words"
  )
  void theAnnotationRefusesATargetWithNoNoArgumentConstructor() {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source(PKG + ".AdCtorOrder", CONSTRUCTED),
      ProcessorHarness.source(
        PKG + ".AdCtorDto",
        "package " +
          PKG +
          ";\n@io.github.eschizoid.telescope.annotations.Bridge(value = AdCtorOrder.class, writeStrategy =" +
          " io.github.eschizoid.telescope.annotations.WriteStrategy.ADDERS, lenient = true)\n" +
          "public record AdCtorDto(java.util.List<String> lines) {}\n"
      )
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(PairingMessages.addersNeedNoArgConstructor("AdCtorOrder")),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("the verifier refuses a writeBean(..., ADDERS) row on a class with no no-argument constructor")
  void theVerifierRefusesAMissingNoArgumentConstructor() {
    final var holder =
      "package " +
      PKG +
      ";\npublic class AdCtorHolder {\n  public record Src(java.util.List<String> lines) {}\n" +
      "  static final Object MAPPER = io.github.eschizoid.telescope.Telescope.mapper(Src.class, AdCtorOrder.class,\n" +
      "    io.github.eschizoid.telescope.mapping.WriteHint.writeBean(AdCtorOrder.class," +
      " io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.ADDERS));\n}\n";
    final var compilation = ProcessorHarness.compileFully(
      List.of(new MapperVerifierProcessor()),
      List.of(),
      ProcessorHarness.source(PKG + ".AdCtorOrder", CONSTRUCTED),
      ProcessorHarness.source(PKG + ".AdCtorHolder", holder)
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(PairingMessages.addersNeedNoArgConstructor("AdCtorOrder")),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("an adder and a collection field a superclass declares fill a subclass the bridge builds")
  void aSuperclassAdderFillsTheSubclass() throws ReflectiveOperationException {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source(
        PKG + ".AdBase",
        "package " +
          PKG +
          ";\npublic class AdBase {\n  private final java.util.List<String> lines = new java.util.ArrayList<>();\n" +
          "  public java.util.List<String> getLines() { return lines; }\n" +
          "  public void addLine(final String line) { lines.add(line + \"!\"); }\n}\n"
      ),
      ProcessorHarness.source(
        PKG + ".AdSub",
        "package " + PKG + ";\npublic class AdSub extends AdBase {\n  public AdSub() {}\n}\n"
      ),
      ProcessorHarness.source(
        PKG + ".AdSubDto",
        "package " +
          PKG +
          ";\n@io.github.eschizoid.telescope.annotations.Bridge(value = AdSub.class, writeStrategy =" +
          " io.github.eschizoid.telescope.annotations.WriteStrategy.ADDERS, lenient = true)\n" +
          "public record AdSubDto(java.util.List<String> lines) {}\n"
      )
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var dto = classes.get(PKG + ".AdSubDto").getConstructor(List.class).newInstance(List.of("a", "b"));
    final var built = classes.get(PKG + ".AdSubDtoBridge").getMethod("forward", dto.getClass()).invoke(null, dto);
    assertEquals(List.of("a!", "b!"), classes.get(PKG + ".AdBase").getMethod("getLines").invoke(built));
  }
}
