package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.NullDefaults;
import io.github.eschizoid.telescope.internal.Records;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import io.github.eschizoid.telescope.mapping.Extract;
import io.github.eschizoid.telescope.mapping.MapExtractStep;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The engine behind {@link Telescope#fromMap(Class, MapExtractStep...)} — the untyped {@code
 * Map<String, Object> → T} boundary factory. Sibling of {@link Merge}: the {@code Telescope} facade
 * validates nothing and delegates here, keeping the factories-delegate-to-engines shape.
 *
 * <p>Everything name-keyed resolves at build time. Rows are matched to target components /
 * properties once, into positional arrays; the per-call forward path is then a source {@code
 * Map.get} plus converter call per extracted slot, a JLS default per unmatched slot, and one cached
 * canonical-constructor invocation (records) or writer fill (beans) — no per-call name→row or
 * name→default lookups.
 */
final class FromMap {

  private FromMap() {}

  @SuppressWarnings("unchecked")
  static <T> ForwardMapper<Map<String, Object>, T> build(final Class<T> target, final MapExtractStep... rows) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(rows, "rows");
    final var byField = new LinkedHashMap<String, Extract<?, ?>>();
    for (final var row : rows) {
      if (!(row instanceof Extract<?, ?> e)) throw new IllegalArgumentException(
        "Telescope.fromMap rows must be built via MapExtractStep.extract(...)"
      );
      final var fieldName = PropertyNames.property(LambdaIntrospection.methodNameOf(e.targetAccessor()));
      if (byField.put(fieldName, e) != null) throw new IllegalArgumentException(
        "Telescope.fromMap: duplicate extract row for target field '" + fieldName + "'"
      );
    }
    final Function<Map<String, Object>, T> forward = target.isRecord()
      ? recordForward(target, byField)
      : beanForward(target, byField);
    return ForwardMapper.create(forward, (Class<Map<String, Object>>) (Class<?>) Map.class, target);
  }

  /**
   * Record path — the full positional bind. Each canonical component resolves at build time to
   * either its extract row (source key + converter) or its JLS default; the forward call fills a
   * positional args array and invokes the cached canonical-constructor handle via {@link
   * Records#construct(Class, Object[])}. No name-keyed dispatch survives to the hot path.
   */
  @SuppressWarnings("unchecked")
  private static <T> Function<Map<String, Object>, T> recordForward(
    final Class<T> target,
    final Map<String, Extract<?, ?>> byField
  ) {
    final var comps = target.getRecordComponents();
    final var n = comps.length;
    final var keys = new String[n];
    final var converters = (Function<Object, Object>[]) new Function<?, ?>[n];
    final var defaults = new Object[n];
    for (var i = 0; i < n; i++) {
      final var e = byField.get(comps[i].getName());
      if (e != null) {
        keys[i] = e.key();
        converters[i] = (Function<Object, Object>) e.converter();
      } else {
        defaults[i] = NullDefaults.defaultFor(comps[i].getGenericType());
      }
    }
    return mapSrc -> {
      if (mapSrc == null) return null;
      final var args = new Object[n];
      for (var i = 0; i < n; i++) {
        args[i] = keys[i] != null ? converters[i].apply(mapSrc.get(keys[i])) : defaults[i];
      }
      return Records.construct(target, args);
    };
  }

  /**
   * Bean path — build-time alignment to the writer's property order. The writer's {@code construct}
   * contract is name-driven, so the per-call closure keeps a name entry point but resolves it
   * through one prebuilt name→index map into the positional arrays (replacing the previous two
   * lookups: row map then defaults map). The writer's own internals (setter resolution) are
   * unchanged here.
   */
  private static <T> Function<Map<String, Object>, T> beanForward(
    final Class<T> target,
    final Map<String, Extract<?, ?>> byField
  ) {
    final var writer = Beans.autoWriter(target);
    final var propertyNames = Beans.propertyNames(target);
    final var n = propertyNames.length;
    final var keys = new String[n];
    @SuppressWarnings("unchecked")
    final var converters = (Function<Object, Object>[]) new Function<?, ?>[n];
    final var defaults = new Object[n];
    final var indexByName = new HashMap<String, Integer>(n * 2);
    for (var i = 0; i < n; i++) {
      indexByName.put(propertyNames[i], i);
      final var e = byField.get(propertyNames[i]);
      if (e != null) {
        keys[i] = e.key();
        @SuppressWarnings("unchecked")
        final var conv = (Function<Object, Object>) e.converter();
        converters[i] = conv;
      } else {
        defaults[i] = NullDefaults.defaultFor(Beans.propertyType(target, propertyNames[i]));
      }
    }
    return mapSrc -> {
      if (mapSrc == null) return null;
      final Function<String, Object> valueByName = name -> {
        final var i = indexByName.get(name);
        if (i == null) return null;
        return keys[i] != null ? converters[i].apply(mapSrc.get(keys[i])) : defaults[i];
      };
      return writer.construct(propertyNames, valueByName);
    };
  }
}
