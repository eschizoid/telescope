package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import java.util.Map;
import java.util.function.Function;

/**
 * One row in a {@link Telescope#fromMap(Class, MapExtractStep...) Telescope.fromMap(...)} factory
 * call — names a key in the untyped source {@code Map<String, Object>}, the target accessor that
 * receives the converted value, and a per-row converter that turns the raw map value into the typed
 * target value.
 *
 * <p>Build rows via the static factory {@link #extract(String, Accessor, Function)} —
 * static-imported it reads as a list of correspondences alongside the {@link Mapping#to(Accessor,
 * Accessor)} rows on the typed surface.
 *
 * <p>Sealed. Today's only permit is {@link Extract}; future expansions (nested extracts,
 * conditional gates, required-key validation) extend the sealed surface — same pattern as {@link
 * MapStep}.
 */
public sealed interface MapExtractStep permits Extract {
  /** The key to look up in the source {@code Map<String, Object>}. */
  String key();

  /**
   * Target accessor — the method reference that names the target component receiving the converted
   * value. Recovered via {@code SerializedLambda} the same way the typed {@code Mapping.to(...)}
   * row does, so the field name + type flow into the rebuild step without an explicit declaration.
   */
  Accessor<?, ?> targetAccessor();

  /**
   * Converter — turns the raw {@code Object} read from the map into the target component's typed
   * value. The factory is responsible for null-safety semantics; the converter sees whatever the
   * map produced (including {@code null} when the key is absent or absent-mapped).
   */
  Function<Object, ?> converter();

  /**
   * Static factory for an {@link Extract} row. Static-import: {@code import static
   * io.github.eschizoid.telescope.mapping.MapExtractStep.extract;}
   *
   * <pre>{@code
   * ForwardMapper<Map<String, Object>, CaseListRequest> m = Telescope.fromMap(
   *     CaseListRequest.class,
   *     extract("bookingType", CaseListRequest::getBookingType, Object::toString),
   *     extract("caseId",      CaseListRequest::getCaseId,      Object::toString),
   *     extract("priority",    CaseListRequest::getPriority,    v -> Integer.parseInt(v.toString())));
   * }</pre>
   *
   * @param key the map key the row pulls its raw value from
   * @param targetAccessor method reference naming the target field/component
   * @param converter raw map value → typed target value
   */
  static <T, X> MapExtractStep extract(
    final String key,
    final Accessor<T, X> targetAccessor,
    final Function<Object, X> converter
  ) {
    return new Extract<>(key, targetAccessor, converter);
  }

  /**
   * Static factory for a nested {@link Extract} row whose converter is itself a {@link
   * Telescope#fromMap(Class, MapExtractStep...) fromMap} mapper — the row's raw value is a nested
   * {@code Map<String, Object>} that the {@code nested} mapper turns into the target component.
   * Composes {@code fromMap} declaratively, so a nested map fills a nested POJO without a
   * hand-rolled converter that casts and calls a static method:
   *
   * <pre>{@code
   * Telescope.fromMap(CaseListRequest.class,
   *     extract("caseId", CaseListRequest::getCaseId, Object::toString),
   *     extract("pageDetails", CaseListRequest::getPageDetails,
   *         Telescope.fromMap(PageDetails.class,
   *             extract("pageSize", PageDetails::getPageSize, v -> (Integer) v))));
   * }</pre>
   *
   * <p>The unchecked {@code Object → Map<String, Object>} cast the nested map requires lives here,
   * once, instead of at every call site. An absent key (a {@code null} raw value) yields a {@code
   * null} component — {@link ForwardMapper#forward} is null-in/null-out.
   *
   * @param key the map key the row pulls its nested map from
   * @param targetAccessor method reference naming the target field/component
   * @param nested a {@code fromMap} mapper that converts the nested map into the component value
   */
  static <T, X> MapExtractStep extract(
    final String key,
    final Accessor<T, X> targetAccessor,
    final ForwardMapper<Map<String, Object>, X> nested
  ) {
    @SuppressWarnings("unchecked")
    final Function<Object, X> converter = v -> nested.forward((Map<String, Object>) v);
    return extract(key, targetAccessor, converter);
  }
}
