package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A {@code writeBean} or {@code writeBeans} hint among a {@code @Bridge} field's rows: the
 * generated bridge rebuilds the side the hint names through the strategy it names, as the runtime
 * mapper built from the same rows does, and a hint the bridge has no form for is refused by name.
 */
class WriteHintRowTest {

  private static final String HEADER =
    "package wh;\n" +
    "import static io.github.eschizoid.telescope.mapping.Mapping.*;\n" +
    "import static io.github.eschizoid.telescope.mapping.WriteHint.*;\n" +
    "import io.github.eschizoid.telescope.Telescope;\n" +
    "import io.github.eschizoid.telescope.annotations.Bridge;\n" +
    "import io.github.eschizoid.telescope.conversion.*;\n" +
    "import io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy;\n";

  /**
   * A bean built through its builder or through its setters, which records which one built it in a
   * member no property reads.
   */
  private static String bean(final String name) {
    return (
      "package wh;\n" +
      "public class " +
      name +
      " {\n" +
      "  private String name;\n" +
      "  private String how = \"setters\";\n" +
      "  public " +
      name +
      "() {}\n" +
      "  public String getName() { return name; }\n" +
      "  public void setName(final String name) { this.name = name; }\n" +
      "  public String how() { return how; }\n" +
      "  public static Builder builder() { return new Builder(); }\n" +
      "  public static final class Builder {\n" +
      "    private String name;\n" +
      "    public Builder name(final String name) { this.name = name; return this; }\n" +
      "    public " +
      name +
      " build() { final var b = new " +
      name +
      "(); b.name = name; b.how = \"builder\"; return b; }\n" +
      "  }\n" +
      "}\n"
    );
  }

  private static final List<String> MODEL = List.of(
    "wh.Src",
    "package wh; public record Src(String name) {}",
    "wh.Acct",
    bean("Acct"),
    "wh.Ledger",
    bean("Ledger"),
    "wh.Dto",
    "package wh; public record Dto(String name) {}",
    "wh.Outer",
    "package wh; public record Outer(Acct inner) {}",
    "wh.OuterDto",
    "package wh; public record OuterDto(Ledger inner) {}",
    "wh.Many",
    "package wh; public record Many(java.util.List<Acct> inner) {}",
    "wh.ManyDto",
    "package wh; public record ManyDto(java.util.List<Ledger> inner) {}",
    "wh.Half",
    "package wh; public record Half(Src inner) {}",
    "wh.HalfDto",
    "package wh; public record HalfDto(Acct inner) {}",
    "wh.Deep",
    "package wh; public record Deep(Half inner) {}",
    "wh.DeepDto",
    "package wh; public record DeepDto(HalfDto inner) {}",
    "wh.Sh",
    "package wh; public sealed interface Sh permits ShA {}",
    "wh.ShA",
    bean("ShA").replace(
      "public class ShA {",
      "@io.github.eschizoid.telescope.annotations.Bridge(ShADto.class)\npublic final class ShA implements Sh {"
    ),
    "wh.ShDto",
    "package wh; public sealed interface ShDto permits ShADto {}",
    "wh.ShADto",
    "package wh; public record ShADto(String name) implements ShDto {}",
    "wh.OuterS",
    "package wh; public record OuterS(Sh inner) {}",
    "wh.OuterSDto",
    "package wh; public record OuterSDto(ShDto inner) {}",
    "wh.HalfB",
    "package wh; @io.github.eschizoid.telescope.annotations.Bridge(HalfBDto.class) public record HalfB(Acct src) {}",
    "wh.HalfBDto",
    "package wh; public record HalfBDto(Ledger src) {}",
    "wh.DeepB",
    "package wh; public record DeepB(HalfB inner) {}",
    "wh.DeepBDto",
    "package wh; public record DeepBDto(HalfBDto inner) {}",
    "wh.HalfF",
    HEADER +
      "public record HalfF(Acct src) {\n" +
      "  @Bridge static final Mapper<HalfF, HalfFDto> OWN = Telescope.mapper(HalfF.class, HalfFDto.class);\n" +
      "}\n",
    "wh.HalfFDto",
    "package wh; public record HalfFDto(Ledger src) {}",
    "wh.DeepF",
    "package wh; public record DeepF(HalfF inner) {}",
    "wh.DeepFDto",
    "package wh; public record DeepFDto(HalfFDto inner) {}",
    "wh.Sr",
    "package wh; public sealed interface Sr permits SrA {}",
    "wh.SrA",
    "package wh; @io.github.eschizoid.telescope.annotations.Bridge(SrADto.class) public record SrA(Acct src) implements Sr {}",
    "wh.SrDto",
    "package wh; public sealed interface SrDto permits SrADto {}",
    "wh.SrADto",
    "package wh; public record SrADto(Ledger src) implements SrDto {}",
    "wh.OuterR",
    "package wh; public record OuterR(Sr inner) {}",
    "wh.OuterRDto",
    "package wh; public record OuterRDto(SrDto inner) {}",
    "wh.Pure",
    "package wh; public sealed interface Pure permits PureA {}",
    "wh.PureA",
    "package wh; @io.github.eschizoid.telescope.annotations.Bridge(PureADto.class) public record PureA(Src src) implements Pure {}",
    "wh.PureDto",
    "package wh; public sealed interface PureDto permits PureADto {}",
    "wh.PureADto",
    "package wh; public record PureADto(Dto src) implements PureDto {}",
    "wh.OuterP",
    "package wh; public record OuterP(Pure inner, java.util.List<Src> items) {}",
    "wh.OuterPDto",
    "package wh; public record OuterPDto(PureDto inner, java.util.List<Dto> items) {}",
    "wh.Node",
    "package wh; public record Node(String name, Node next) {}",
    "wh.NodeDto",
    "package wh; public record NodeDto(String name, NodeDto next) {}",
    "wh.Wrap",
    "package wh; public record Wrap(Src inner) {}",
    "wh.WrapDto",
    "package wh; public record WrapDto(Dto inner) {}"
  );

  private static JavaFileObject[] sources(final List<String> pairs) {
    final var all = new ArrayList<JavaFileObject>();
    for (var i = 0; i < pairs.size(); i += 2) all.add(ProcessorHarness.source(pairs.get(i), pairs.get(i + 1)));
    return all.toArray(JavaFileObject[]::new);
  }

  /** The model, a class building the mapper on the runtime path, and a {@code @Bridge} field. */
  private static List<String> withMapper(final String mapper) {
    final var all = new ArrayList<>(MODEL);
    all.add("wh.Plain");
    all.add(HEADER + "public final class Plain {\n  public static Object build() { return " + mapper + "; }\n}\n");
    all.add("wh.Fields");
    all.add(
      HEADER + "public final class Fields {\n  @Bridge public static final Mapper<?, ?> GEN = " + mapper + ";\n}\n"
    );
    return all;
  }

  private static ProcessorHarness.Compilation generated(final List<String> pairs) {
    return ProcessorHarness.compileFully(List.<Processor>of(new BridgeProcessor()), List.of(), sources(pairs));
  }

  private static ProcessorHarness.Compilation runtime(final List<String> pairs) {
    final var withoutField = new ArrayList<>(pairs);
    final var at = withoutField.indexOf("wh.Fields");
    withoutField.subList(at, at + 2).clear();
    return ProcessorHarness.compileFully(List.of(), List.of(), sources(withoutField));
  }

  private static URLClassLoader load(final ProcessorHarness.Compilation c) throws IOException {
    final var dir = Files.createTempDirectory("write-hint-row");
    for (final var e : c.classes().entrySet())
      write(dir.resolve(e.getKey().replace('.', '/') + ".class"), e.getValue());
    for (final var e : c.resources().entrySet()) write(dir.resolve(e.getKey()), e.getValue().getBytes());
    return new URLClassLoader(new URL[] { dir.toUri().toURL() }, WriteHintRowTest.class.getClassLoader());
  }

  private static void write(final Path file, final byte[] bytes) throws IOException {
    Files.createDirectories(file.getParent());
    Files.write(file, bytes);
  }

  /** How the bean side of {@code converted} was built, read through its {@code how()}. */
  private static String how(final Object converted) throws Exception {
    return (String) converted.getClass().getMethod("how").invoke(converted);
  }

  static List<String[]> hintsAndHowTheyBuild() {
    return List.of(
      new String[] {
        "no hint builds the target through its builder",
        "Telescope.mapper(Src.class, Acct.class)",
        "forward",
        "builder",
      },
      new String[] {
        "writeBean keyed to the target builds it through its setters",
        "Telescope.mapper(Src.class, Acct.class, writeBean(Acct.class, WriteStrategy.SETTERS))",
        "forward",
        "setters",
      },
      new String[] {
        "writeBeans builds the target through its setters",
        "Telescope.mapper(Src.class, Acct.class, writeBeans(WriteStrategy.SETTERS))",
        "forward",
        "setters",
      },
      new String[] {
        "writeBean keyed to the source builds it through its setters on backward",
        "Telescope.mapper(Acct.class, Dto.class, writeBean(Acct.class, WriteStrategy.SETTERS))",
        "backward",
        "setters",
      },
      new String[] {
        "writeBean keyed to the target leaves the source to its builder on backward",
        "Telescope.mapper(Acct.class, Ledger.class, writeBean(Ledger.class, WriteStrategy.SETTERS))",
        "backward",
        "builder",
      },
      new String[] {
        "writeBeans builds both sides through their setters",
        "Telescope.mapper(Acct.class, Ledger.class, writeBeans(WriteStrategy.SETTERS))",
        "backward",
        "setters",
      },
      new String[] {
        "no hint rebuilds the source through its builder on patch",
        "Telescope.mapper(Acct.class, Dto.class)",
        "patch",
        "builder",
      },
      new String[] {
        "writeBean keyed to the source rebuilds it through its setters on patch",
        "Telescope.mapper(Acct.class, Dto.class, writeBean(Acct.class, WriteStrategy.SETTERS))",
        "patch",
        "setters",
      },
      new String[] {
        "writeBean keyed to the source rebuilds it through its setters on a patch with no partial",
        "Telescope.mapper(Acct.class, Dto.class, writeBean(Acct.class, WriteStrategy.SETTERS))",
        "patch with no partial",
        "setters",
      },
      new String[] {
        "writeBean keyed to the target leaves the source to its builder on patch",
        "Telescope.mapper(Acct.class, Ledger.class, writeBean(Ledger.class, WriteStrategy.SETTERS))",
        "patch",
        "builder",
      },
      new String[] {
        "writeBeans rebuilds the source through its setters on patch",
        "Telescope.mapper(Acct.class, Ledger.class, writeBeans(WriteStrategy.SETTERS))",
        "patch",
        "setters",
      },
      new String[] {
        "writeBeans rebuilds the source through its setters on a patch with no partial",
        "Telescope.mapper(Acct.class, Ledger.class, writeBeans(WriteStrategy.SETTERS))",
        "patch with no partial",
        "setters",
      }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("hintsAndHowTheyBuild")
  @DisplayName("a write hint on a @Bridge field rebuilds the side it names as the runtime path does")
  @SuppressWarnings("unchecked")
  void aWriteHintRebuildsAsTheRuntimePathDoes(
    final String shape,
    final String mapper,
    final String direction,
    final String expected
  ) throws Exception {
    final var pairs = withMapper(mapper);
    final var generated = generated(pairs);
    assertTrue(generated.success(), generated::errorMessages);
    final var runtime = runtime(pairs);
    assertTrue(runtime.success(), runtime::errorMessages);
    for (final var compilation : List.of(generated, runtime)) {
      try (final var loader = load(compilation)) {
        final var built =
          compilation == generated
            ? loader.loadClass("wh.Fields").getField("GEN").get(null)
            : loader.loadClass("wh.Plain").getMethod("build").invoke(null);
        final var m = (Mapper<Object, Object>) built;
        final var runs = m.explain().declared().map(OpticNode.Declared::runs).orElse(OpticNode.Runs.RUNTIME);
        assertEquals(compilation == generated ? OpticNode.Runs.GENERATED : OpticNode.Runs.RUNTIME, runs, shape);
        final Object converted;
        if (direction.equals("forward")) {
          converted = m.forward(loader.loadClass("wh.Src").getConstructors()[0].newInstance("a"));
        } else {
          final var acct = loader.loadClass("wh.Acct").getConstructor().newInstance();
          acct.getClass().getMethod("setName", String.class).invoke(acct, "a");
          converted = switch (direction) {
            case "backward" -> m.backward(m.forward(acct));
            case "patch" -> m.patch(acct, m.forward(acct));
            default -> m.patch(acct, null);
          };
        }
        assertEquals(
          expected,
          how(converted),
          () -> shape + " on " + (compilation == generated ? "generated" : "runtime")
        );
      }
    }
  }

  static List<String[]> hintsBothPathsRefuse() {
    return List.of(
      new String[] {
        "a writeBean keyed to a record",
        "Telescope.mapper(Acct.class, Dto.class, writeBean(Dto.class, WriteStrategy.SETTERS))",
      },
      new String[] {
        "two writeBean hints keyed to one class",
        "Telescope.mapper(Src.class, Acct.class, writeBean(Acct.class, WriteStrategy.SETTERS)," +
        " writeBean(Acct.class, WriteStrategy.BUILDER))",
      },
      new String[] {
        "two writeBeans defaults",
        "Telescope.mapper(Src.class, Acct.class, writeBeans(WriteStrategy.SETTERS), writeBeans(WriteStrategy.BUILDER))",
      }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("hintsBothPathsRefuse")
  @DisplayName("a write hint the runtime path refuses is refused by the processor in the same words")
  void aRefusedHintIsRefusedAlike(final String shape, final String mapper) throws Exception {
    final var pairs = withMapper(mapper);
    final var generated = generated(pairs);
    assertFalse(generated.success(), shape);
    final var refusals = generated
      .errors()
      .stream()
      .map(d -> d.getMessage(Locale.ROOT).replaceFirst("^In the rows of @Bridge field Fields\\.GEN: ", ""))
      .toList();
    final var runtime = runtime(pairs);
    assertTrue(runtime.success(), runtime::errorMessages);
    try (final var loader = load(runtime)) {
      final var refused = assertThrows(InvocationTargetException.class, () ->
        loader.loadClass("wh.Plain").getMethod("build").invoke(null)
      );
      final var said = refused.getCause().getMessage();
      assertTrue(
        refusals.contains(said),
        () -> shape + ": the runtime says\n  " + said + "\nthe processor\n  " + refusals
      );
    }
  }

  @Test
  @DisplayName("a writeBean keyed to a class other than the pair's sides is refused by name")
  void aHintKeyedToANestedClassIsRefused() {
    final var generated = generated(
      withMapper("Telescope.mapper(Outer.class, OuterDto.class, writeBean(Ledger.class, WriteStrategy.SETTERS))")
    );
    assertFalse(generated.success());
    assertTrue(
      generated.hasError(
        "writeBean(Ledger.class, ...) is keyed to a class other than Outer and OuterDto, a nested pair the generated" +
          " bridge has no hint for yet"
      ),
      generated::errorMessages
    );
  }

  static List<String[]> defaultsReachingANestedBean() {
    return List.of(
      new String[] {
        "Telescope.mapper(Outer.class, OuterDto.class, writeBeans(WriteStrategy.SETTERS))",
        "Acct -> Ledger",
      },
      new String[] { "Telescope.mapper(Half.class, HalfDto.class, writeBeans(WriteStrategy.SETTERS))", "Src -> Acct" },
      new String[] {
        "Telescope.mapper(Many.class, ManyDto.class, writeBeans(WriteStrategy.SETTERS))",
        "Acct -> Ledger",
      },
      new String[] { "Telescope.mapper(Deep.class, DeepDto.class, writeBeans(WriteStrategy.SETTERS))", "Src -> Acct" },
      new String[] {
        "Telescope.mapper(OuterS.class, OuterSDto.class, writeBeans(WriteStrategy.SETTERS))",
        "ShA -> ShADto",
      },
      new String[] {
        "Telescope.mapper(DeepB.class, DeepBDto.class, writeBeans(WriteStrategy.SETTERS))",
        "Acct -> Ledger",
      },
      new String[] {
        "Telescope.mapper(DeepF.class, DeepFDto.class, writeBeans(WriteStrategy.SETTERS))",
        "Acct -> Ledger",
      },
      new String[] {
        "Telescope.mapper(OuterR.class, OuterRDto.class, writeBeans(WriteStrategy.SETTERS))",
        "Acct -> Ledger",
      }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("defaultsReachingANestedBean")
  @DisplayName("a writeBeans default reaching a bean below records or through sealed cases is refused by name")
  void aDefaultReachingANestedBeanIsRefused(final String mapper, final String nested) {
    final var generated = generated(withMapper(mapper));
    assertFalse(generated.success());
    assertTrue(
      generated.hasError(
        "writeBeans(SETTERS) builds every bean the mapper reaches, and the generated bridge builds the nested pair " +
          nested +
          " through its own bridge"
      ),
      generated::errorMessages
    );
  }

  static List<String> defaultsReachingNoBean() {
    return List.of(
      "Telescope.mapper(OuterP.class, OuterPDto.class, writeBeans(WriteStrategy.SETTERS))",
      "Telescope.mapper(Node.class, NodeDto.class, writeBeans(WriteStrategy.SETTERS))"
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("defaultsReachingNoBean")
  @DisplayName("a writeBeans default reaching only records, through sealed cases, containers or a cycle, is compiled")
  void aDefaultReachingNoBeanIsCompiled(final String mapper) {
    final var generated = generated(withMapper(mapper));
    assertTrue(generated.success(), generated::errorMessages);
  }

  @Test
  @DisplayName("a writeBeans default on a pair whose nested pair is two records is compiled, as the runtime builds it")
  @SuppressWarnings("unchecked")
  void aDefaultOverNestedRecordsIsCompiled() throws Exception {
    final var pairs = withMapper("Telescope.mapper(Wrap.class, WrapDto.class, writeBeans(WriteStrategy.SETTERS))");
    final var generated = generated(pairs);
    assertTrue(generated.success(), generated::errorMessages);
    final var runtime = runtime(pairs);
    assertTrue(runtime.success(), runtime::errorMessages);
    final var converted = new ArrayList<String>();
    for (final var compilation : List.of(generated, runtime)) {
      try (final var loader = load(compilation)) {
        final var m = (Mapper<Object, Object>) (compilation == generated
          ? loader.loadClass("wh.Fields").getField("GEN").get(null)
          : loader.loadClass("wh.Plain").getMethod("build").invoke(null));
        final var runs = m.explain().declared().map(OpticNode.Declared::runs).orElse(OpticNode.Runs.RUNTIME);
        assertEquals(compilation == generated ? OpticNode.Runs.GENERATED : OpticNode.Runs.RUNTIME, runs);
        final var src = loader.loadClass("wh.Src").getConstructors()[0].newInstance("a");
        final var forward = m.forward(loader.loadClass("wh.Wrap").getConstructors()[0].newInstance(src));
        converted.add(forward + " / " + m.backward(forward));
      }
    }
    assertEquals("WrapDto[inner=Dto[name=a]] / Wrap[inner=Src[name=a]]", converted.get(0));
    assertEquals(converted.get(0), converted.get(1));
  }
}
