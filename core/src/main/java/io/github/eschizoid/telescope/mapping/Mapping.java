package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Edit;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.conversion.Mapper;
import List;
import Map;
import Optional;
import Set;
import java.util.function.Function;

/**
 * One field correspondence in a {@link Telescope#map(Class, Class, MapStep...)} call — supplies an
 * override for a specific {@code (sourceClass, targetClass)} type pair anywhere in the deep
 * recursive traversal. Build with the static factories ({@link #to}, {@link #via}) — intended to be
 * static-imported so the call site reads as a list of rows.
 *
 * <pre>{@code
 * import static io.github.eschizoid.telescope.mapping.Mapping.to;
 * import static io.github.eschizoid.telescope.mapping.Mapping.via;
 *
 * final Telescope<CompanyEntity, CompanyDto> companyMapper = Telescope.map(
 *     CompanyEntity.class, CompanyDto.class,
 *     to(CompanyEntity::founded, CompanyDto::since),        // top-level rename
 *     to(UserEntity::name,       UserDto::fullName));       // applies wherever User↔UserDto recurses
 * }</pre>
 *
 * <p>Symmetrical with {@link Edit#over(Telescope, Function)} / {@link Telescope#all(Edit[])} —
 * varargs of typed rows, declarative, count-visible-at-a-glance.
 *
 * <p><b>Type-pair keying.</b> Each {@link #to to(srcAcc, tgtAcc)} / {@link #via via(srcAcc, tgtAcc,
 * mapper)} row carries the declaring classes of its accessors via {@code SerializedLambda}. {@link
 * Telescope#map(Class, Class, MapStep...)} keys overrides by {@code (sourceClass, targetClass)} so
 * a single row applies wherever the recursion lands on that pair — top level or N levels deep.
 *
 * <p><b>Permitted impls.</b> Sealed over three package-private records in sibling files in this
 * package — {@link SameTypedTo}, {@link TypedTransformTo}, {@link Via}. Users construct via the
 * static factories below; the record types are not public API.
 */
public sealed interface Mapping<A, B> extends MapStep permits SameTypedTo, TypedTransformTo, Via {
  /** Same-typed correspondence: {@code src↔tgt}, both with leaf type {@code X}. Identity. */
  static <A, B, X> Mapping<A, B> to(final Accessor<A, X> src, final Accessor<B, X> tgt) {
    return new SameTypedTo<>(src, tgt);
  }

  /**
   * Typed-transform correspondence: {@code src} has leaf {@code X}, {@code tgt} has leaf {@code Y};
   * supply both directions of the conversion so the overall mapping stays a bijection
   * (composition-safe).
   *
   * <pre>{@code
   * to(UserEntity::createdAt, UserDto::createdAtIso, Instant::toString, Instant::parse)
   * }</pre>
   */
  static <A, B, X, Y> Mapping<A, B> to(
    final Accessor<A, X> src,
    final Accessor<B, Y> tgt,
    final Function<? super X, ? extends Y> forward,
    final Function<? super Y, ? extends X> backward
  ) {
    return new TypedTransformTo<>(src, tgt, forward, backward);
  }

  /**
   * Nested correspondence: map {@code src}'s leaf through a pre-built {@link Mapper}. The mapper
   * supplies both directions, and any custom rules it bakes in (typed transforms, nested mappers of
   * its own) survive — the deep recursion uses it as-is at this slot instead of building its own.
   *
   * <pre>{@code
   * via(UserEntity::address, UserDto::address, addressMapper)
   * }</pre>
   */
  static <A, B, X, Y> Mapping<A, B> via(final Accessor<A, X> src, final Accessor<B, Y> tgt, final Mapper<X, Y> nested) {
    return new Via<>(src, tgt, nested);
  }

  /**
   * {@code via(...)} overload for {@code List}-typed accessors that accepts an <em>element-level</em>
   * mapper and lifts it through the list internally. Without this overload, the user would have to
   * either hoist every row from the element-pair mapper up to the parent factory or call
   * {@code elementMapper.liftList()} explicitly.
   *
   * <pre>{@code
   * via(TeamEntity::members, TeamDto::members, userMapper) // userMapper: Mapper<UserEntity, UserDto>
   * }</pre>
   */
  static <A, B, X, Y> Mapping<A, B> via(
    final Accessor<A, List<X>> src,
    final Accessor<B, List<Y>> tgt,
    final Mapper<X, Y> elementMapper
  ) {
    return new Via<>(src, tgt, elementMapper.liftList());
  }

  /** {@code via(...)} overload that lifts an element mapper through a {@code Set}-typed accessor pair. */
  static <A, B, X, Y> Mapping<A, B> via(
    final Accessor<A, Set<X>> src,
    final Accessor<B, Set<Y>> tgt,
    final Mapper<X, Y> elementMapper
  ) {
    return new Via<>(src, tgt, elementMapper.liftSet());
  }

  /** {@code via(...)} overload that lifts an element mapper through an {@code Optional}-typed accessor pair. */
  static <A, B, X, Y> Mapping<A, B> via(
    final Accessor<A, Optional<X>> src,
    final Accessor<B, Optional<Y>> tgt,
    final Mapper<X, Y> elementMapper
  ) {
    return new Via<>(src, tgt, elementMapper.liftOptional());
  }

  /**
   * {@code via(...)} overload that lifts a value-element mapper through a {@code Map<K, X>} ↔
   * {@code Map<K, Y>} accessor pair (keys must match exactly; only the values flow through the
   * element mapper).
   */
  static <A, B, K, X, Y> Mapping<A, B> via(
    final Accessor<A, Map<K, X>> src,
    final Accessor<B, Map<K, Y>> tgt,
    final Mapper<X, Y> elementMapper
  ) {
    return new Via<>(src, tgt, elementMapper.liftMapValues());
  }
}
