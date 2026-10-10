package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which declared types a map can fill, and how a value is converted into each, is decided twice: by
 * the {@code @FromMap} processor while it generates a binder, and by {@code Telescope.fromMap}
 * while it builds a mapper for a component no row names, which reads the key with its own name. A
 * type one of them refuses and the other accepts is a record that compiles under one path and is
 * silently filled with {@code null} under the other, and a value they convert differently is the
 * same map read as two different records.
 *
 * <p>Each case declares one component of the type under test, on a record and on a bean, and
 * compiles the same sources twice: once without the processor, so the runtime path has classes to
 * read, and once through the real processor. Each path is then asked for its verdict, and where
 * both accept, for the value an empty map leaves behind and for what each sample value under the
 * component's name converts to.
 *
 * <p>A component type with a generated binder is not among the cases. The runtime finds a binder
 * through the {@code META-INF/services} registration its compilation wrote, and a class defined
 * here at test time has none, so that case lives in core, over types Gradle compiled through the
 * processor.
 */
class FromMapRefusalCrossPathTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  /**
   * One declared type, written with {@code %s} wherever a helper type's prefix belongs, and the
   * values a map can carry under the component's own name. Each sample is converted by both paths
   * and the two results compared, so a sample neither path can convert is compared by the exception
   * it throws.
   */
  private record Case(String type, boolean refused, List<Object> samples) {
    static Case served(final String type, final Object... samples) {
      return new Case(type, false, List.of(samples));
    }

    static Case refused(final String type) {
      return new Case(type, true, List.of());
    }
  }

  private static final List<Object> NUMBERS = List.of(7, 7.9d, -3L, 70_000, "12", "x");

  private static final List<Case> CASES = List.of(
    Case.served("int", NUMBERS.toArray()),
    Case.served("long", NUMBERS.toArray()),
    Case.served("double", NUMBERS.toArray()),
    Case.served("float", NUMBERS.toArray()),
    Case.served("short", NUMBERS.toArray()),
    Case.served("byte", NUMBERS.toArray()),
    Case.served("boolean", true, "TRUE", "no", 1),
    Case.served("char", 'q', "xyz", "", 65),
    Case.served("Integer", NUMBERS.toArray()),
    Case.served("Long", NUMBERS.toArray()),
    Case.served("Double", NUMBERS.toArray()),
    Case.served("Float", NUMBERS.toArray()),
    Case.served("Short", NUMBERS.toArray()),
    Case.served("Byte", NUMBERS.toArray()),
    Case.served("Boolean", false, "true", "yes"),
    Case.served("Character", 'q', "xyz", ""),
    Case.served("String", "s", 5),
    Case.served("Object", "o", List.of(1)),
    Case.served("CharSequence", "cs", 5),
    Case.served("java.time.Instant", "2024-01-01T00:00:00Z", Instant.EPOCH, "yesterday"),
    Case.served("java.time.LocalDate", "2024-02-03", LocalDate.EPOCH),
    Case.served("java.time.LocalDateTime", "2024-02-03T04:05:06"),
    Case.served("java.time.LocalTime", "04:05"),
    Case.served("java.time.OffsetDateTime", "2024-02-03T04:05:06+01:00"),
    Case.served("java.time.ZonedDateTime", "2024-02-03T04:05:06Z[UTC]"),
    Case.served("java.time.Duration", "PT5M"),
    Case.served("java.time.Period", "P1D"),
    Case.served("java.util.UUID", "123e4567-e89b-12d3-a456-426614174000"),
    Case.served("java.math.BigDecimal", "1.10", 5, new BigDecimal("2.5")),
    Case.served("java.math.BigInteger", "99", 7L),
    Case.served("java.net.URI", "https://example.org/a"),
    Case.served("java.util.Currency", "EUR"),
    Case.served("java.util.Locale", "en-GB"),
    Case.served("java.util.regex.Pattern", "a+b"),
    Case.served("java.time.DayOfWeek", "MONDAY", DayOfWeek.FRIDAY, "Mon"),
    Case.served("%sTone", "HIGH", "low"),
    Case.served("java.util.List<String>", List.of("a", "b"), "not a list", Set.of("a")),
    Case.served("java.util.Set<Integer>", Set.of(1, 2), Set.of("3"), List.of(1)),
    Case.served("java.util.Map<String, Integer>", Map.of("a", 1, "b", "2"), "not a map"),
    Case.served("java.util.Optional<String>", "s"),
    Case.served("java.util.Optional<java.util.List<String>>", List.of("a"), "not a list"),
    // A null element is where an Optional has to test for absence before its element
    // converts:
    // the inner List answers a null with an empty list, which would wrap into a present
    // Optional.
    Case.served("java.util.List<java.util.Optional<java.util.List<Integer>>>", Arrays.asList(null, List.of(1, "2"))),
    Case.served(
      "java.util.Map<String, java.util.List<java.time.Instant>>",
      Map.of("k", List.of("2024-01-01T00:00:00Z"))
    ),
    Case.refused("String[]"),
    Case.refused("int[]"),
    Case.refused("T"),
    Case.refused("java.util.List<?>"),
    Case.refused("java.util.List"),
    Case.refused("java.util.Optional"),
    Case.refused("java.util.Collection<String>"),
    Case.refused("Iterable<String>"),
    Case.refused("java.util.ArrayList<String>"),
    Case.refused("java.util.HashMap<String, String>"),
    Case.refused("java.util.TreeSet<String>"),
    Case.refused("Number"),
    Case.refused("java.io.File"),
    Case.refused("%sPlain"),
    Case.refused("java.util.List<String[]>"),
    Case.refused("java.util.Set<Number>"),
    Case.refused("java.util.Optional<%sPlain>"),
    Case.refused("java.util.Map<%sPlain, String>"),
    Case.refused("java.util.Map<String, java.util.ArrayList<String>>")
  );

  @Test
  @DisplayName("both paths refuse the same component types, and convert the same map into the same value")
  void bothPathsAgreeOnEveryType() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var c : CASES) {
      final var prefix = "Fmr" + index++;
      final var type = c.type().formatted(prefix);
      final var sources = sources(prefix, type);

      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(plain.success(), () -> type + " should compile without the processor: " + plain.errorMessages());
      final var processed = ProcessorHarness.compileFully(List.of(new FromMapProcessor()), List.of(), sources);
      final var classes = plain.define(MethodHandles.lookup());
      final var record = classes.get(PACKAGE + "." + prefix + "Rec");
      final var bean = classes.get(PACKAGE + "." + prefix + "Bean");

      if (c.refused()) {
        if (processed.success()) failures.add(type + ": the processor accepted it");
        else {
          // Each target has to be refused on its own account, and for its component's type rather
          // than for anything else the compilation tripped over.
          for (final var target : List.of(prefix + "Rec", prefix + "Bean")) {
            final var own = processed
              .errors()
              .stream()
              .filter(d -> d.getSource() != null && d.getSource().toUri().toString().endsWith("/" + target + ".java"))
              .map(d -> d.getMessage(Locale.ROOT))
              .toList();
            if (own.isEmpty() || !own.stream().allMatch(m -> m.startsWith("@FromMap:"))) {
              failures.add(type + ": the processor did not refuse " + target + " for its type: " + own);
            }
          }
        }
        expectRuntimeRefusal(failures, type, record, "component 'value' of " + prefix + "Rec");
        expectRuntimeRefusal(failures, type, bean, "property 'value' of " + prefix + "Bean");
        continue;
      }

      if (!processed.success()) {
        failures.add(type + ": the processor refused it: " + processed.errorMessages().strip());
        continue;
      }
      final var binders = generatedOnly(processed, plain).define(MethodHandles.lookup());
      final var generatedRecord = invokeBinder(binders, prefix + "Rec");
      final var generatedBean = invokeBinder(binders, prefix + "Bean");
      final Object runtimeRecord;
      final Object runtimeBean;
      try {
        runtimeRecord = Telescope.fromMap(record).forward(Map.of());
        runtimeBean = Telescope.fromMap(bean).forward(Map.of());
      } catch (final IllegalArgumentException e) {
        failures.add(type + ": the runtime refused it: " + e.getMessage());
        continue;
      }
      if (!generatedRecord.equals(runtimeRecord)) {
        failures.add(type + ": record, generated " + generatedRecord + " but runtime " + runtimeRecord);
      }
      final var getter = bean.getMethod("getValue");
      final var generatedValue = getter.invoke(generatedBean);
      final var runtimeValue = getter.invoke(runtimeBean);
      if (!Objects.equals(generatedValue, runtimeValue)) {
        failures.add(type + ": bean, generated " + generatedValue + " but runtime " + runtimeValue);
      }
      for (final var sample : c.samples()) {
        final var source = Map.<String, Object>of("value", sample);
        for (final var target : List.of(record, bean)) {
          final var binder = binders
            .get(PACKAGE + "." + target.getSimpleName() + "FromMap")
            .getMethod("fromMap", Map.class);
          final var value = target.isRecord() ? target.getMethod("value") : getter;
          final var generated = outcome(() -> value.invoke(binder.invoke(null, source)));
          final var runtime = outcome(() -> value.invoke(Telescope.fromMap(target).forward(source)));
          if (!generated.equals(runtime)) {
            failures.add(
              type +
                " from " +
                describe(sample) +
                ", " +
                target.getSimpleName() +
                ": generated " +
                generated +
                " but runtime " +
                runtime
            );
          }
        }
      }
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  /** A reflective call that yields a value or throws. */
  private interface Call {
    Object run() throws ReflectiveOperationException;
  }

  /**
   * What a conversion produced, in a form two paths can be compared by: the value, or the class of
   * the exception it threw. A {@code Pattern} has no value equality of its own, so it is compared
   * by its source.
   */
  private static String outcome(final Call call) {
    try {
      final var value = call.run();
      return "value " + (value instanceof Pattern p ? "Pattern(" + p.pattern() + ")" : describe(value));
    } catch (final InvocationTargetException e) {
      return "throws " + e.getCause().getClass().getName();
    } catch (final RuntimeException e) {
      return "throws " + e.getClass().getName();
    } catch (final ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static String describe(final Object value) {
    return value == null ? "null" : value + " (" + value.getClass().getSimpleName() + ")";
  }

  /**
   * A bean whose {@code value} it inherits from a generic superclass, by the superclass's argument
   * as written in the bean's {@code extends} clause, {@code null} for one the bean passes through
   * as a variable of its own, and the value an empty map leaves behind.
   */
  private record Inherited(String name, String argument, Object unfilled) {}

  private static final List<Inherited> INHERITED = List.of(
    new Inherited("an Integer the subclass fixes", "Integer", null),
    new Inherited("a list the subclass fixes", "java.util.List<String>", List.of()),
    new Inherited("an Optional the subclass fixes", "java.util.Optional<String>", Optional.empty()),
    new Inherited("a variable the subclass passes through", null, null)
  );

  @Test
  @DisplayName("a property inherited from a generic superclass is typed as the subclass fixes it on both paths")
  void anInheritedPropertyIsReadAsTheSubclassFixesItOnBothPaths() throws ReflectiveOperationException {
    final var failures = new ArrayList<String>();
    var index = 0;
    for (final var c : INHERITED) {
      final var prefix = "Fmi" + index++;
      final var head = "package " + PACKAGE + ";\nimport io.github.eschizoid.telescope.annotations.FromMap;\n";
      final var generic = c.argument() == null ? "<X>" : "";
      final var argument = c.argument() == null ? "X" : c.argument();
      final var sources = new JavaFileObject[] {
        ProcessorHarness.source(
          PACKAGE + "." + prefix + "Base",
          "package " +
            PACKAGE +
            ";\npublic class " +
            prefix +
            "Base<T> {\n  private T value;\n  public T getValue() { return value; }\n" +
            "  public void setValue(final T value) { this.value = value; }\n}\n"
        ),
        ProcessorHarness.source(
          PACKAGE + "." + prefix + "Bean",
          head +
            "@FromMap\npublic class " +
            prefix +
            "Bean" +
            generic +
            " extends " +
            prefix +
            "Base<" +
            argument +
            "> {}\n"
        ),
      };
      final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
      assertTrue(plain.success(), () -> c.name() + " should compile without the processor: " + plain.errorMessages());
      final var processed = ProcessorHarness.compileFully(List.of(new FromMapProcessor()), List.of(), sources);
      final var bean = plain.define(MethodHandles.lookup()).get(PACKAGE + "." + prefix + "Bean");
      if (c.argument() == null) {
        if (processed.success()) failures.add(c.name() + ": the processor accepted it");
        expectRuntimeRefusal(failures, c.name(), bean, "property 'value' of " + prefix + "Bean");
        continue;
      }
      if (!processed.success()) {
        failures.add(c.name() + ": the processor refused it: " + processed.errorMessages().strip());
        continue;
      }
      final var binders = generatedOnly(processed, plain).define(MethodHandles.lookup());
      final var binder = binders.get(PACKAGE + "." + prefix + "BeanFromMap");
      final var getter = bean.getMethod("getValue");
      final var generated = getter.invoke(binder.getMethod("fromMap", Map.class).invoke(null, Map.of()));
      final Object runtime;
      try {
        runtime = getter.invoke(Telescope.fromMap(bean).forward(Map.of()));
      } catch (final IllegalArgumentException e) {
        failures.add(c.name() + ": the runtime refused it: " + e.getMessage());
        continue;
      }
      if (!Objects.equals(c.unfilled(), generated) || !Objects.equals(c.unfilled(), runtime)) {
        failures.add(c.name() + ": generated " + generated + ", runtime " + runtime + ", owed " + c.unfilled());
      }
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  @Test
  @DisplayName("the runtime refusal names the component, its declared type and the fix")
  void theRefusalNamesTheComponentTypeAndFix() {
    final var sources = sources("FmrMsg", "String[]");
    final var plain = ProcessorHarness.compileFully(List.of(), List.of(), sources);
    assertTrue(plain.success(), plain::errorMessages);
    final var record = plain.define(MethodHandles.lookup()).get(PACKAGE + ".FmrMsgRec");

    final var refusal = assertThrows(IllegalArgumentException.class, () -> Telescope.fromMap(record));
    assertEquals(
      "Telescope.fromMap: component 'value' of FmrMsgRec is declared String[] and no row names it: " +
        "java.lang.String[] can't be coerced from a map value (type variable / array / unsupported kind); " +
        "name it with an extract(key, accessor, converter) row",
      refusal.getMessage()
    );
  }

  private static void expectRuntimeRefusal(
    final List<String> failures,
    final String type,
    final Class<?> target,
    final String names
  ) {
    try {
      Telescope.fromMap(target);
      failures.add(type + ": the runtime accepted " + target.getSimpleName());
    } catch (final IllegalArgumentException e) {
      if (!e.getMessage().contains(names) || !e.getMessage().contains("extract(key, accessor, converter)")) {
        failures.add(type + ": the runtime refusal does not name " + names + " and the fix: " + e.getMessage());
      }
    }
  }

  private static Object invokeBinder(final Map<String, Class<?>> binders, final String target)
    throws ReflectiveOperationException {
    final var binder = binders.get(PACKAGE + "." + target + "FromMap");
    if (binder == null) throw new IllegalStateException("no binder for " + target + ", only " + binders.keySet());
    return binder.getMethod("fromMap", Map.class).invoke(null, Map.of());
  }

  /** The classes the processor added, which link against the ones the plain compile defined. */
  private static ProcessorHarness.Compilation generatedOnly(
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
    );
  }

  /**
   * A record and a bean, each with one {@code value} of {@code type}, plus the helper types a case
   * can name: an enum and a record the processor has not bound.
   */
  private static JavaFileObject[] sources(final String prefix, final String type) {
    final var generic = type.equals("T") ? "<T>" : "";
    final var head = "package " + PACKAGE + ";\nimport io.github.eschizoid.telescope.annotations.FromMap;\n";
    return new JavaFileObject[] {
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Tone",
        "package " + PACKAGE + ";\npublic enum " + prefix + "Tone { LOW, HIGH }\n"
      ),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Plain",
        "package " + PACKAGE + ";\npublic record " + prefix + "Plain(String city) {}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Rec",
        head + "@FromMap\npublic record " + prefix + "Rec" + generic + "(" + type + " value) {}\n"
      ),
      ProcessorHarness.source(
        PACKAGE + "." + prefix + "Bean",
        head +
          "@FromMap\npublic class " +
          prefix +
          "Bean" +
          generic +
          " {\n  private " +
          type +
          " value;\n  public " +
          prefix +
          "Bean() {}\n  public " +
          type +
          " getValue() { return value; }\n  public void setValue(final " +
          type +
          " value) { this.value = value; }\n}\n"
      ),
    };
  }
}
