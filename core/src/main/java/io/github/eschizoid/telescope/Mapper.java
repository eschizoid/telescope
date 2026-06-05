package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.internal.Records;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.util.List;

/**
 * A bidirectional record mapper produced by {@link MapBuilder#buildMapper()}. Beyond the conversion
 * that {@link MapBuilder#build()} gives you, a {@code Mapper} retains the field links so it can
 * apply a sparse {@link #patch} and be nested inside another mapping via {@link FieldMapping#via}.
 */
public final class Mapper<A, B> {

  private final Iso<A, B> iso;
  private final List<MapBuilder.Link> links;

  Mapper(final Iso<A, B> iso, final List<MapBuilder.Link> links) {
    this.iso = iso;
    this.links = links;
  }

  /**
   * Convert forward, {@code A → B}.
   *
   * <pre>{@code
   * final var mapper = Telescope.map(UserEntity.class).to(UserDto.class).auto().buildMapper();
   * final UserDto dto = mapper.read(entity);
   * }</pre>
   *
   * For the reverse direction, or to thread the conversion through a longer path, use {@link
   * #asTelescope()} (which exposes {@code set}/{@code update}/{@code then}); for a sparse overlay,
   * use {@link #patch}.
   */
  public B read(final A a) {
    return iso.to(a);
  }

  /**
   * The mapper as a composable {@code Telescope<A, B>}, for threading the conversion through longer
   * paths via {@link Telescope#then}.
   *
   * <pre>{@code
   * Telescope.of(EntityPage.class)
   *     .each(EntityPage::items)
   *     .then(userMapper.asTelescope())
   *     .field(UserDto::email)
   *     .update(page, String::toLowerCase);
   * }</pre>
   */
  public Telescope<A, B> asTelescope() {
    return new Telescope<>(iso);
  }

  /**
   * Sparse update: overlay the non-null fields of {@code patch} (a partially-populated target) onto
   * {@code base}, leaving the rest of {@code base} untouched. Each present target field is run back
   * through its link's reverse transform before being written to the source.
   *
   * <pre>{@code
   * // dtoPatch has a new email, null everything else — only the email changes on the entity:
   * UserEntity updated = userMapper.patch(entity, dtoPatch);
   * }</pre>
   */
  public A patch(final A base, final B patch) {
    var result = base;
    for (final var l : links) {
      final var targetValue = Records.read(patch, l.targetField());
      if (targetValue != null) {
        result = Records.with(result, l.sourceField(), l.backward().apply(targetValue));
      }
    }
    return result;
  }

  B forward(final A a) {
    return iso.to(a);
  }

  A backward(final B b) {
    return iso.from(b);
  }
}
