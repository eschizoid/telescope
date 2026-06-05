package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.Telescope.Accessor;
import java.util.function.Function;

/**
 * One field correspondence in a {@link Telescope#map(Mapping[])} call. Build with the static
 * factories ({@link #to}, {@link #via}, {@link #auto()}, {@link #auto(Class, Class)}) — intended to
 * be static-imported so the call site reads as a list of rows.
 *
 * <pre>{@code
 * import static io.github.eschizoid.telescope.Mapping.to;
 * import static io.github.eschizoid.telescope.Mapping.via;
 * import static io.github.eschizoid.telescope.Mapping.auto;
 *
 * final Telescope<UserEntity, UserDto> userMapper = Telescope.map(
 *     to(UserEntity::name,    UserDto::fullName),                     // rename, same type
 *     via(UserEntity::address, UserDto::address, addressMapper),      // nested mapper
 *     auto());                                                        // backfill same-name
 * }</pre>
 *
 * <p>Symmetrical with {@link Edit#over(Telescope, Function)} / {@link Telescope#all(Edit[])} —
 * varargs of typed rows, declarative, count-visible-at-a-glance.
 *
 * <p><b>Class inference.</b> {@link #to}, {@link #via} carry the source/target record classes in
 * the {@link Accessor} method references; {@link Telescope#map(Mapping[])} recovers them via {@code
 * SerializedLambda} from the first explicit row it finds. {@link #auto()} has no accessors and
 * rides on a sibling row's inference. A {@code Telescope.map(auto())} with no explicit row to ride
 * on cannot recover the classes — use {@link #auto(Class, Class)} instead (or fall back to the
 * fluent {@link Telescope#map(Class)} builder).
 */
public sealed interface Mapping<A, B> {
  /**
   * Same-typed correspondence: {@code src↔tgt}, both with leaf type {@code X}. Identity transforms
   * in both directions. The clean rename case.
   *
   * <pre>{@code
   * to(UserEntity::name, UserDto::fullName)
   * }</pre>
   */
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
   * Nested correspondence: map {@code src}'s leaf through another {@link Mapper}. The mapper
   * supplies both directions.
   *
   * <pre>{@code
   * via(UserEntity::address, UserDto::address, addressMapper)
   * }</pre>
   */
  static <A, B, X, Y> Mapping<A, B> via(final Accessor<A, X> src, final Accessor<B, Y> tgt, final Mapper<X, Y> nested) {
    return new Via<>(src, tgt, nested);
  }

  /**
   * Auto-fill every target component whose name matches a source component (identity transforms).
   * Rides on a sibling row's class inference; if every row in {@link Telescope#map(Mapping[])} is
   * {@code auto()} with no explicit row, the call fails at construction — use {@link #auto(Class,
   * Class)} for the pure-auto case.
   */
  static <A, B> Mapping<A, B> auto() {
    return new AutoInfer<>();
  }

  /**
   * Like {@link #auto()} but with the source/target record classes supplied explicitly. Use when
   * the mapping is <em>only</em> auto rows (no {@code to} or {@code via} to infer from).
   *
   * <pre>{@code
   * Telescope.map(auto(UserEntity.class, UserDto.class));   // pure same-name copy
   * }</pre>
   */
  static <A, B> Mapping<A, B> auto(final Class<A> source, final Class<B> target) {
    return new AutoExplicit<>(source, target);
  }

  /** Apply this row onto the builder. Internal contract — not for user code. */
  void apply(MapBuilder<A, B> mb);

  /**
   * Source class if this row can reveal one at runtime (via {@code SerializedLambda}); {@code null}
   * for {@link #auto()}. Used by {@link Telescope#map(Mapping[])} to recover the classes needed to
   * construct the underlying {@link MapBuilder}.
   */
  Class<A> sourceClass();

  /** Target class if this row can reveal one; {@code null} for {@link #auto()}. */
  Class<B> targetClass();
}

record SameTypedTo<A, B, X>(Accessor<A, X> src, Accessor<B, X> tgt) implements Mapping<A, B> {
  @Override
  public void apply(final MapBuilder<A, B> mb) {
    mb.field(src).to(tgt);
  }

  @Override
  public Class<A> sourceClass() {
    return Telescope.implClassOf(src);
  }

  @Override
  public Class<B> targetClass() {
    return Telescope.implClassOf(tgt);
  }
}

record TypedTransformTo<A, B, X, Y>(
  Accessor<A, X> src,
  Accessor<B, Y> tgt,
  Function<? super X, ? extends Y> forward,
  Function<? super Y, ? extends X> backward
) implements Mapping<A, B> {
  @Override
  public void apply(final MapBuilder<A, B> mb) {
    mb.field(src).to(tgt, forward, backward);
  }

  @Override
  public Class<A> sourceClass() {
    return Telescope.implClassOf(src);
  }

  @Override
  public Class<B> targetClass() {
    return Telescope.implClassOf(tgt);
  }
}

record Via<A, B, X, Y>(Accessor<A, X> src, Accessor<B, Y> tgt, Mapper<X, Y> nested) implements Mapping<A, B> {
  @Override
  public void apply(final MapBuilder<A, B> mb) {
    mb.field(src).via(tgt, nested);
  }

  @Override
  public Class<A> sourceClass() {
    return Telescope.implClassOf(src);
  }

  @Override
  public Class<B> targetClass() {
    return Telescope.implClassOf(tgt);
  }
}

record AutoInfer<A, B>() implements Mapping<A, B> {
  @Override
  public void apply(final MapBuilder<A, B> mb) {
    mb.auto();
  }

  @Override
  public Class<A> sourceClass() {
    return null;
  }

  @Override
  public Class<B> targetClass() {
    return null;
  }
}

record AutoExplicit<A, B>(Class<A> source, Class<B> target) implements Mapping<A, B> {
  @Override
  public void apply(final MapBuilder<A, B> mb) {
    mb.auto();
  }

  @Override
  public Class<A> sourceClass() {
    return source;
  }

  @Override
  public Class<B> targetClass() {
    return target;
  }
}
