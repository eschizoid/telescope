package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.NullDefaults;
import io.github.eschizoid.telescope.internal.Records;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.mapping.Extract;
import io.github.eschizoid.telescope.mapping.MapExtractStep;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The engine behind {@link Telescope#fromMap(Class, MapExtractStep...)} — the untyped {@code
 * Map<String, Object> → T} boundary factory. Sibling of {@link Merge}: the {@code Telescope} facade
 * validates nothing and delegates here, keeping the factories-delegate-to-engines shape.
 *
 * <p>Rows are matched to target components / properties once at build time, into positional arrays.
 * On the record path the per-call forward is fully positional: a source {@code Map.get} plus
 * converter per extracted slot, a type default per unmatched slot (the NullDefaults table: "" for
 * String, empty containers — not bare null), and one cached canonical-constructor invocation — no
 * name lookup survives. On the bean path the writer's {@code construct} contract stays name-driven,
 * so one {@code name→index} lookup per property remains (down from the two the old engine paid —
 * the row map and the defaults map).
 */
final class FromMap {

  private FromMap() {}

  @SuppressWarnings("unchecked")
  static <T> ForwardMapper<Map<String, Object>, T> build(final Class<T> target, final MapExtractStep... rows) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(rows, "rows");
    final var byField = new LinkedHashMap<String, MapExtractStep>();
    for (final var row : rows) {
      if (!(row instanceof Extract<?, ?>)) throw new IllegalArgumentException(
        "Telescope.fromMap rows must be built via MapExtractStep.extract(...)"
      );
      final var fieldName = PropertyNames.property(LambdaIntrospection.methodNameOf(row.targetAccessor()));
      if (byField.put(fieldName, row) != null) throw new IllegalArgumentException(
        "Telescope.fromMap: duplicate extract row for target field '" + fieldName + "'"
      );
    }
    // Fail loud on a row that matches no target component/property — the slot alignment below
    // would otherwise silently ignore it (the extract key never read, the converter never run).
    final var known = target.isRecord()
      ? Arrays.stream(target.getRecordComponents()).map(RecordComponent::getName).toList()
      : List.of(Beans.propertyNames(target));
    final var typeByName = HashMap.<String, String>newHashMap(known.size());
    if (target.isRecord()) {
      for (final var comp : target.getRecordComponents())
        typeByName.put(comp.getName(), DeepMap.simpleTypeName(comp.getGenericType()));
    } else {
      for (final var name : known) typeByName.put(name, DeepMap.simpleTypeName(Beans.propertyType(target, name)));
    }
    for (final var fieldName : byField.keySet()) {
      if (!known.contains(fieldName)) throw new IllegalArgumentException(
        "Telescope.fromMap: extract row targets '" +
          fieldName +
          "', which is not a component/property of " +
          target.getSimpleName() +
          ". Known fields: " +
          known +
          "."
      );
    }
    final Function<Map<String, Object>, T> forward = target.isRecord()
      ? recordForward(target, byField)
      : beanForward(target, byField);
    // The slot alignment above already decided every component's fate — surface those decisions
    // as the explain() trail instead of throwing them away: one Transformed row per extract
    // (map key → component, through the row's converter), one MISSING_SOURCE skip per defaulted
    // slot. The report is derived from the same data the forward path runs on, so it cannot drift.
    final var trail = new ArrayList<OpticNode>(known.size());
    for (final var comp : known) {
      final var row = byField.get(comp);
      if (row != null) {
        // Both of the row's type components are type names, and the report prints each where a type
        // belongs. A value read from an untyped map is an Object, which is what the deep-mapping
        // trail writes for an Object-typed source field through this same helper.
        trail.add(new OpticNode.Transformed(row.key(), comp, "Object", typeByName.get(comp)));
      } else {
        trail.add(new OpticNode.Skipped(comp, OpticNode.Reason.MISSING_SOURCE));
      }
    }
    return ForwardMapper.create(forward, (Class<Map<String, Object>>) (Class<?>) Map.class, target, trail);
  }

  /**
   * Record path — the full positional bind. Each canonical component resolves at build time to
   * either its extract row (source key + converter) or its type default; the forward call fills a
   * positional args array and invokes the cached canonical-constructor handle via {@link
   * Records#construct(Class, Object[])}. No name-keyed dispatch survives to the hot path.
   */
  @SuppressWarnings("unchecked")
  private static <T> Function<Map<String, Object>, T> recordForward(
    final Class<T> target,
    final Map<String, MapExtractStep> byField
  ) {
    final var comps = target.getRecordComponents();
    final var n = comps.length;
    final var keys = new String[n];
    final var converters = (Function<Object, Object>[]) new Function<?, ?>[n];
    final var defaults = new Object[n];
    final var names = new String[n];
    for (var i = 0; i < n; i++) {
      names[i] = comps[i].getName();
      final var e = byField.get(comps[i].getName());
      if (e != null) {
        keys[i] = e.key();
        converters[i] = (Function<Object, Object>) e.converter();
      }
      defaults[i] = unfilledDefault(comps[i].getType(), comps[i].getGenericType());
    }
    return mapSrc -> {
      if (mapSrc == null) return null;
      final var args = new Object[n];
      for (var i = 0; i < n; i++) {
        final var value = keys[i] == null ? null : mapSrc.get(keys[i]);
        args[i] = value == null ? defaults[i] : converters[i].apply(value);
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
    final Map<String, MapExtractStep> byField
  ) {
    final var writer = Beans.autoWriter(target);
    final var propertyNames = Beans.propertyNames(target);
    final var n = propertyNames.length;
    final var keys = new String[n];
    @SuppressWarnings("unchecked")
    final var converters = (Function<Object, Object>[]) new Function<?, ?>[n];
    final var defaults = new Object[n];
    final var indexByName = HashMap.<String, Integer>newHashMap(n);
    for (var i = 0; i < n; i++) {
      indexByName.put(propertyNames[i], i);
      final var e = byField.get(propertyNames[i]);
      if (e != null) {
        keys[i] = e.key();
        @SuppressWarnings("unchecked")
        final var conv = (Function<Object, Object>) e.converter();
        converters[i] = conv;
      }
      final var propertyType = Beans.propertyType(target, propertyNames[i]);
      defaults[i] = unfilledDefault(rawOf(propertyType), propertyType);
    }
    return mapSrc -> {
      if (mapSrc == null) return null;
      final Function<String, Object> valueByName = name -> {
        final var i = indexByName.get(name);
        if (i == null) return null;
        final var value = keys[i] == null ? null : mapSrc.get(keys[i]);
        return value == null ? defaults[i] : converters[i].apply(value);
      };
      return writer.construct(propertyNames, valueByName);
    };
  }

  /**
   * The class behind a declared type, or null where there is none to read. A component declared
   * with type arguments is a {@link ParameterizedType}, and reading only for a {@link Class}
   * answers null for every one of them, which turns the fit test below into a test of nothing.
   */
  private static Class<?> rawOf(final Type type) {
    if (type instanceof Class<?> raw) return raw;
    return type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw
      ? raw
      : null;
  }

  /**
   * The value for a slot no row fills. The substitution table answers for the types it names and
   * returns null for the rest, which a reference slot can hold and a primitive one cannot. A
   * primitive therefore falls back to its own JLS default, the same value the rebuild path uses
   * when it meets a null source for one.
   */
  private static Object unfilledDefault(final Class<?> raw, final Type generic) {
    if (raw != null && raw.isPrimitive()) return Placeholders.primitiveDefault(raw);
    final var tabled = NullDefaults.defaultFor(generic);
    // Only the empty container and the empty Optional. The rest of that table substitutes a value
    // for a null the mapping engine met -- "" for a String, ZERO for a BigDecimal -- which is a
    // different question from what a key the map never carried should leave behind. Here the answer
    // is the JLS default for the declared type, which is what the generated binder for @FromMap
    // produces, so the two paths hand back the same record for the same map.
    final var emptyContainer = tabled instanceof Collection<?> || tabled instanceof Map<?, ?>;
    if (!emptyContainer && !(tabled instanceof Optional<?>)) return null;
    // The table answers for a family, so the empty singleton it returns for a List cannot be held
    // by a component declared as an ArrayList. A slot with no value it can hold is left empty.
    return raw == null || raw.isInstance(tabled) ? tabled : null;
  }
}
