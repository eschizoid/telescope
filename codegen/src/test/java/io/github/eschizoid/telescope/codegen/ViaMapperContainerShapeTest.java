package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.lang.invoke.MethodHandles;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The container shapes a {@code @ViaMapper} does not lift its mapper over. One whose methods take
 * the container itself converts the whole property, even where each side's element is a type
 * variable whose bound would admit the method's parameter. One on a map whose keys differ between
 * the sides is refused when the bridge is generated, as the reflective path refuses the same row
 * when its mapper is built.
 */
class ViaMapperContainerShapeTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  @Test
  @DisplayName("a mapper of the whole list on a list both sides type by a variable is handed the list")
  void wholeListMapperIsHandedTheList() throws ReflectiveOperationException {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source(
        PACKAGE + ".WlListMap",
        "package " +
          PACKAGE +
          ";\n" +
          "@SuppressWarnings(\"rawtypes\")\n" +
          "public class WlListMap {\n" +
          "  public static java.util.List forward(final java.util.List l) { return l; }\n" +
          "  public static java.util.List backward(final java.util.List l) { return l; }\n}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + ".WlSrc",
        "package " +
          PACKAGE +
          ";\n" +
          "@io.github.eschizoid.telescope.annotations.Bridge(value = WlTgt.class, viaMappers =" +
          " @io.github.eschizoid.telescope.annotations.ViaMapper(field = \"children\", using = WlListMap.class))\n" +
          "public record WlSrc<X>(java.util.List<X> children) {}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + ".WlTgt",
        "package " + PACKAGE + ";\npublic record WlTgt<Y>(java.util.List<Y> children) {}\n"
      )
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + ".WlSrc");
    final var tgt = classes.get(PACKAGE + ".WlTgt");
    final var bridge = classes.get(PACKAGE + ".WlSrcBridge");
    final var children = List.of("a", "b");
    final var source = src.getDeclaredConstructor(List.class).newInstance(children);
    final var forward = bridge.getMethod("forward", src).invoke(null, source);
    assertSame(children, tgt.getMethod("children").invoke(forward));
    final var backward = bridge.getMethod("backward", tgt).invoke(null, forward);
    assertEquals(children, src.getMethod("children").invoke(backward));
  }

  @Test
  @DisplayName("an element mapper on maps whose keys differ is refused by name")
  void mapsWhoseKeysDifferAreRefused() {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source(PACKAGE + ".WkCh", "package " + PACKAGE + ";\npublic record WkCh(String name) {}\n"),
      ProcessorHarness.source(
        PACKAGE + ".WkChDto",
        "package " + PACKAGE + ";\npublic record WkChDto(String name) {}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + ".WkChMap",
        "package " +
          PACKAGE +
          ";\npublic class WkChMap {\n" +
          "  public static WkChDto forward(final WkCh c) { return new WkChDto(c.name()); }\n" +
          "  public static WkCh backward(final WkChDto d) { return new WkCh(d.name()); }\n}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + ".WkSrc",
        "package " +
          PACKAGE +
          ";\n" +
          "@io.github.eschizoid.telescope.annotations.Bridge(value = WkTgt.class, viaMappers =" +
          " @io.github.eschizoid.telescope.annotations.ViaMapper(field = \"children\", using = WkChMap.class))\n" +
          "public record WkSrc(java.util.Map<String, WkCh> children) {}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + ".WkTgt",
        "package " + PACKAGE + ";\npublic record WkTgt(java.util.Map<Integer, WkChDto> children) {}\n"
      )
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(
        "@Bridge WkSrc -> WkTgt: field 'children' has incompatible Map key types — java.lang.String vs" +
          " java.lang.Integer. Map key types must match exactly; codegen preserves source keys."
      ),
      compilation::errorMessages
    );
  }

  /** The child pair {@code %1$sCh} and {@code %1$sChDto}. */
  private static List<javax.tools.JavaFileObject> childPair(final String prefix) {
    return List.of(
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Ch",
        "package " + PACKAGE + ";\npublic record " + prefix + "Ch(String name) {}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "ChDto",
        "package " + PACKAGE + ";\npublic record " + prefix + "ChDto(String name) {}\n"
      )
    );
  }

  /**
   * {@code sources} beside the child pair, a mapper class {@code %1$sChMap} with {@code body}, a
   * source record {@code %1$sSrc} declared {@code srcHeader} with {@code srcComponents}, whose
   * {@code children} the mapper converts, and a target record {@code %1$sTgt} with {@code
   * tgtComponents}, compiled with the bridge processor.
   */
  private static ProcessorHarness.Compilation compiled(
    final String prefix,
    final String body,
    final String srcHeader,
    final String srcComponents,
    final String tgtComponents
  ) {
    final var sources = new java.util.ArrayList<>(childPair(prefix));
    sources.add(
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "ChMap",
        ("package " + PACKAGE + ";\npublic class %1$sChMap {\n" + body + "}\n").formatted(prefix)
      )
    );
    sources.add(
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Src",
        (
          "package " +
          PACKAGE +
          ";\n" +
          "@io.github.eschizoid.telescope.annotations.Bridge(value = %1$sTgt.class, viaMappers =" +
          " @io.github.eschizoid.telescope.annotations.ViaMapper(field = \"children\", using = %1$sChMap.class))\n" +
          "public record " +
          srcHeader +
          "(" +
          srcComponents +
          ") {}\n"
        ).formatted(prefix)
      )
    );
    sources.add(
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Tgt",
        ("package " + PACKAGE + ";\npublic record %1$sTgt(" + tgtComponents + ") {}\n").formatted(prefix)
      )
    );
    return ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      sources.toArray(javax.tools.JavaFileObject[]::new)
    );
  }

  @Test
  @DisplayName("a mapper of a list into one value converts the whole property")
  void listIntoOneValue() throws ReflectiveOperationException {
    final var compilation = compiled(
      "Wv",
      "  public static String forward(final java.util.List<%1$sCh> l) { return l.size() + \"\"; }\n" +
        "  public static java.util.List<%1$sCh> backward(final String n) { return java.util.List.of(); }\n",
      "WvSrc",
      "java.util.List<WvCh> children",
      "String children"
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + ".WvSrc");
    final var ch = classes.get(PACKAGE + ".WvCh").getDeclaredConstructor(String.class).newInstance("a");
    final var forward = classes
      .get(PACKAGE + ".WvSrcBridge")
      .getMethod("forward", src)
      .invoke(null, src.getDeclaredConstructor(List.class).newInstance(List.of(ch, ch)));
    assertEquals("2", forward.getClass().getMethod("children").invoke(forward));
  }

  @Test
  @DisplayName("a mapper of a list into a set converts the whole property")
  void listIntoASet() throws ReflectiveOperationException {
    final var compilation = compiled(
      "Ws",
      "  public static java.util.Set<%1$sChDto> forward(final java.util.List<%1$sCh> l) {" +
        " return java.util.Set.of(new %1$sChDto(\"n\" + l.size())); }\n" +
        "  public static java.util.List<%1$sCh> backward(final java.util.Set<%1$sChDto> s) { return java.util.List.of(); }\n",
      "WsSrc",
      "java.util.List<WsCh> children",
      "java.util.Set<WsChDto> children"
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + ".WsSrc");
    final var ch = classes.get(PACKAGE + ".WsCh").getDeclaredConstructor(String.class).newInstance("a");
    final var forward = classes
      .get(PACKAGE + ".WsSrcBridge")
      .getMethod("forward", src)
      .invoke(null, src.getDeclaredConstructor(List.class).newInstance(List.of(ch)));
    final var children = (java.util.Set<?>) forward.getClass().getMethod("children").invoke(forward);
    assertEquals("n1", children.iterator().next().getClass().getMethod("name").invoke(children.iterator().next()));
  }

  @Test
  @DisplayName("an element mapper with overloads for Object besides the elements calls the elements' overloads")
  void overloadForObjectBesideTheElement() throws ReflectiveOperationException {
    final var compilation = compiled(
      "Wo",
      "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name().toUpperCase()); }\n" +
        "  public static %1$sChDto forward(final Object o) { return new %1$sChDto(\"o\"); }\n" +
        "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name().toLowerCase()); }\n" +
        "  public static %1$sCh backward(final Object o) { return new %1$sCh(\"o\"); }\n",
      "WoSrc",
      "java.util.List<WoCh> children",
      "java.util.List<WoChDto> children"
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + ".WoSrc");
    final var ch = classes.get(PACKAGE + ".WoCh").getDeclaredConstructor(String.class).newInstance("a");
    final var forward = classes
      .get(PACKAGE + ".WoSrcBridge")
      .getMethod("forward", src)
      .invoke(null, src.getDeclaredConstructor(List.class).newInstance(List.of(ch)));
    final var children = (List<?>) forward.getClass().getMethod("children").invoke(forward);
    assertEquals("A", children.getFirst().getClass().getMethod("name").invoke(children.getFirst()));
  }

  @Test
  @DisplayName("a list of a variable with two forwards either of which could take the element is refused by name")
  void twoForwardsForAVariableElementAreRefused() {
    final var compilation = compiled(
      "Wt",
      "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name()); }\n" +
        "  public static %1$sChDto forward(final String o) { return new %1$sChDto(o); }\n" +
        "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name()); }\n",
      "WtSrc<X>",
      "java.util.List<X> children",
      "java.util.List<WtChDto> children"
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(
        "@ViaMapper field=\"children\": " +
          PACKAGE +
          ".WtChMap's forward has several overloads that take an element of java.util.List<X>, so the one an" +
          " element's class calls for cannot be chosen when the bridge is compiled"
      ),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("an element forward with a backward of the whole list is not lifted, and is refused by name")
  void backwardOfTheWholeListIsNotLifted() {
    final var compilation = compiled(
      "Wb",
      "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name()); }\n" +
        "  @SuppressWarnings(\"rawtypes\") public static java.util.List backward(final java.util.List l) { return l; }\n",
      "WbSrc<X>",
      "java.util.List<X> children",
      "java.util.List<WbChDto> children"
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(PairingMessages.viaMapperNeverFits("WbSrc", "WbTgt", "source", "children", "WbCh")),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("a list of a variable whose bound excludes the element mapper's parameter is refused by name")
  void boundExcludingTheParameterIsNotLifted() {
    final var compilation = compiled(
      "Wn",
      "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name()); }\n" +
        "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name()); }\n",
      "WnSrc<X extends Number>",
      "java.util.List<X> children",
      "java.util.List<WnChDto> children"
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(PairingMessages.viaMapperNeverFits("WnSrc", "WnTgt", "source", "children", "WnCh")),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("on a list of a variable, an overload for Object that takes the whole list is no element method")
  void objectOverloadOnAVariableElementIsNoElementMethod() throws ReflectiveOperationException {
    final var compilation = compiled(
      "Wj",
      "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name().toUpperCase()); }\n" +
        "  public static %1$sChDto forward(final Object o) { return new %1$sChDto(\"o\"); }\n" +
        "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name().toLowerCase()); }\n",
      "WjSrc<X>",
      "java.util.List<X> children",
      "java.util.List<WjChDto> children"
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + ".WjSrc");
    final var ch = classes.get(PACKAGE + ".WjCh").getDeclaredConstructor(String.class).newInstance("a");
    final var forward = classes
      .get(PACKAGE + ".WjSrcBridge")
      .getMethod("forward", src)
      .invoke(null, src.getDeclaredConstructor(List.class).newInstance(List.of(ch)));
    final var children = (List<?>) forward.getClass().getMethod("children").invoke(forward);
    assertEquals("A", children.getFirst().getClass().getMethod("name").invoke(children.getFirst()));
  }

  @Test
  @DisplayName("a mapper with a whole-list forward but only an element backward converts each element")
  void wholeForwardWithElementBackwardIsLifted() throws ReflectiveOperationException {
    final var compilation = compiled(
      "Wf",
      "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name().toUpperCase()); }\n" +
        "  public static java.util.List<%1$sChDto> forward(final java.util.List<%1$sCh> l) { return java.util.List.of(); }\n" +
        "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name().toLowerCase()); }\n",
      "WfSrc",
      "java.util.List<WfCh> children",
      "java.util.List<WfChDto> children"
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + ".WfSrc");
    final var ch = classes.get(PACKAGE + ".WfCh").getDeclaredConstructor(String.class).newInstance("a");
    final var forward = classes
      .get(PACKAGE + ".WfSrcBridge")
      .getMethod("forward", src)
      .invoke(null, src.getDeclaredConstructor(List.class).newInstance(List.of(ch)));
    final var children = (List<?>) forward.getClass().getMethod("children").invoke(forward);
    assertEquals("A", children.getFirst().getClass().getMethod("name").invoke(children.getFirst()));
  }

  @Test
  @DisplayName(
    "a list of a variable on the target with two backwards either of which could take the element is refused by name"
  )
  void twoBackwardsForAVariableElementAreRefused() {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      childPair("Wr").get(0),
      childPair("Wr").get(1),
      ProcessorHarness.source(
        PACKAGE + ".WrChMap",
        "package " +
          PACKAGE +
          ";\npublic class WrChMap {\n" +
          "  public static WrChDto forward(final WrCh c) { return new WrChDto(c.name()); }\n" +
          "  public static WrCh backward(final WrChDto d) { return new WrCh(d.name()); }\n" +
          "  public static WrCh backward(final String s) { return new WrCh(s); }\n}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + ".WrSrc",
        "package " +
          PACKAGE +
          ";\n" +
          "@io.github.eschizoid.telescope.annotations.Bridge(value = WrTgt.class, viaMappers =" +
          " @io.github.eschizoid.telescope.annotations.ViaMapper(field = \"children\", using = WrChMap.class))\n" +
          "public record WrSrc(java.util.List<WrCh> children) {}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + ".WrTgt",
        "package " + PACKAGE + ";\npublic record WrTgt<Y>(java.util.List<Y> children) {}\n"
      )
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(
        "@ViaMapper field=\"children\": " +
          PACKAGE +
          ".WrChMap's backward has several overloads that take an element of java.util.List<Y>, so the one an" +
          " element's class calls for cannot be chosen when the bridge is compiled"
      ),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("a mapper whose whole-list overloads return an inferred result converts the whole property")
  void wholeListOverloadsWithAnInferredResult() throws ReflectiveOperationException {
    final var compilation = compiled(
      "Wi",
      "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(\"f\" + c.name()); }\n" +
        "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(\"b\" + d.name()); }\n" +
        "  @SuppressWarnings(\"unchecked\") public static <R> R forward(final java.util.List<%1$sCh> l) {" +
        " return (R) java.util.List.of(new %1$sChDto(\"whole\")); }\n" +
        "  @SuppressWarnings(\"unchecked\") public static <R> R backward(final java.util.List<%1$sChDto> l) {" +
        " return (R) java.util.List.of(new %1$sCh(\"whole\")); }\n",
      "WiSrc",
      "java.util.List<WiCh> children",
      "java.util.List<WiChDto> children"
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + ".WiSrc");
    final var ch = classes.get(PACKAGE + ".WiCh").getDeclaredConstructor(String.class).newInstance("a");
    final var forward = classes
      .get(PACKAGE + ".WiSrcBridge")
      .getMethod("forward", src)
      .invoke(null, src.getDeclaredConstructor(List.class).newInstance(List.of(ch)));
    final var children = (List<?>) forward.getClass().getMethod("children").invoke(forward);
    assertEquals("whole", children.getFirst().getClass().getMethod("name").invoke(children.getFirst()));
  }

  @Test
  @DisplayName(
    "on beans fixing a generic base's list, whole-list overloads returning an inferred result convert the whole property"
  )
  void inferredWholeListOverloadsThroughAGenericBase() throws ReflectiveOperationException {
    final var head = "package " + PACKAGE + ";\n";
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      childPair("Wh").get(0),
      childPair("Wh").get(1),
      ProcessorHarness.source(
        PACKAGE + ".WhBase",
        head +
          "public class WhBase<T> {\n" +
          "  private java.util.List<T> v;\n" +
          "  public java.util.List<T> getV() { return v; }\n" +
          "  public void setV(final java.util.List<T> v) { this.v = v; }\n}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + ".WhChMap",
        head +
          "@SuppressWarnings(\"unchecked\")\npublic class WhChMap {\n" +
          "  public static WhChDto forward(final WhCh c) { return new WhChDto(\"f:\" + c.name()); }\n" +
          "  public static WhCh backward(final WhChDto d) { return new WhCh(\"b:\" + d.name()); }\n" +
          "  public static <R> R forward(final java.util.List<WhCh> l) {" +
          " return (R) l.stream().map(c -> new WhChDto(\"GW:\" + c.name())).toList(); }\n" +
          "  public static <R> R backward(final java.util.List<WhChDto> l) {" +
          " return (R) l.stream().map(d -> new WhCh(\"GWB:\" + d.name())).toList(); }\n}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + ".WhSrc",
        head +
          "@io.github.eschizoid.telescope.annotations.Bridge(value = WhTgt.class, viaMappers =" +
          " @io.github.eschizoid.telescope.annotations.ViaMapper(field = \"v\", using = WhChMap.class))\n" +
          "public class WhSrc extends WhBase<WhCh> { public WhSrc() {} }\n"
      ),
      ProcessorHarness.source(
        PACKAGE + ".WhTgt",
        head + "public class WhTgt extends WhBase<WhChDto> { public WhTgt() {} }\n"
      )
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + ".WhSrc");
    final var base = classes.get(PACKAGE + ".WhBase");
    final var source = src.getDeclaredConstructor().newInstance();
    base
      .getMethod("setV", List.class)
      .invoke(source, List.of(classes.get(PACKAGE + ".WhCh").getDeclaredConstructor(String.class).newInstance("a")));
    final var forward = classes.get(PACKAGE + ".WhSrcBridge").getMethod("forward", src).invoke(null, source);
    final var children = (List<?>) base.getMethod("getV").invoke(forward);
    assertEquals("GW:a", children.getFirst().getClass().getMethod("name").invoke(children.getFirst()));
  }

  @Test
  @DisplayName(
    "whole-list overloads with a bounded inferred result still convert the whole property, as javac binds them"
  )
  void boundedInferredResultConvertsTheWholeProperty() throws ReflectiveOperationException {
    final var compilation = compiled(
      "Wu",
      "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name().toUpperCase()); }\n" +
        "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name().toLowerCase()); }\n" +
        "  public static <R extends Number> R forward(final java.util.List<%1$sCh> l) { return null; }\n" +
        "  public static <R extends Number> R backward(final java.util.List<%1$sChDto> l) { return null; }\n",
      "WuSrc",
      "java.util.List<WuCh> children",
      "java.util.List<WuChDto> children"
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + ".WuSrc");
    final var ch = classes.get(PACKAGE + ".WuCh").getDeclaredConstructor(String.class).newInstance("a");
    final var forward = classes
      .get(PACKAGE + ".WuSrcBridge")
      .getMethod("forward", src)
      .invoke(null, src.getDeclaredConstructor(List.class).newInstance(List.of(ch)));
    assertNull(forward.getClass().getMethod("children").invoke(forward));
  }

  @Test
  @DisplayName("whole-list overloads whose inferred result is bounded by Comparable convert the whole property")
  void comparableBoundedInferredResultConvertsTheWholeProperty() throws ReflectiveOperationException {
    final var head = "package " + PACKAGE + ";\n";
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      childPair("Wk").get(0),
      childPair("Wk").get(1),
      ProcessorHarness.source(
        PACKAGE + ".WkCl",
        head +
          "public class WkCl<E> extends java.util.ArrayList<E> implements Comparable<WkCl<E>> {\n" +
          "  public WkCl() {}\n" +
          "  @Override public int compareTo(final WkCl<E> o) { return Integer.compare(size(), o.size()); }\n}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + ".WkChMap",
        head +
          "@SuppressWarnings(\"unchecked\")\npublic class WkChMap {\n" +
          "  public static WkChDto forward(final WkCh c) { return new WkChDto(\"f:\" + c.name()); }\n" +
          "  public static WkCh backward(final WkChDto d) { return new WkCh(\"b:\" + d.name()); }\n" +
          "  public static <R extends Comparable<R>> R forward(final java.util.List<WkCh> l) {\n" +
          "    final var out = new WkCl<WkChDto>();\n" +
          "    for (final var c : l) out.add(new WkChDto(\"RK:\" + c.name()));\n" +
          "    return (R) out;\n  }\n" +
          "  public static <R extends Comparable<R>> R backward(final java.util.List<WkChDto> l) {\n" +
          "    final var out = new WkCl<WkCh>();\n" +
          "    for (final var d : l) out.add(new WkCh(\"RKB:\" + d.name()));\n" +
          "    return (R) out;\n  }\n}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + ".WkSrc",
        head +
          "@io.github.eschizoid.telescope.annotations.Bridge(value = WkTgt.class, viaMappers =" +
          " @io.github.eschizoid.telescope.annotations.ViaMapper(field = \"children\", using = WkChMap.class))\n" +
          "public record WkSrc(java.util.List<WkCh> children) {}\n"
      ),
      ProcessorHarness.source(PACKAGE + ".WkTgt", head + "public record WkTgt(java.util.List<WkChDto> children) {}\n")
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var src = classes.get(PACKAGE + ".WkSrc");
    final var ch = classes.get(PACKAGE + ".WkCh").getDeclaredConstructor(String.class).newInstance("a");
    final var forward = classes
      .get(PACKAGE + ".WkSrcBridge")
      .getMethod("forward", src)
      .invoke(null, src.getDeclaredConstructor(List.class).newInstance(List.of(ch)));
    final var children = (List<?>) forward.getClass().getMethod("children").invoke(forward);
    assertSame(classes.get(PACKAGE + ".WkCl"), children.getClass());
    assertEquals("RK:a", children.getFirst().getClass().getMethod("name").invoke(children.getFirst()));
  }

  @Test
  @DisplayName(
    "whole-list overloads whose result is bounded by the final class String bind the whole property and are refused by name"
  )
  void stringBoundedInferredResultBindsTheWholeProperty() {
    // A variable bounded by a final class holds only that class, so the overload's result is a
    // String, which the target's List<WzChDto> never takes: refused, not lifted over the elements.
    final var compilation = compiled(
      "Wz",
      "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name().toUpperCase()); }\n" +
        "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name().toLowerCase()); }\n" +
        "  @SuppressWarnings(\"unchecked\") public static <R extends String> R forward(final java.util.List<%1$sCh> l) {" +
        " return (R) (Object) l; }\n" +
        "  @SuppressWarnings(\"unchecked\") public static <R extends String> R backward(final java.util.List<%1$sChDto> l) {" +
        " return (R) (Object) l; }\n",
      "WzSrc",
      "java.util.List<WzCh> children",
      "java.util.List<WzChDto> children"
    );
    assertFalse(compilation.success(), "a result bounded by String cannot be written into a list");
    assertTrue(
      compilation.hasError(
        "@ViaMapper field=\"children\": " +
          PACKAGE +
          ".WzChMap's forward returns R, which no instantiation of its type variables lets the member WzTgt" +
          " writes children through take"
      ),
      compilation::errorMessages
    );
  }

  /**
   * Compiles a @ViaMapper whose mapper class {@code %1$sChMap} declares {@code body}, on a source
   * whose {@code children} is {@code srcType} and a target whose {@code children} is {@code
   * tgtType}, and returns the refusal the processor reports for a mapper no value fits, or null.
   */
  private static String neverFits(final String prefix, final String body, final String srcType, final String tgtType) {
    final var compilation = compiled(prefix, body, prefix + "Src", srcType + " children", tgtType + " children");
    return compilation
      .errors()
      .stream()
      .map(d -> d.getMessage(java.util.Locale.ROOT))
      .filter(m -> m.contains("never holds"))
      .findFirst()
      .orElse(null);
  }

  @Test
  @DisplayName("a mapper of an array refused against a List names the array")
  void arrayParameterIsNamed() {
    assertEquals(
      PairingMessages.viaMapperNeverFits("WaSrc", "WaTgt", "source", "children", PACKAGE + ".WaCh[]"),
      neverFits(
        "Wa",
        "  public static %1$sChDto forward(final %1$sCh[] c) { return null; }\n" +
          "  public static %1$sCh[] backward(final %1$sChDto d) { return null; }\n",
        "java.util.List<WaCh>",
        "WaChDto"
      )
    );
  }

  @Test
  @DisplayName("a mapper of a sealed interface none of whose cases a class can be is refused")
  void sealedParameterIsRefused() {
    assertEquals(
      PairingMessages.viaMapperNeverFits("WsrSrc", "WsrTgt", "source", "children", "Shape"),
      neverFits(
        "Wsr",
        "  public sealed interface Shape permits Circle {}\n" +
          "  public record Circle() implements Shape {}\n" +
          "  public static class Plain { public Plain() {} }\n" +
          "  public static %1$sChDto forward(final Shape s) { return null; }\n" +
          "  public static Shape backward(final %1$sChDto d) { return null; }\n",
        "WsrChMap.Plain",
        "WsrChDto"
      )
    );
  }

  @Test
  @DisplayName("a mapper of an interface a record property does not implement is refused")
  void interfaceParameterOnARecordIsRefused() {
    assertEquals(
      PairingMessages.viaMapperNeverFits("WrcSrc", "WrcTgt", "source", "children", "Named"),
      neverFits(
        "Wrc",
        "  public interface Named {}\n" +
          "  public static %1$sChDto forward(final Named n) { return null; }\n" +
          "  public static Named backward(final %1$sChDto d) { return null; }\n",
        "WrcCh",
        "WrcChDto"
      )
    );
  }

  @Test
  @DisplayName(
    "a mapper of an enum whose constant declares a body, handed an interface the enum does not implement, is refused"
  )
  void enumParameterOnAnInterfaceIsRefused() {
    assertEquals(
      PairingMessages.viaMapperNeverFits("WenSrc", "WenTgt", "source", "children", "Kind"),
      neverFits(
        "Wen",
        "  public interface Named {}\n" +
          "  public enum Kind { A { @Override public String toString() { return \"a\"; } } }\n" +
          "  public static %1$sChDto forward(final Kind k) { return null; }\n" +
          "  public static Kind backward(final %1$sChDto d) { return null; }\n",
        "WenChMap.Named",
        "WenChDto"
      )
    );
  }

  @Test
  @DisplayName(
    "a mapper of one interface handed another is not refused as never fitting, since a class can implement both"
  )
  void interfaceParameterOnAnInterfaceIsNotRefused() {
    assertNull(
      neverFits(
        "Wii",
        "  public interface Named {}\n" +
          "  public interface Other {}\n" +
          "  public static %1$sChDto forward(final Other o) { return null; }\n" +
          "  public static Other backward(final %1$sChDto d) { return null; }\n",
        "WiiChMap.Named",
        "WiiChDto"
      )
    );
  }

  @Test
  @DisplayName("a mapper class with no forward is refused by name")
  void mapperWithNoForwardIsRefused() {
    final var compilation = compiled(
      "Wnf",
      "  public static %1$sCh backward(final %1$sChDto d) { return null; }\n",
      "WnfSrc",
      "java.util.List<WnfCh> children",
      "WnfChDto children"
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError("@ViaMapper field=\"children\": " + PACKAGE + ".WnfChMap declares no forward"),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("a mapper of an interface handed a class that is not final is not refused as never fitting")
  void interfaceParameterOnANonFinalClassIsNotRefused() {
    assertNull(
      neverFits(
        "Wnc",
        "  public interface Named {}\n" +
          "  public static class Plain { public Plain() {} }\n" +
          "  public static %1$sChDto forward(final Named n) { return null; }\n" +
          "  public static Named backward(final %1$sChDto d) { return null; }\n",
        "WncChMap.Plain",
        "WncChDto"
      )
    );
  }

  @Test
  @DisplayName("a mapper of an interface handed an enum property that does not implement it is refused")
  void interfaceParameterOnAnEnumIsRefused() {
    assertEquals(
      PairingMessages.viaMapperNeverFits("WneSrc", "WneTgt", "source", "children", "Named"),
      neverFits(
        "Wne",
        "  public interface Named {}\n" +
          "  public enum Kind { A }\n" +
          "  public static %1$sChDto forward(final Named n) { return null; }\n" +
          "  public static Named backward(final %1$sChDto d) { return null; }\n",
        "WneChMap.Kind",
        "WneChDto"
      )
    );
  }

  @Test
  @DisplayName("a property of an unbounded variable with an overload taking a primitive is refused by name")
  void propertyOverloadTakingAPrimitiveIsRefused() {
    final var compilation = compiled(
      "Wp",
      "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name()); }\n" +
        "  public static %1$sChDto forward(final int n) { return null; }\n" +
        "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name()); }\n",
      "WpSrc<X>",
      "X children",
      "WpChDto children"
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError("@ViaMapper field=\"children\": " + PACKAGE + ".WpChMap's forward has several overloads"),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("an Object property whose mapper has two methods a value could reach is refused by name")
  void objectPropertyWithTwoReachableMethodsIsRefused() {
    final var compilation = compiled(
      "Wo2",
      "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name()); }\n" +
        "  public static %1$sChDto forward(final String s) { return null; }\n" +
        "  public static %1$sCh backward(final %1$sChDto d) { return new %1$sCh(d.name()); }\n",
      "Wo2Src",
      "Object children",
      "Wo2ChDto children"
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(
        "@ViaMapper field=\"children\": " + PACKAGE + ".Wo2ChMap's forward takes no value of java.lang.Object"
      ),
      compilation::errorMessages
    );
  }

  @Test
  @DisplayName("a mapper class with no backward is refused by name")
  void mapperWithNoBackwardIsRefused() {
    final var compilation = compiled(
      "Wnb",
      "  public static %1$sChDto forward(final %1$sCh c) { return new %1$sChDto(c.name()); }\n",
      "WnbSrc",
      "WnbCh children",
      "WnbChDto children"
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError("@ViaMapper field=\"children\": " + PACKAGE + ".WnbChMap declares no backward"),
      compilation::errorMessages
    );
  }
}
