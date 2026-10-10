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
import io.github.eschizoid.telescope.mapping.Require;
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
 * <p>Every component / property is resolved once at build time to a slot: the map key it reads, the
 * conversion it applies and whether the key is required. A row decides the slot it names. Every
 * other slot reads the key with its own name and converts the value as the binder generated for
 * {@code @FromMap} converts it, so with no rows the two paths read the same map the same way.
 *
 * <p>On the record path the per-call forward is fully positional: a source {@code Map.get} plus
 * converter per slot, the type default where a slot has no value (the JLS default for its declared
 * type, or an empty {@code List}/{@code Set}/{@code Map}/{@code Optional}), and one cached
 * canonical-constructor invocation — no name lookup survives. On the bean path the writer's {@code
 * construct} contract stays name-driven, so one {@code name→index} lookup per property remains.
 */
final class FromMap {

  private FromMap() {}

  /**
   * What one component / property is filled from.
   *
   * @param key the map key read
   * @param converter applied to a non-null value under {@code key}
   * @param required whether a missing value refuses the source rather than defaulting
   */
  private record Slot(String key, Function<Object, Object> converter, boolean required) {}

  @SuppressWarnings("unchecked")
  static <T> ForwardMapper<Map<String, Object>, T> build(final Class<T> target, final MapExtractStep... rows) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(rows, "rows");
    final var byField = new LinkedHashMap<String, MapExtractStep>();
    for (final var row : rows) {
      if (!(row instanceof Extract<?, ?>) && !(row instanceof Require<?, ?>)) throw new IllegalArgumentException(
        "Telescope.fromMap rows must be built via MapExtractStep.extract(...) or MapExtractStep.required(...)"
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
    final var typeByName = LinkedHashMap.<String, Type>newLinkedHashMap(known.size());
    if (target.isRecord()) {
      for (final var comp : target.getRecordComponents()) typeByName.put(comp.getName(), comp.getGenericType());
    } else {
      for (final var name : known) typeByName.put(name, Beans.memberPropertyType(target, name));
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
    final var slots = LinkedHashMap.<String, Slot>newLinkedHashMap(known.size());
    for (final var entry : typeByName.entrySet()) {
      final var name = entry.getKey();
      final var row = byField.get(name);
      if (row != null) {
        slots.put(name, new Slot(row.key(), (Function<Object, Object>) row.converter(), row instanceof Require<?, ?>));
        continue;
      }
      // A component no row names reads the key with its own name, converted as the generated
      // binder converts it. A type with no conversion is refused here, where the generated binder
      // refuses it too, rather than left null.
      final var converter = FromMapCoercions.converterFor(entry.getValue(), reason ->
        new IllegalArgumentException(
          "Telescope.fromMap: " +
            (target.isRecord() ? "component '" : "property '") +
            name +
            "' of " +
            target.getSimpleName() +
            " is declared " +
            DeepMap.simpleTypeName(entry.getValue()) +
            " and no row names it: " +
            reason
        )
      );
      slots.put(name, new Slot(name, converter, false));
    }
    final Beans.BeanWriter<T> writer = target.isRecord() ? null : Beans.autoWriter(target);
    if (writer != null) refuseUnwritableRows(target, writer, byField);
    final Function<Map<String, Object>, T> forward =
      writer == null ? recordForward(target, slots) : beanForward(target, writer, slots);
    // The slot alignment above already decided every component's fate — surface those decisions
    // as the explain() trail instead of throwing them away: one Extracted row per slot that is
    // written, saying which key it reads and what an absent key does to it, and one MISSING_SOURCE
    // skip per bean property the writer cannot write and no row names. The report is derived from
    // the same data the forward path runs on, so it cannot drift.
    final var trail = new ArrayList<OpticNode>(known.size());
    for (final var comp : known) {
      final var slot = slots.get(comp);
      if (writer != null && !byField.containsKey(comp) && !writer.writes(comp)) {
        trail.add(new OpticNode.Skipped(comp, OpticNode.Reason.MISSING_SOURCE));
        continue;
      }
      trail.add(
        new OpticNode.Extracted(
          slot.key(),
          comp,
          DeepMap.simpleTypeName(typeByName.get(comp)),
          slot.required() ? OpticNode.WhenAbsent.REFUSES : OpticNode.WhenAbsent.DEFAULTS
        )
      );
    }
    return ForwardMapper.create(forward, (Class<Map<String, Object>>) (Class<?>) Map.class, target, trail);
  }

  /**
   * Record path — the full positional bind. Each canonical component resolves at build time to its
   * slot (source key + converter) and its type default; the forward call fills a positional args
   * array and invokes the cached canonical-constructor handle via {@link Records#construct(Class,
   * Object[])}. No name-keyed dispatch survives to the hot path.
   */
  @SuppressWarnings("unchecked")
  private static <T> Function<Map<String, Object>, T> recordForward(
    final Class<T> target,
    final Map<String, Slot> slots
  ) {
    final var comps = target.getRecordComponents();
    final var n = comps.length;
    final var keys = new String[n];
    final var converters = (Function<Object, Object>[]) new Function<?, ?>[n];
    final var defaults = new Object[n];
    final var names = new String[n];
    final var required = new boolean[n];
    for (var i = 0; i < n; i++) {
      names[i] = comps[i].getName();
      final var slot = slots.get(names[i]);
      keys[i] = slot.key();
      converters[i] = slot.converter();
      required[i] = slot.required();
      defaults[i] = unfilledDefault(comps[i].getType(), comps[i].getGenericType());
    }
    final var refusal = MissingKeys.of(target, "component", keys, names, required);
    return mapSrc -> {
      if (mapSrc == null) return null;
      refusal.check(mapSrc);
      final var args = new Object[n];
      for (var i = 0; i < n; i++) {
        final var value = mapSrc.get(keys[i]);
        args[i] = value == null ? defaults[i] : converters[i].apply(value);
      }
      return Records.construct(target, args);
    };
  }

  /**
   * Bean path — build-time alignment to the writer's property order. The writer's {@code construct}
   * contract is name-driven, so the per-call closure keeps a name entry point but resolves it
   * through one prebuilt name→index map into the positional arrays. The writer's own internals
   * (setter resolution) are unchanged here.
   */
  @SuppressWarnings("unchecked")
  private static <T> Function<Map<String, Object>, T> beanForward(
    final Class<T> target,
    final Beans.BeanWriter<T> writer,
    final Map<String, Slot> slots
  ) {
    final var propertyNames = Beans.propertyNames(target);
    final var n = propertyNames.length;
    final var keys = new String[n];
    final var converters = (Function<Object, Object>[]) new Function<?, ?>[n];
    final var defaults = new Object[n];
    final var required = new boolean[n];
    final var indexByName = HashMap.<String, Integer>newHashMap(n);
    for (var i = 0; i < n; i++) {
      indexByName.put(propertyNames[i], i);
      final var slot = slots.get(propertyNames[i]);
      keys[i] = slot.key();
      converters[i] = slot.converter();
      required[i] = slot.required();
      final var propertyType = Beans.memberPropertyType(target, propertyNames[i]);
      defaults[i] = unfilledDefault(rawOf(propertyType), propertyType);
    }
    final var refusal = MissingKeys.of(target, "property", keys, propertyNames, required);
    return mapSrc -> {
      if (mapSrc == null) return null;
      refusal.check(mapSrc);
      final Function<String, Object> valueByName = name -> {
        final var i = indexByName.get(name);
        if (i == null) return null;
        final var value = mapSrc.get(keys[i]);
        return value == null ? defaults[i] : converters[i].apply(value);
      };
      return writer.construct(propertyNames, valueByName);
    };
  }

  /**
   * Refuses a row naming a bean property the bean's writer has no way to set: a getter with no
   * setter, builder method or constructor parameter behind it. Its value would be read and dropped,
   * or never read at all, so a source that carried it would come back without it and nothing would
   * say so.
   */
  private static void refuseUnwritableRows(
    final Class<?> target,
    final Beans.BeanWriter<?> writer,
    final Map<String, MapExtractStep> byField
  ) {
    for (final var row : byField.entrySet()) {
      if (!writer.writes(row.getKey())) throw new IllegalArgumentException(
        "Telescope.fromMap: a row names property '" +
          row.getKey() +
          "' of " +
          target.getSimpleName() +
          ", which has no setter, builder method or constructor parameter to write it, so the value" +
          " read for key \"" +
          row.getValue().key() +
          "\" would be dropped. Remove the row, or give " +
          target.getSimpleName() +
          " a way to write '" +
          row.getKey() +
          "'."
      );
    }
  }

  /**
   * The class behind a declared type, or null for a type variable, wildcard or generic array, which
   * have none. A component declared with type arguments is a {@link ParameterizedType}, whose raw
   * type the reflection API always reports as a {@link Class}; reading only for a {@code Class}
   * would answer null for every such component and leave the fit test below testing nothing.
   */
  private static Class<?> rawOf(final Type type) {
    if (type instanceof Class<?> raw) return raw;
    return type instanceof ParameterizedType parameterized ? (Class<?>) parameterized.getRawType() : null;
  }

  /**
   * The value for a slot the map leaves without one: the JLS default for its declared type, except
   * that {@code List}, {@code Set}, {@code Map} and {@code Optional} come back empty. That is what
   * the binder generated for {@code @FromMap} produces, so the two paths hand back the same record
   * for the same map.
   *
   * <p>Only the empty containers and the empty {@code Optional} are taken from the substitution
   * table. The rest of it stands in for a null the mapping engine met, {@code ""} for a {@code
   * String} and {@code ZERO} for a {@code BigDecimal}, which is a different question from what a
   * missing value leaves behind. The table returns null for a primitive, which a primitive slot
   * cannot hold, so a primitive takes its own default from the helper the rebuild path uses.
   *
   * <p>The table answers for a container family, and its empty singleton does not fit every member
   * of one: a component declared {@code ArrayList} cannot hold it. Such a component reaches here
   * only when a row names it, since one no row names is refused while the mapper is built, and it
   * is left {@code null}.
   */
  private static Object unfilledDefault(final Class<?> raw, final Type generic) {
    if (raw != null && raw.isPrimitive()) return Placeholders.primitiveDefault(raw);
    final var tabled = NullDefaults.defaultFor(generic);
    final var emptyContainer = tabled instanceof Collection<?> || tabled instanceof Map<?, ?>;
    if (!emptyContainer && !(tabled instanceof Optional<?>)) return null;
    return raw == null || raw.isInstance(tabled) ? tabled : null;
  }
}
