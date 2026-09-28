package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
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
 * One input through both paths, for every cell of a generated grid.
 *
 * <p>Every parity gate in this repository is written by hand, and a hand-written gate covers the
 * shapes whoever wrote it thought of. Four separate ones have been green while the defect they were
 * written for was live, each time because the fixture varied a dimension the rule does not branch
 * on. Generating the grid removes the author's sample from the decision.
 *
 * <p>A cell agrees when both paths return the same value, or when both refuse. A cell disagrees
 * when one path returns a value the other does not, or when one refuses and the other does not, and
 * a disagreement is a defect unless {@link #KNOWN_DIVERGENCES} carries it with a reason.
 *
 * <p>Every cell's types are declared in this test's own package with a per-cell name prefix,
 * because the classes are defined through this class's lookup: that is what puts them in a module
 * the runtime accessor substrate can read, and it holds each name once.
 */
class CrossPathCorpusTest {

  /**
   * A container kind: the interface a field declares, the implementation it may name, the class
   * both paths have to allocate for it, and how a rebuilt one with two elements renders.
   */
  private record Kind(String iface, String impl, String allocates, String items) {}

  /** What the field's declared type is, which is a separate question from the kind. */
  private record Form(String name, boolean concrete) {}

  /**
   * The element type a cell's container holds. {@code rendered} takes the cell's name prefix and
   * one element's value, so a cell can say what its own converted elements look like.
   */
  private record Element(String name, String srcType, String tgtType, String rendered) {}

  private static final Map<String, Kind> KINDS = new LinkedHashMap<>(
    Map.of(
      "list",
      new Kind("java.util.List<%s>", "java.util.ArrayList<%s>", "java.util.ArrayList", "[%s, %s]"),
      "set",
      new Kind("java.util.Set<%s>", "java.util.LinkedHashSet<%s>", "java.util.LinkedHashSet", "[%s, %s]"),
      "map",
      new Kind(
        "java.util.Map<java.lang.String, %s>",
        "java.util.LinkedHashMap<java.lang.String, %s>",
        "java.util.LinkedHashMap",
        "{k1=%s, k2=%s}"
      )
    )
  );

  private static final List<Form> FORMS = List.of(new Form("iface", false), new Form("concrete", true));

  private static final List<Element> ELEMENTS = List.of(
    new Element("scalar", "java.lang.String", "java.lang.String", "%2$s"),
    new Element("record", "%sLeaf", "%sLeafDto", "%1$sLeafDto[v=%2$s]")
  );

  /**
   * Cells whose two paths are known to differ, each with the reason.
   *
   * <p>Empty for this grid. A cell that starts differing therefore fails rather than being
   * absorbed, and an entry here has to say why the difference is correct rather than recording that
   * it exists.
   */
  private static final Map<String, String> KNOWN_DIVERGENCES = Map.of();

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  /**
   * The two element values every cell converts, in an order the insertion-ordered containers keep
   * and a plain hash container does not. One element renders a list and a set alike, which hides an
   * allocation that picked the wrong family; two of them do not.
   */
  private static final List<String> VALUES = List.of("b", "a");

  private static JavaFileObject[] sources(
    final String prefix,
    final Kind kind,
    final Form form,
    final Element element
  ) {
    final var srcElement = element.srcType().formatted(prefix);
    final var tgtElement = element.tgtType().formatted(prefix);
    final var srcField = kind.iface().formatted(srcElement);
    final var tgtField = form.concrete() ? kind.impl().formatted(tgtElement) : kind.iface().formatted(tgtElement);
    final var head = "package " + PACKAGE + ";\n";
    return new JavaFileObject[] {
      source(prefix + "Leaf", head + "public record " + prefix + "Leaf(String v) {}\n"),
      source(prefix + "LeafDto", head + "public record " + prefix + "LeafDto(String v) {}\n"),
      source(
        prefix + "Src",
        head +
          "import io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(" +
          prefix +
          "Tgt.class)\npublic record " +
          prefix +
          "Src(" +
          srcField +
          " items) {}\n"
      ),
      source(prefix + "Tgt", head + "public record " + prefix + "Tgt(" + tgtField + " items) {}\n"),
    };
  }

  private static JavaFileObject source(final String simpleName, final String code) {
    return ProcessorHarness.source(PACKAGE + "." + simpleName, code);
  }

  /**
   * What one path produced, or the refusal it made instead.
   *
   * <p>{@code allocated} is the class of the rebuilt container rather than of the target record,
   * because an interface-typed field leaves that choice to whichever table the path consults, and
   * two tables that name different implementations both satisfy the declaration.
   */
  private record Outcome(String value, String allocated, String refusal) {
    static Outcome of(final Object produced, final Object container) {
      return new Outcome(String.valueOf(produced), container == null ? "null" : container.getClass().getName(), null);
    }

    static Outcome refused(final Throwable t) {
      final var sb = new StringBuilder(t.getClass().getSimpleName() + ": " + t.getMessage());
      for (var c = t.getCause(); c != null; c = c.getCause()) {
        sb.append(" <- ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
      }
      return new Outcome(null, null, sb.toString());
    }

    boolean agreesWith(final Outcome other) {
      return refusal == null && other.refusal == null
        ? value.equals(other.value) && allocated.equals(other.allocated)
        : refusal != null && other.refusal != null;
    }

    @Override
    public String toString() {
      return refusal == null ? value + " in " + allocated : "refused(" + refusal + ")";
    }
  }

  @Test
  @DisplayName("every generated cell is converted the same way by both paths")
  void bothPathsAgreeOnEveryCell() {
    final var disagreements = new ArrayList<String>();
    final var checked = new LinkedHashMap<String, String>();
    var index = 0;

    for (final var kind : KINDS.entrySet()) {
      for (final var form : FORMS) {
        for (final var element : ELEMENTS) {
          final var cell = kind.getKey() + "/" + form.name() + "/" + element.name();
          final var prefix = "C" + index++;
          final var compilation = ProcessorHarness.compileFully(
            List.of(new BridgeProcessor()),
            List.of(),
            sources(prefix, kind.getValue(), form, element)
          );
          assertTrue(compilation.success(), () -> cell + " should compile: " + compilation.errorMessages());

          final Outcome generated;
          final Outcome reflective;
          try {
            final var classes = compilation.define(MethodHandles.lookup());
            final var src = classes.get(PACKAGE + "." + prefix + "Src");
            final var tgt = classes.get(PACKAGE + "." + prefix + "Tgt");
            final var source = src.getConstructors()[0].newInstance(items(classes, prefix, kind.getKey(), element));
            final var items = tgt.getMethod("items");

            generated = run(items, () ->
              classes.get(PACKAGE + "." + prefix + "SrcBridge").getMethod("forward", src).invoke(null, source)
            );
            reflective = run(items, () -> Telescope.mapper(cast(src), cast(tgt)).forward(source));
          } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(cell + " could not be built", e);
          }

          final var expected = expected(prefix, kind.getValue(), element);
          checked.put(cell, generated + " | " + reflective);
          if (generated.agreesWith(reflective)) {
            if (!expected.equals(generated.toString()) && KNOWN_DIVERGENCES.get(cell) == null) {
              disagreements.add(cell + ": both paths returned " + generated + ", expected " + expected);
            }
            continue;
          }
          if (KNOWN_DIVERGENCES.get(cell) == null) {
            disagreements.add(cell + ": generated " + generated + ", reflective " + reflective);
          }
        }
      }
    }

    assertTrue(
      checked.size() == KINDS.size() * FORMS.size() * ELEMENTS.size(),
      () -> "every cell should be reached, saw " + checked.keySet()
    );
    assertTrue(
      disagreements.isEmpty(),
      () -> disagreements.size() + " cell(s) disagree:\n  " + String.join("\n  ", disagreements)
    );
  }

  /**
   * The one rendering a cell's two paths both have to produce: the target record with its rebuilt
   * container, and the class that container has to be.
   */
  private static String expected(final String prefix, final Kind kind, final Element element) {
    final var rendered = VALUES.stream()
      .map(v -> element.rendered().formatted(prefix, v))
      .toArray();
    return prefix + "Tgt[items=" + kind.items().formatted(rendered) + "] in " + kind.allocates();
  }

  private static Object items(
    final Map<String, Class<?>> classes,
    final String prefix,
    final String kind,
    final Element element
  ) throws ReflectiveOperationException {
    final var leaves = new ArrayList<>();
    for (final var value : VALUES) {
      leaves.add(
        element.name().equals("record")
          ? classes.get(PACKAGE + "." + prefix + "Leaf").getConstructor(String.class).newInstance(value)
          : value
      );
    }
    return switch (kind) {
      case "list" -> new ArrayList<>(leaves);
      case "set" -> new LinkedHashSet<>(leaves);
      default -> {
        final var map = new LinkedHashMap<String, Object>();
        for (var i = 0; i < leaves.size(); i++) map.put("k" + (i + 1), leaves.get(i));
        yield map;
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> cast(final Class<?> type) {
    return (Class<T>) type;
  }

  private interface Attempt {
    Object get() throws ReflectiveOperationException;
  }

  private static Outcome run(final java.lang.reflect.Method items, final Attempt attempt) {
    try {
      final var produced = attempt.get();
      return Outcome.of(produced, items.invoke(produced));
    } catch (final InvocationTargetException e) {
      return Outcome.refused(e.getCause() == null ? e : e.getCause());
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return Outcome.refused(e);
    }
  }
}
