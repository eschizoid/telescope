package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Telescope.matchedNames;
import static io.github.eschizoid.telescope.Telescope.methodNameOf;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Intermediate of {@link MapBeanFrom#to(Class)}. */
public final class MapBeanTo<A, B> {

  private final Class<A> source;
  private final Class<B> target;
  private final Map<String, String> sourceToTarget = new LinkedHashMap<>();
  private boolean ignoreUnmatched = false;

  MapBeanTo(final Class<A> source, final Class<B> target) {
    this.source = source;
    this.target = target;
  }

  /**
   * Map a differently-named property across the boundary: {@code A}'s {@code from} property
   * supplies (and is supplied by) {@code B}'s {@code to} property. Types must match. Properties not
   * named here still match by name.
   *
   * <pre>{@code
   * Telescope.mapBean(LegacyUser.class).to(UserView.class)
   *     .rename(LegacyUser::getName, UserView::getFullName)
   *     .build();
   * }</pre>
   */
  public <X> MapBeanTo<A, B> rename(final Accessor<A, X> from, final Accessor<B, X> to) {
    sourceToTarget.put(Beans.propertyOf(methodNameOf(from)), Beans.propertyOf(methodNameOf(to)));
    return this;
  }

  /**
   * Drop the bijection requirement: a property with no counterpart on the other side is simply not
   * transferred (it keeps the rebuilt object's default). The result is lossy — a round-trip won't
   * restore the dropped fields.
   */
  public MapBeanTo<A, B> ignoreUnmatched() {
    this.ignoreUnmatched = true;
    return this;
  }

  /** Build the bidirectional {@code Telescope<A, B>}. */
  public Telescope<A, B> build() {
    final var targetToSource = new LinkedHashMap<String, String>();
    sourceToTarget.forEach((s, t) -> targetToSource.put(t, s));
    final var bKeys = matchedNames(Beans.propertyNames(target), targetToSource, source, ignoreUnmatched, (name, cp) ->
      mismatch(name, target, cp, source)
    ).toArray(String[]::new);
    final var aKeys = matchedNames(Beans.propertyNames(source), sourceToTarget, target, ignoreUnmatched, (name, cp) ->
      mismatch(name, source, cp, target)
    ).toArray(String[]::new);
    final var writerA = Beans.autoWriter(source);
    final var writerB = Beans.autoWriter(target);
    final Function<A, B> forward = a ->
      writerB.construct(bKeys, bProp -> Beans.readProperty(a, targetToSource.getOrDefault(bProp, bProp)));
    final Function<B, A> backward = b ->
      writerA.construct(aKeys, aProp -> Beans.readProperty(b, sourceToTarget.getOrDefault(aProp, aProp)));
    return new Telescope<>(Iso.of(forward, backward));
  }

  private static RuntimeException mismatch(
    final String name,
    final Class<?> owner,
    final String counterpart,
    final Class<?> other
  ) {
    return new IllegalArgumentException(
      "mapBean: property '" +
        name +
        "' on " +
        owner.getSimpleName() +
        " has no matching getter '" +
        counterpart +
        "' on " +
        other.getSimpleName() +
        " (rename it with .rename(...), or call .ignoreUnmatched() to drop it)."
    );
  }
}
