package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Telescope.methodNameOf;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.Records;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Accumulates field correspondences for {@link Telescope#map(Class)}. {@link #build()} synthesizes
 * a bidirectional {@code Telescope<A, B>}; {@link #buildMapper()} additionally retains the field
 * links so the result supports {@link Mapper#patch}.
 */
public final class MapBuilder<A, B> {

  /**
   * One field correspondence with (possibly identity) transforms in each direction.
   *
   * <p>Package-private so the sibling-file builders ({@link FieldMapping}, {@link Mapper}) can
   * reference it without the construction being part of the public surface.
   */
  record Link(
    String sourceField,
    String targetField,
    Function<Object, Object> forward,
    Function<Object, Object> backward
  ) {}

  private static final Function<Object, Object> IDENTITY = x -> x;

  private final Class<A> source;
  private final Class<B> target;
  private final List<Link> links = new ArrayList<>();

  MapBuilder(final Class<A> source, final Class<B> target) {
    this.source = source;
    this.target = target;
  }

  /**
   * Auto-map every target component whose name matches a source component, leaving any
   * already-declared correspondences untouched. Matches by name only with identity transforms — no
   * fuzzy heuristics, no type checking, no nested traversal. A type mismatch on an auto-linked pair
   * surfaces at record construction time (when {@link #build()} runs the forward/backward
   * functions), not here. Declare {@code .field(...).to(...)} explicitly for renames or transforms
   * (those override the auto-mapped link for the same target).
   *
   * <pre>{@code
   * // id + email map by name; only the renamed field is declared by hand:
   * final var userMapper = Telescope.map(UserEntity.class).to(UserDto.class)
   *     .field(UserEntity::name).to(UserDto::fullName)   // wins over auto for this target
   *     .auto()                                          // id, email
   *     .build();
   * }</pre>
   */
  public MapBuilder<A, B> auto() {
    final var sourceNames = Set.of(Records.componentNames(source));
    for (final var name : Records.componentNames(target)) {
      final var alreadyLinked = links.stream().anyMatch(l -> l.targetField().equals(name));
      if (!alreadyLinked && sourceNames.contains(name)) {
        links.add(new Link(name, name, IDENTITY, IDENTITY));
      }
    }
    return this;
  }

  /**
   * Declare the source side of a field correspondence; complete it on the returned {@link
   * FieldMapping} with {@link FieldMapping#to(Accessor) .to(...)} (same-typed), {@link
   * FieldMapping#to(Accessor, Function, Function) .to(..., fwd, bwd)} (typed transform), or {@link
   * FieldMapping#via .via(..., mapper)} (nested record).
   */
  public <X> FieldMapping<A, B, X> field(final Accessor<A, X> sourceGetter) {
    return new FieldMapping<>(this, methodNameOf(sourceGetter));
  }

  MapBuilder<A, B> link(final Link link) {
    links.removeIf(l -> l.targetField().equals(link.targetField()));
    links.add(link);
    return this;
  }

  /**
   * Synthesize the bidirectional {@code Telescope<A, B>}. Throws {@link IllegalStateException} if
   * the mapping isn't a bijection (some component on either side is left unmapped). Use {@link
   * #buildMapper()} instead when you also want {@link Mapper#patch} or to nest the mapping via
   * {@link FieldMapping#via}.
   */
  public Telescope<A, B> build() {
    return new Telescope<>(iso());
  }

  /**
   * Synthesize a {@link Mapper} — the same bidirectional conversion as {@link #build()}, plus the
   * field links retained so it can do {@link Mapper#patch sparse patches}.
   */
  public Mapper<A, B> buildMapper() {
    return new Mapper<>(iso(), List.copyOf(links));
  }

  private Iso<A, B> iso() {
    final var byTarget = new LinkedHashMap<String, Link>();
    final var bySource = new LinkedHashMap<String, Link>();
    for (final var l : links) {
      byTarget.put(l.targetField(), l);
      bySource.put(l.sourceField(), l);
    }
    requireAllMapped(Records.componentNames(target), byTarget.keySet(), target, "target");
    requireAllMapped(Records.componentNames(source), bySource.keySet(), source, "source");

    final Function<A, B> forward = a ->
      Records.construct(target, t -> {
        final var l = byTarget.get(t);
        return l.forward().apply(Records.read(a, l.sourceField()));
      });
    final Function<B, A> backward = b ->
      Records.construct(source, s -> {
        final var l = bySource.get(s);
        return l.backward().apply(Records.read(b, l.targetField()));
      });
    return Iso.of(forward, backward);
  }

  private static void requireAllMapped(
    final String[] names,
    final Set<String> mapped,
    final Class<?> type,
    final String side
  ) {
    for (final var name : names) {
      if (!mapped.contains(name)) throw new IllegalStateException(
        "Mapping is not a bijection: " +
          side +
          " field '" +
          name +
          "' on " +
          type.getSimpleName() +
          " is unmapped. Every component on both sides must be mapped (try .auto() for same-name fields)."
      );
    }
  }
}
