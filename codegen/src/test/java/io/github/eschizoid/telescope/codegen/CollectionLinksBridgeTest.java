package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How the generated path ties a collection's elements to their owner where nothing on the runtime
 * path tells: a model-anchored {@code @Bridge} fills a collection through the adder its type finds,
 * the bridge takes a {@code mappedBy} back-reference only where the runtime answers it, and the
 * verifier reports each collection's path, and what it cannot decide, at compile time. Both paths
 * over the same declarations are held to one result by {@code CrossPathCorpusTest}.
 */
class CollectionLinksBridgeTest {

  private static final String PKG = "io.github.eschizoid.telescope.codegen";

  private static final String LINE =
    "package " +
    PKG +
    ";\npublic class AdLine {\n  private String sku;\n  private Object order;\n  public AdLine() {}\n" +
    "  public String getSku() { return sku; }\n  public void setSku(final String v) { sku = v; }\n" +
    "  public Object getOrder() { return order; }\n  public void attach(final Object o) { order = o; }\n}\n";

  /** An order whose only way to take a line is an adder its name does not tie to the lines. */
  private static final String ORDER =
    "package " +
    PKG +
    ";\npublic class AdOrder {\n  private final java.util.List<AdLine> lines = new java.util.ArrayList<>();\n" +
    "  public AdOrder() {}\n  public java.util.List<AdLine> getLines() { return lines; }\n" +
    "  public void addOrderLine(final AdLine line) { line.attach(this); lines.add(line); }\n}\n";

  private static final String LINE_DTO =
    "package " +
    PKG +
    ";\n@io.github.eschizoid.telescope.annotations.Bridge(AdLine.class)\npublic record AdLineDto(String sku) {}\n";

  private static final String ORDER_DTO =
    "package " +
    PKG +
    ";\n@io.github.eschizoid.telescope.annotations.Bridge(value = AdOrder.class, writeStrategy =" +
    " io.github.eschizoid.telescope.annotations.WriteStrategy.SETTERS, lenient = true)\n" +
    "public record AdOrderDto(java.util.List<AdLineDto> lines) {}\n";

  @Test
  @DisplayName("a model-anchored bridge fills the target's collection through the adder its element class finds")
  void theAnnotationFillsThroughTheAdderFoundByType() throws ReflectiveOperationException {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source(PKG + ".AdLine", LINE),
      ProcessorHarness.source(PKG + ".AdOrder", ORDER),
      ProcessorHarness.source(PKG + ".AdLineDto", LINE_DTO),
      ProcessorHarness.source(PKG + ".AdOrderDto", ORDER_DTO)
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var line = classes.get(PKG + ".AdLineDto").getConstructor(String.class).newInstance("a");
    final var dto = classes.get(PKG + ".AdOrderDto").getConstructor(List.class).newInstance(List.of(line));
    final var order = classes
      .get(PKG + ".AdOrderDtoBridge")
      .getMethod("forward", classes.get(PKG + ".AdOrderDto"))
      .invoke(null, dto);
    final var lines = (List<?>) classes.get(PKG + ".AdOrder").getMethod("getLines").invoke(order);
    assertEquals(1, lines.size());
    assertSame(order, classes.get(PKG + ".AdLine").getMethod("getOrder").invoke(lines.getFirst()));
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
          " io.github.eschizoid.telescope.annotations.WriteStrategy.SETTERS, lenient = true)\n" +
          "public record AdSubDto(java.util.List<String> lines) {}\n"
      )
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var dto = classes.get(PKG + ".AdSubDto").getConstructor(List.class).newInstance(List.of("a", "b"));
    final var built = classes.get(PKG + ".AdSubDtoBridge").getMethod("forward", dto.getClass()).invoke(null, dto);
    assertEquals(List.of("a!", "b!"), classes.get(PKG + ".AdBase").getMethod("getLines").invoke(built));
  }

  /**
   * A line with two properties of the order's type, and an order whose list names one by mappedBy.
   */
  private static final String TWO_WAY_LINE =
    "package " +
    PKG +
    ";\npublic class MbLine {\n  private String sku;\n  private MbOrder order;\n  private MbOrder previous;\n" +
    "  public MbLine() {}\n  public String getSku() { return sku; }\n  public void setSku(final String v) { sku = v; }\n" +
    "  public MbOrder getOrder() { return order; }\n  public void setOrder(final MbOrder v) { order = v; }\n" +
    "  public MbOrder getPrevious() { return previous; }\n  public void setPrevious(final MbOrder v) { previous = v; }\n}\n";

  private static final String MAPPED_ORDER =
    "package " +
    PKG +
    ";\npublic class MbOrder {\n  @jakarta.persistence.OneToMany(mappedBy = \"order\")\n" +
    "  private java.util.List<MbLine> lines;\n  public MbOrder() {}\n" +
    "  public java.util.List<MbLine> getLines() { return lines; }\n" +
    "  public void setLines(final java.util.List<MbLine> v) { lines = v; }\n}\n";

  private static final String MAPPED_SOURCE =
    "package " + PKG + ";\npublic record MbSrc(java.util.List<MbSrcLine> lines) {}\n";

  private static final String MAPPED_SOURCE_LINE = "package " + PKG + ";\npublic record MbSrcLine(String sku) {}\n";

  private static final String MAPPED_HOLDER =
    "package " +
    PKG +
    ";\npublic class MbHolder {\n" +
    "  @io.github.eschizoid.telescope.annotations.Bridge\n" +
    "  public static final io.github.eschizoid.telescope.conversion.Mapper<MbSrc, MbOrder> MAPPER =\n" +
    "    io.github.eschizoid.telescope.Telescope.mapper(MbSrc.class, MbOrder.class);\n}\n";

  @Test
  @DisplayName(
    "a mappedBy the element's own properties would not tell is taken only where the runtime's policy answers it"
  )
  void mappedByIsTakenWhereTheRuntimeAnswersIt() {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source(PKG + ".MbLine", TWO_WAY_LINE),
      ProcessorHarness.source(PKG + ".MbOrder", MAPPED_ORDER),
      ProcessorHarness.source(PKG + ".MbSrc", MAPPED_SOURCE),
      ProcessorHarness.source(PKG + ".MbSrcLine", MAPPED_SOURCE_LINE),
      ProcessorHarness.source(PKG + ".MbHolder", MAPPED_HOLDER)
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var bridge = compilation
      .generated()
      .entrySet()
      .stream()
      .filter(entry -> entry.getKey().endsWith("MbSrcToMbOrderBridge"))
      .map(Map.Entry::getValue)
      .findFirst()
      .orElseThrow();
    assertTrue(
      bridge.contains(">links(" + PKG + ".MbOrder.class, \"lines\", \"order\","),
      () -> "the links ask the runtime for mappedBy:\n" + bridge
    );
    final var line = compilation
      .generated()
      .entrySet()
      .stream()
      .filter(entry -> entry.getKey().endsWith("MbSrcLineToMbLineBridge"))
      .map(Map.Entry::getValue)
      .findFirst()
      .orElseThrow();
    final var into = line.substring(line.indexOf(" into("), line.indexOf("\n  }\n", line.indexOf(" into(")));
    assertFalse(
      into.contains(".setOrder(") || into.contains(".setPrevious("),
      () -> "the element pair's into leaves each back-reference as the element holds it:\n" + into
    );
  }

  private static final String WARN = "-Atelescope.verify=warn";

  private static List<String> notes(final ProcessorHarness.Compilation compilation, final Diagnostic.Kind kind) {
    return compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == kind)
      .map(d -> d.getMessage(null))
      .toList();
  }

  @Test
  @DisplayName("the verifier notes each collection's path under verify=warn, in the words explain prints")
  void theVerifierNotesEachCollection() {
    final var holder =
      "package " +
      PKG +
      ";\npublic class AdHolder {\n  public record Src(java.util.List<SrcLine> lines) {}\n" +
      "  public record SrcLine(String sku) {}\n" +
      "  static final Object MAPPER = io.github.eschizoid.telescope.Telescope.mapper(Src.class, AdOrder.class,\n" +
      "    io.github.eschizoid.telescope.mapping.Mapping.writeBean(AdOrder.class," +
      " io.github.eschizoid.telescope.mapping.Mapping.SETTERS));\n}\n";
    final var sources = List.of(
      ProcessorHarness.source(PKG + ".AdLine", LINE),
      ProcessorHarness.source(PKG + ".AdOrder", ORDER),
      ProcessorHarness.source(PKG + ".AdHolder", holder)
    );
    final var warned = ProcessorHarness.compileFully(
      List.of(new MapperVerifierProcessor()),
      List.of(WARN),
      sources.toArray(JavaFileObject[]::new)
    );
    assertTrue(warned.success(), warned::errorMessages);
    assertTrue(
      notes(warned, Diagnostic.Kind.NOTE).contains("telescope: AdOrder.lines: through AdOrder.addOrderLine(AdLine)"),
      () -> notes(warned, Diagnostic.Kind.NOTE).toString()
    );
    final var quiet = ProcessorHarness.compileFully(
      List.of(new MapperVerifierProcessor()),
      List.of(),
      sources.toArray(JavaFileObject[]::new)
    );
    assertTrue(quiet.success(), quiet::errorMessages);
    assertTrue(
      notes(quiet, Diagnostic.Kind.NOTE)
        .stream()
        .noneMatch(note -> note.contains("AdOrder.lines")),
      "a default build prints no note"
    );
  }

  /** An order with a list of lines and a setter for it, and no adder. */
  private static final String PLAIN_ORDER =
    "package " +
    PKG +
    ";\npublic class PtOrder {\n  private java.util.List<PtLine> lines = new java.util.ArrayList<>();\n" +
    "  public PtOrder() {}\n  public java.util.List<PtLine> getLines() { return lines; }\n" +
    "  public void setLines(final java.util.List<PtLine> v) { lines = v; }\n%s}\n";

  private static final String PT_SOURCES = "package " + PKG + ";\npublic record PtLineDto(String sku) {}\n";

  private static ProcessorHarness.Compilation pointerPair(final String orderMembers, final String lineProperty) {
    return ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source(
        PKG + ".PtLine",
        "package " +
          PKG +
          ";\npublic class PtLine {\n  private String sku;\n  private " +
          lineProperty +
          " extra;\n  public PtLine() {}\n" +
          "  public String getSku() { return sku; }\n  public void setSku(final String v) { sku = v; }\n" +
          "  public " +
          lineProperty +
          " getExtra() { return extra; }\n  public void setExtra(final " +
          lineProperty +
          " v) { extra = v; }\n}\n"
      ),
      ProcessorHarness.source(PKG + ".PtOrder", PLAIN_ORDER.formatted(orderMembers)),
      ProcessorHarness.source(PKG + ".PtLineDto", PT_SOURCES),
      ProcessorHarness.source(
        PKG + ".PtOrderDto",
        "package " + PKG + ";\npublic record PtOrderDto(java.util.List<PtLineDto> lines) {}\n"
      ),
      ProcessorHarness.source(
        PKG + ".PtHolder",
        "package " +
          PKG +
          ";\npublic class PtHolder {\n  @io.github.eschizoid.telescope.annotations.Bridge\n" +
          "  public static final io.github.eschizoid.telescope.conversion.Mapper<PtOrderDto, PtOrder> MAPPER =\n" +
          "    io.github.eschizoid.telescope.Telescope.mapper(PtOrderDto.class, PtOrder.class);\n}\n"
      )
    );
  }

  @Test
  @DisplayName("an element pair leaves the owner-typed pointer an adder sets at its default, at compile time")
  void anAdderSetPointerIsLeftUnpaired() {
    final var compilation = pointerPair(
      "  public void addLine(final PtLine line) { line.setExtra(this); lines.add(line); }\n",
      "PtOrder"
    );
    assertTrue(compilation.success(), compilation::errorMessages);
  }

  @Test
  @DisplayName("an element pair refuses an owner-typed property with no adder and no back-reference to set it")
  void anUndeclaredOwnerTypedPropertyIsRefused() {
    final var compilation = pointerPair("", "PtOrder");
    assertFalse(compilation.success());
    assertTrue(compilation.errorMessages().contains("'extra'"), compilation::errorMessages);
  }

  @Test
  @DisplayName("an element pair refuses an unpaired property of another type, even where the owner has an adder")
  void anUnpairedPropertyOfAnotherTypeIsRefused() {
    final var compilation = pointerPair("  public void addLine(final PtLine line) { lines.add(line); }\n", "String");
    assertFalse(compilation.success());
    assertTrue(compilation.errorMessages().contains("'extra'"), compilation::errorMessages);
  }

  @Test
  @DisplayName(
    "a bridge leaves two adders no name decides between unused, writes through the setter, and warns in the" +
      " runtime's words"
  )
  void aBridgeLeavesUndecidedAddersUnused() throws ReflectiveOperationException {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source(
        PKG + ".KbLine",
        "package " +
          PKG +
          ";\npublic class KbLine {\n  private String sku;\n  public KbLine() {}\n" +
          "  public String getSku() { return sku; }\n  public void setSku(final String v) { sku = v; }\n" +
          "  @Override public String toString() { return sku; }\n}\n"
      ),
      ProcessorHarness.source(
        PKG + ".KbOrder",
        "package " +
          PKG +
          ";\npublic class KbOrder {\n  private java.util.List<KbLine> lines = new java.util.ArrayList<>();\n" +
          "  public final java.util.List<KbLine> other = new java.util.ArrayList<>();\n  public KbOrder() {}\n" +
          "  public java.util.List<KbLine> getLines() { return lines; }\n" +
          "  public void setLines(final java.util.List<KbLine> v) { lines = v; }\n" +
          "  public void addPrimary(final KbLine line) { other.add(line); }\n" +
          "  public void addBackup(final KbLine line) { other.add(line); }\n}\n"
      ),
      ProcessorHarness.source(PKG + ".KbLineDto", "package " + PKG + ";\npublic record KbLineDto(String sku) {}\n"),
      ProcessorHarness.source(
        PKG + ".KbOrderDto",
        "package " +
          PKG +
          ";\n@io.github.eschizoid.telescope.annotations.Bridge(KbOrder.class)\n" +
          "public record KbOrderDto(java.util.List<KbLineDto> lines) {}\n"
      )
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var warnings = compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
      .map(d -> d.getMessage(Locale.ROOT))
      .toList();
    assertEquals(
      List.of(
        PairingMessages.collectionMemberUnused(
          "KbOrder",
          "lines",
          "KbLine",
          "adder",
          List.of("addBackup(KbLine)", "addPrimary(KbLine)"),
          "addLine"
        )
      ),
      warnings
    );
    final var classes = compilation.define(MethodHandles.lookup());
    final var line = classes.get(PKG + ".KbLineDto").getConstructor(String.class).newInstance("a");
    final var dto = classes.get(PKG + ".KbOrderDto").getConstructor(List.class).newInstance(List.of(line));
    final var bridge = classes.get(PKG + ".KbOrderDtoBridge");
    final var built = bridge.getMethod("forward", dto.getClass()).invoke(null, dto);
    final var order = classes.get(PKG + ".KbOrder");
    assertEquals("[a]", order.getMethod("getLines").invoke(built).toString());
    assertEquals(List.of(), order.getField("other").get(built));
    assertEquals(
      "[KbOrder.lines: refilled in place; addBackup(KbLine) and addPrimary(KbLine) left unused: no name says" +
        " which belongs to 'lines']",
      bridge.getDeclaredField("COLLECTIONS").get(null).toString()
    );
  }

  @Test
  @DisplayName("a class-level bridge refuses two adders neither named for the collection, at compile time")
  void aClassLevelBridgeRefusesAnUndecidedAdder() {
    final var order =
      "package " +
      PKG +
      ";\npublic class AdOrder {\n  private final java.util.List<AdLine> lines = new java.util.ArrayList<>();\n" +
      "  public AdOrder() {}\n  public java.util.List<AdLine> getLines() { return lines; }\n" +
      "  public void addGift(final AdLine line) { lines.add(line); }\n" +
      "  public void addSample(final AdLine line) { lines.add(line); }\n}\n";
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source(PKG + ".AdLine", LINE),
      ProcessorHarness.source(PKG + ".AdOrder", order),
      ProcessorHarness.source(PKG + ".AdLineDto", LINE_DTO),
      ProcessorHarness.source(PKG + ".AdOrderDto", ORDER_DTO)
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(
        PairingMessages.collectionMemberUndecided(
          "AdOrder",
          "lines",
          "AdLine",
          "adder",
          List.of("addGift(AdLine)", "addSample(AdLine)"),
          "addLine"
        )
      ),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("the verifier refuses two adders neither named for the collection, in the runtime's words")
  void theVerifierRefusesAnUndecidedAdder() {
    final var order =
      "package " +
      PKG +
      ";\npublic class AdOrder {\n  private final java.util.List<AdLine> lines = new java.util.ArrayList<>();\n" +
      "  public AdOrder() {}\n  public java.util.List<AdLine> getLines() { return lines; }\n" +
      "  public void addGift(final AdLine line) { lines.add(line); }\n" +
      "  public void addSample(final AdLine line) { lines.add(line); }\n}\n";
    final var holder =
      "package " +
      PKG +
      ";\npublic class AdHolder {\n  public record Src(java.util.List<SrcLine> lines) {}\n" +
      "  public record SrcLine(String sku) {}\n" +
      "  static final Object MAPPER = io.github.eschizoid.telescope.Telescope.mapper(Src.class, AdOrder.class,\n" +
      "    io.github.eschizoid.telescope.mapping.Mapping.writeBean(AdOrder.class," +
      " io.github.eschizoid.telescope.mapping.Mapping.SETTERS));\n}\n";
    final var compilation = ProcessorHarness.compileFully(
      List.of(new MapperVerifierProcessor()),
      List.of(),
      ProcessorHarness.source(PKG + ".AdLine", LINE),
      ProcessorHarness.source(PKG + ".AdOrder", order),
      ProcessorHarness.source(PKG + ".AdHolder", holder)
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(
        PairingMessages.collectionMemberUndecided(
          "AdOrder",
          "lines",
          "AdLine",
          "adder",
          List.of("addGift(AdLine)", "addSample(AdLine)"),
          "addLine"
        )
      ),
      compilation::errorMessages
    );
  }
}
