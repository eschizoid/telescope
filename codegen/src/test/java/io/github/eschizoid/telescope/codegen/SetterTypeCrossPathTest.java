package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bean property is read through its getter and written through its setter, and the two can take
 * different types. The getter's type is the property's read type and the setter's parameter type
 * its write type; when several methods carry the setter's name, the setter is the one Java binds a
 * value of the read type to.
 *
 * <p>A path that converts a value only to write it converts it to whichever type the setter can
 * take: fromMap converts to the write type where a value of the read type cannot be passed to the
 * setter. A path that writes a value of the read type, read back from the bean or converted for its
 * property, needs the setter to take it: a mapper or bridge filling the bean from a source, a lens,
 * a navigator, a patch. Generated code needs javac to pass the value without a cast; the reflective
 * writers also take a setter narrower than the getter, checking each value at the call as a cast
 * would. Where neither can write, both refuse the property by name, and writing any other property
 * of the same bean still works.
 *
 * <p>Each case declares a bean with a {@code count} property and a {@code name} property, and the
 * setters for {@code count} under test, and each test holds a generated path and a runtime path to
 * one answer.
 */
class SetterTypeCrossPathTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  /** What the reflective writers' refusal says for a setter that cannot take the read type. */
  private static final String CANNOT_TAKE = "so a value of the property's type cannot be written through it";

  /** What both paths' refusal says for setters none of which Java binds the value to alone. */
  private static final String AMBIGUOUS = "has several setters";

  /** Which paths can write a value of the read type through the setter the case's bean has. */
  private enum Writes {
    /** Generated code and the reflective writers. */
    BOTH,
    /** Only the reflective writers, which check each value at the call. */
    REFLECTIVE,
    /** Neither: no value of the read type can be passed to the setter. */
    NEITHER,
    /** Neither: no single setter is the one Java binds the value to. */
    AMBIGUOUS,
  }

  /**
   * A bean whose {@code count} getter returns {@code type} and whose {@code count} setters are
   * {@code setters}, written against a field of {@code type} named {@code count}.
   */
  private record Case(String name, String type, String setters, Writes writes) {
    boolean compiles() {
      return writes == Writes.BOTH;
    }

    boolean reflects() {
      return writes == Writes.BOTH || writes == Writes.REFLECTIVE;
    }

    /** What a refusal of this case says. */
    String refusal() {
      return writes == Writes.AMBIGUOUS ? AMBIGUOUS : CANNOT_TAKE;
    }
  }

  private static final List<Case> CASES = List.of(
    new Case(
      "a String setter behind an int getter",
      "int",
      "public void setCount(final String c) { this.count = c == null ? 0 : Integer.parseInt(c); }",
      Writes.NEITHER
    ),
    new Case(
      "a long setter behind an int getter",
      "int",
      "public void setCount(final long c) { this.count = (int) c; }",
      Writes.BOTH
    ),
    new Case(
      "an Integer setter behind a Number getter",
      "Number",
      "public void setCount(final Integer c) { this.count = c; }",
      Writes.REFLECTIVE
    ),
    new Case(
      "a Number setter behind an Integer getter",
      "Integer",
      "public void setCount(final Number c) { this.count = c == null ? null : c.intValue(); }",
      Writes.BOTH
    ),
    new Case(
      "an int setter behind an Integer getter",
      "Integer",
      "public void setCount(final int c) { this.count = c; }",
      Writes.BOTH
    ),
    new Case(
      "an int overload declared after a String one",
      "int",
      "public void setCount(final String c) { this.count = -1; }\n" +
        "  public void setCount(final int c) { this.count = c; }",
      Writes.BOTH
    ),
    new Case(
      "a double overload declared before a long one behind an int getter",
      "int",
      "public void setCount(final double c) { this.count = -2; }\n" +
        "  public void setCount(final long c) { this.count = (int) c; }",
      Writes.BOTH
    ),
    new Case(
      "two overloads neither of which takes the getter's type",
      "int",
      "public void setCount(final String c) { this.count = -1; }\n" +
        "  public void setCount(final java.util.Date c) { this.count = -3; }",
      Writes.AMBIGUOUS
    )
  );

  @Test
  @DisplayName("the generated binder and the runtime fromMap convert to a type the setter takes, and agree")
  void fromMapConvertsToATypeTheSetterTakes() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var c : CASES) {
      final var prefix = "Stf" + index++;
      final var compilation = ProcessorHarness.compileFully(
        List.of(new FromMapProcessor()),
        List.of(),
        bean(prefix, "@io.github.eschizoid.telescope.annotations.FromMap", c)
      );
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), bean(prefix + "P", "", c));
      final var runtimeBean = plain.define(MethodHandles.lookup()).get(PACKAGE + "." + prefix + "PBean");
      final var runtime = outcome(() -> {
        Telescope.fromMap(runtimeBean);
        return "built";
      });
      if (c.writes() == Writes.AMBIGUOUS) {
        if (compilation.success() || !compilation.hasError(AMBIGUOUS)) {
          failures.add(c.name() + ": the binder is not refused: " + compilation.errorMessages().strip());
        }
        if (!runtime.equals("throws java.lang.IllegalArgumentException")) {
          failures.add(c.name() + ": the runtime fromMap " + runtime + " rather than refusing");
        }
        continue;
      }
      if (!compilation.success()) {
        failures.add(c.name() + ": the binder does not compile: " + compilation.errorMessages().strip());
        continue;
      }
      final var classes = compilation.define(MethodHandles.lookup());
      final var bean = classes.get(PACKAGE + "." + prefix + "Bean");
      final var binder = classes.get(PACKAGE + "." + prefix + "BeanFromMap").getMethod("fromMap", Map.class);
      for (final var source : List.<Map<String, Object>>of(
        Map.of("name", "Ada", "count", "5"),
        Map.of("name", "Ada", "count", 5),
        Map.of("name", "Ada")
      )) {
        final var generated = outcome(() -> describe(bean, binder.invoke(null, source)));
        final var reflective = outcome(() -> describe(runtimeBean, Telescope.fromMap(runtimeBean).forward(source)));
        if (!generated.equals(reflective)) {
          failures.add(c.name() + " from " + source + ": generated " + generated + " but runtime " + reflective);
        }
      }
      final var written = outcome(() -> describe(bean, binder.invoke(null, Map.of("name", "Ada", "count", "5"))));
      if (!written.equals("Ada/5")) failures.add(c.name() + ": the binder built " + written + ", owed Ada/5");
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  @Test
  @DisplayName(
    "a navigator and a lens write a property only through a setter that takes its type, and other properties still"
  )
  void navigatorsAndLensesNeedTheSetterToTakeTheReadType() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var c : CASES) {
      final var prefix = "Stn" + index++;
      final var compilation = ProcessorHarness.compileFully(
        List.of(new BeanFocusProcessor()),
        List.of(),
        bean(prefix, "@io.github.eschizoid.telescope.annotations.BeanFocus", c)
      );
      if (c.compiles() && !compilation.success()) {
        failures.add(c.name() + ": the navigator does not compile: " + compilation.errorMessages().strip());
      }
      if (!c.compiles() && (compilation.success() || !compilation.hasError(c.refusal()))) {
        failures.add(c.name() + ": the navigator is not refused by name: " + compilation.errorMessages().strip());
      }
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), bean(prefix + "P", "", c));
      final var bean = plain.define(MethodHandles.lookup()).get(PACKAGE + "." + prefix + "PBean");
      final var name = outcome(() -> describe(bean, beanSet(bean, "name", "Ada")));
      final var nameOwed = "Ada/" + unwritten(c);
      if (!name.equals(nameOwed)) failures.add(c.name() + ": writing name built " + name + ", owed " + nameOwed);
      final var count = outcome(() -> describe(bean, beanSet(bean, "count", 5)));
      final var countOwed = c.reflects() ? "null/5" : "throws java.lang.IllegalArgumentException";
      if (!count.equals(countOwed)) failures.add(c.name() + ": writing count built " + count + ", owed " + countOwed);
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  @Test
  @DisplayName("the generated bridge and the runtime mapper fill a property only through a setter that takes its type")
  void bridgesAndMappersNeedTheSetterToTakeTheReadType() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var c : CASES) {
      final var prefix = "Stb" + index++;
      final var compilation = ProcessorHarness.compileFully(
        List.of(new BridgeProcessor()),
        List.of(),
        bean(prefix, "", c),
        source(prefix + "Src", "@io.github.eschizoid.telescope.annotations.Bridge(" + prefix + "Bean.class)", c)
      );
      if (c.compiles() && !compilation.success()) {
        failures.add(c.name() + ": the bridge does not compile: " + compilation.errorMessages().strip());
      }
      if (!c.compiles() && (compilation.success() || !compilation.hasError(c.refusal()))) {
        failures.add(c.name() + ": the bridge is not refused by name: " + compilation.errorMessages().strip());
      }
      final var plain = ProcessorHarness.compileFully(
        List.of(),
        List.of(),
        bean(prefix + "P", "", c),
        source(prefix + "PSrc", "", c)
      );
      final var plainClasses = plain.define(MethodHandles.lookup());
      final var bean = plainClasses.get(PACKAGE + "." + prefix + "PBean");
      final var src = plainClasses.get(PACKAGE + "." + prefix + "PSrc");
      final var value = src.getConstructors()[0].newInstance(5, "Ada");
      final var owed = c.reflects() ? "Ada/5" : "throws java.lang.IllegalStateException";
      final var forwarded = outcome(() -> describe(bean, mapper(src, bean).forward(value)));
      if (!forwarded.equals(owed)) failures.add(c.name() + ": the mapper built " + forwarded + ", owed " + owed);
      final var into = outcome(() ->
        describe(bean, mapper(src, bean).into(bean.getConstructor().newInstance(), value))
      );
      if (!into.equals(owed)) failures.add(c.name() + ": the mapper wrote " + into + " into a bean, owed " + owed);
      if (c.compiles() && compilation.success()) {
        final var classes = compilation.define(MethodHandles.lookup());
        final var generatedBean = classes.get(PACKAGE + "." + prefix + "Bean");
        final var generatedSrc = classes.get(PACKAGE + "." + prefix + "Src");
        final var forward = classes.get(PACKAGE + "." + prefix + "SrcBridge").getMethod("forward", generatedSrc);
        final var generatedValue = generatedSrc.getConstructors()[0].newInstance(5, "Ada");
        final var generated = outcome(() -> describe(generatedBean, forward.invoke(null, generatedValue)));
        if (!generated.equals(forwarded)) {
          failures.add(c.name() + ": the bridge built " + generated + " but the mapper " + forwarded);
        }
      }
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  @Test
  @DisplayName("a mapper that writes the bean back refuses its setter by name, and one that only reads it does not")
  void aBeanReadOnlyForwardIsNotRefusedForItsSetter() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var c : CASES) {
      final var prefix = "Str" + index++;
      // The bridge on the bean writes it backward and in patch, both with values of its read type.
      final var compilation = ProcessorHarness.compileFully(
        List.of(new BridgeProcessor()),
        List.of(),
        bean(prefix, "@io.github.eschizoid.telescope.annotations.Bridge(" + prefix + "Dst.class)", c),
        source(prefix + "Dst", "", c)
      );
      if (c.compiles() && !compilation.success()) {
        failures.add(c.name() + ": the bridge does not compile: " + compilation.errorMessages().strip());
      }
      if (!c.compiles() && (compilation.success() || !compilation.hasError(c.refusal()))) {
        failures.add(c.name() + ": the bridge is not refused by name: " + compilation.errorMessages().strip());
      }
      final var plain = ProcessorHarness.compileFully(
        List.of(),
        List.of(),
        bean(prefix + "P", "", c),
        source(prefix + "PDst", "", c)
      );
      final var plainClasses = plain.define(MethodHandles.lookup());
      final var bean = plainClasses.get(PACKAGE + "." + prefix + "PBean");
      final var dst = plainClasses.get(PACKAGE + "." + prefix + "PDst");
      final var built = bean.getConstructor().newInstance();
      bean.getMethod("setName", String.class).invoke(built, "Ada");
      final var both = outcome(() -> String.valueOf(mapper(bean, dst).forward(built)));
      final var bothRefused = both.equals("throws java.lang.IllegalStateException");
      if (bothRefused == c.reflects()) failures.add(c.name() + ": the two-way mapper " + both);
      final var forwardOnly = outcome(() -> String.valueOf(mapperForward(bean, dst).forward(built)));
      if (forwardOnly.startsWith("throws")) failures.add(c.name() + ": the forward-only mapper " + forwardOnly);
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  @Test
  @DisplayName("a transform producing the setter's type writes through a setter the getter's type cannot reach")
  void aTransformIntoTheSettersTypeKeepsTheBridge() throws ReflectiveOperationException {
    final var c = CASES.getFirst();
    final var prefix = "Stt";
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      bean(prefix, "", c),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Conv",
        "package " +
          PACKAGE +
          ";\npublic final class " +
          prefix +
          "Conv {\n  public static String text(final int i) { return Integer.toString(i); }\n}\n"
      ),
      source(
        prefix + "Src",
        "@io.github.eschizoid.telescope.annotations.Bridge(value = " +
          prefix +
          "Bean.class, transforms = @io.github.eschizoid.telescope.annotations.Transform(field = \"count\", using = " +
          prefix +
          "Conv.class, method = \"text\"))",
        c
      )
    );
    assertEquals(true, compilation.success(), compilation::errorMessages);
    final var classes = compilation.define(MethodHandles.lookup());
    final var bean = classes.get(PACKAGE + "." + prefix + "Bean");
    final var src = classes.get(PACKAGE + "." + prefix + "Src");
    final var forward = classes.get(PACKAGE + "." + prefix + "SrcBridge").getMethod("forward", src);
    assertEquals("Ada/5", describe(bean, forward.invoke(null, src.getConstructors()[0].newInstance(5, "Ada"))));
  }

  @SuppressWarnings("unchecked")
  private static Mapper<Object, Object> mapper(final Class<?> from, final Class<?> to) {
    return Telescope.mapper((Class<Object>) from, (Class<Object>) to);
  }

  @SuppressWarnings("unchecked")
  private static ForwardMapper<Object, Object> mapperForward(final Class<?> from, final Class<?> to) {
    return Telescope.mapperForward((Class<Object>) from, (Class<Object>) to);
  }

  /** A new bean with {@code property} set to {@code value} through the runtime bean path. */
  @SuppressWarnings("unchecked")
  private static Object beanSet(final Class<?> bean, final String property, final Object value)
    throws ReflectiveOperationException {
    return Telescope.ofBean((Class<Object>) bean).fieldByName(property).set(bean.getConstructor().newInstance(), value);
  }

  /** What a bean of the case holds in {@code count} when nothing wrote it. */
  private static String unwritten(final Case c) {
    return c.type().equals("int") ? "0" : "null";
  }

  /** A built bean as {@code name/count}. */
  private static String describe(final Class<?> bean, final Object built) throws ReflectiveOperationException {
    return bean.getMethod("getName").invoke(built) + "/" + Objects.toString(bean.getMethod("getCount").invoke(built));
  }

  private interface Call {
    String run() throws ReflectiveOperationException;
  }

  /** The value a call returns, or the exception it throws, so two paths compare either way. */
  private static String outcome(final Call call) {
    try {
      return call.run();
    } catch (final InvocationTargetException e) {
      return "throws " + e.getCause().getClass().getName();
    } catch (final RuntimeException e) {
      return "throws " + e.getClass().getName();
    } catch (final ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * A record named {@code name} with the case's {@code count} and a {@code name}, carrying {@code
   * annotation}.
   */
  private static JavaFileObject source(final String name, final String annotation, final Case c) {
    return ProcessorHarness.source(
      PACKAGE + "." + name,
      "package " +
        PACKAGE +
        ";\n" +
        annotation +
        "\npublic record " +
        name +
        "(" +
        c.type() +
        " count, String name) {}\n"
    );
  }

  /** The bean of a case, named {@code <prefix>Bean} and carrying {@code annotation}. */
  private static JavaFileObject bean(final String prefix, final String annotation, final Case c) {
    final var bean = prefix + "Bean";
    return ProcessorHarness.source(
      PACKAGE + "." + bean,
      "package " +
        PACKAGE +
        ";\n" +
        annotation +
        "\npublic class " +
        bean +
        " {\n  private " +
        c.type() +
        " count;\n  private String name;\n  public " +
        bean +
        "() {}\n  public " +
        c.type() +
        " getCount() { return count; }\n  " +
        c.setters() +
        "\n  public String getName() { return name; }\n" +
        "  public void setName(final String name) { this.name = name; }\n}\n"
    );
  }
}
