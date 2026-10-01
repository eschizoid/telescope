package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which declared types a map can fill is decided twice: by the {@code @FromMap} processor while it
 * generates a binder, and by {@code Telescope.fromMap} while it builds a mapper for a component no
 * row names. A type one of them refuses and the other accepts is a record that compiles under one
 * path and is silently filled with {@code null} under the other.
 *
 * <p>Each case declares one component of the type under test, on a record and on a bean, and
 * compiles the same sources twice: once without the processor, so the runtime path has classes to
 * read, and once through the real processor. Each path is then asked for its verdict, and where
 * both accept, for the value an empty map leaves behind.
 */
class FromMapRefusalCrossPathTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  /** One declared type, written with {@code %s} wherever a helper type's prefix belongs. */
  private record Case(String type, boolean refused) {
    static Case served(final String type) {
      return new Case(type, false);
    }

    static Case refused(final String type) {
      return new Case(type, true);
    }
  }

  private static final List<Case> CASES = List.of(
    Case.served("int"),
    Case.served("long"),
    Case.served("double"),
    Case.served("float"),
    Case.served("short"),
    Case.served("byte"),
    Case.served("boolean"),
    Case.served("char"),
    Case.served("Integer"),
    Case.served("Long"),
    Case.served("Double"),
    Case.served("Float"),
    Case.served("Short"),
    Case.served("Byte"),
    Case.served("Boolean"),
    Case.served("Character"),
    Case.served("String"),
    Case.served("Object"),
    Case.served("CharSequence"),
    Case.served("java.time.Instant"),
    Case.served("java.time.LocalDate"),
    Case.served("java.time.LocalDateTime"),
    Case.served("java.time.LocalTime"),
    Case.served("java.time.OffsetDateTime"),
    Case.served("java.time.ZonedDateTime"),
    Case.served("java.time.Duration"),
    Case.served("java.time.Period"),
    Case.served("java.util.UUID"),
    Case.served("java.math.BigDecimal"),
    Case.served("java.math.BigInteger"),
    Case.served("java.net.URI"),
    Case.served("java.util.Currency"),
    Case.served("java.util.Locale"),
    Case.served("java.util.regex.Pattern"),
    Case.served("java.time.DayOfWeek"),
    Case.served("%sTone"),
    Case.served("%sAnnotated"),
    Case.served("java.util.List<String>"),
    Case.served("java.util.Set<Integer>"),
    Case.served("java.util.Map<String, Integer>"),
    Case.served("java.util.Optional<String>"),
    Case.served("java.util.Optional<java.util.List<String>>"),
    Case.served("java.util.List<%sAnnotated>"),
    Case.served("java.util.Map<String, java.util.List<java.time.Instant>>"),
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
  @DisplayName("both paths refuse the same component types, and fill the ones they accept with the same value")
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
   * can name: an enum, a record the processor binds, and one it does not.
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
        PACKAGE + "." + prefix + "Annotated",
        head + "@FromMap\npublic record " + prefix + "Annotated(String city) {}\n"
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
