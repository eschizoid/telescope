package io.github.eschizoid.telescope.internal.optics;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Read+write exactly-one: focuses on exactly one {@code A} inside an {@code S} — every {@code S}
 * has one, and you can always write a new one back. Lens is the workhorse; most record-field
 * navigation produces a Lens.
 *
 * <p>Use it for required record components: {@code User::name}, {@code Address::city}. If the
 * component is an {@code Optional} or part of a sealed hierarchy, reach for a {@link Prism} (or a
 * Lens composed with one).
 *
 * <pre>{@code
 * final var name = Lens.<User, String>of(User::name, (u, n) -> new User(n, u.age()));
 * final var who = name.get(user);                  // read the one A
 * final var renamed = name.set(user, "Ada");       // write a new A
 * final var upper = name.modify(user, String::toUpperCase);
 * }</pre>
 *
 * <h2>Composition (Lens as outer)</h2>
 *
 * <ul>
 *   <li>{@code Lens.then(Lens)} → {@link Lens}, {@code Lens.then(Iso)} → {@link Lens}
 *   <li>{@code Lens.then(Prism)} → {@link Affine} (the Prism may miss, so reconstructibility is
 *       lost)
 *   <li>{@code Lens.then(Traversal)} → {@link Traversal} (inherited; widening is one-way)
 * </ul>
 *
 * <h2>Laws</h2>
 *
 * <ul>
 *   <li>get-set: {@code lens.set(s, lens.get(s)).equals(s)} — setting back what you got is a no-op
 *   <li>set-get: {@code lens.get(lens.set(s, a)).equals(a)} — what you set is what you read
 *   <li>set-set: {@code lens.set(lens.set(s, a1), a2).equals(lens.set(s, a2))} — the last set wins
 * </ul>
 */
public interface Lens<S, A> extends Affine<S, A>, Getter<S, A> {
  /** Read the one focused {@code A} out of {@code source}. */
  @Override
  A get(S source);

  /** Write {@code value} as the focused {@code A}, returning a new {@code S}. */
  @Override
  S set(S source, A value);

  /**
   * Null-source write semantics: {@code modify(null, f)} skips the strict {@code get(null)} call
   * that would NPE through the captured method-reference receiver and instead invokes {@code
   * f.apply(null)} then delegates to {@code set(null, value)}. The end-to-end behaviour of {@code
   * modify(null, f)} therefore reduces to the concrete lens's {@code set(null, value)} contract;
   * this default does not itself fabricate a focus.
   *
   * <p>Behavior by lens shape:
   *
   * <ul>
   *   <li>{@code Beans.lens(Class, String, BeanWriter)} backed by {@code SettersWriter} — {@code
   *       set(null, value)} rebuilds a fresh focus. Off-path reads route through {@code
   *       readForRebuild} → {@code readProperty}, both of which short-circuit on null source, so
   *       the writer receives null for every off-path property and {@code SettersWriter}'s
   *       construct path null-guards primitive setters with the JLS default. This is the reflective
   *       bean path the multi-hop {@code mapperForward} writes rely on by default, regardless of
   *       focus property count.
   *   <li>{@code @BeanFocus} codegen-emitted holder lens — the generated setter reads off-path
   *       properties off the source: {@code (p, v) -> { final var c = new X();
   *       c.setOther(p.getOther()); c.setTarget(v); return c; }}. {@code set(null, value)} works
   *       for a single-property focus (no off-path read) but NPEs on {@code p.getOther()} for a
   *       multi-property focus. Multi-property null intermediates still crash loudly through the
   *       codegen setter and are out of scope for this default; the reflective fallback above
   *       covers the multi-property case when {@code @BeanFocus} is not in play.
   *   <li>{@code Beans.lens} backed by {@code ConstructorWriter} / {@code FieldsWriter} / {@code
   *       BuilderWriter} — these strategies pass the focused value alongside the writer's per-name
   *       lookup but do NOT null-guard primitive parameters/fields/builder setters. A null off-path
   *       primitive surfaces as an NPE: {@code ConstructorWriter} / {@code FieldsWriter} wrap it
   *       with their "Failed to construct" / "Failed to set field" diagnostic; {@code
   *       BuilderWriter} propagates it raw (intentional dispatch-time-symmetry — see {@code
   *       BuilderWriter#construct}). Either way the failure is loud, not silent. For null-tolerant
   *       N-hop writes pick the SETTERS strategy (the {@code autoWriter} default) or model the
   *       target with reference-typed fields.
   *   <li>{@code Records.fieldLens} (both string and class-aware overloads) — overrides {@code
   *       modify} with an explicit {@code null}-source short-circuit returning {@code null}; this
   *       default never runs and the null-source write surfaces as a missing-leaf result rather
   *       than a fabricated record.
   *   <li>{@code @Focus} canonical-ctor codegen lens — the generated setter reads sibling
   *       components off the source ({@code (s, v) -> new R(v, s.other())}); {@code set(null,
   *       value)} NPEs on the off-path read. Record write paths through a null intermediate
   *       therefore still crash loudly at the missing hop, by design.
   * </ul>
   *
   * <p>Strict direct {@code .get(null)} on the atomic Lens stays strict (consistent with {@link
   * #getOption} which preserves the strict {@code Optional.of} carrier on non-null sources whose
   * getter returns {@code null}). The asymmetry is intentional: reads of a missing intermediate
   * should surface, writes through a missing intermediate should construct the path when the focus
   * type's setter can support it.
   */
  @Override
  default S modify(final S source, final Function<? super A, ? extends A> f) {
    final A current = source == null ? null : get(source);
    return set(source, f.apply(current));
  }

  /**
   * Affine projection of this Lens: a {@code null} source yields {@link Optional#empty()} rather
   * than dispatching {@code get(null)} through the captured method reference. For a non-null source
   * the strict {@code Optional.of(get(source))} form is preserved — lens-law preservation requires
   * that a Lens whose getter returns {@code null} on a non-null source surfaces the violation
   * directly; {@code Optional.of} is the strict carrier and an NPE is the right signal. {@link
   * #getAll} can carry the same {@code null} result inside a stream because streams accept {@code
   * null} elements — that asymmetry is between the two carrier types, not between the two
   * projections. When the focused {@code A} is genuinely nullable in source data, model that with
   * an {@link Affine} or {@link Prism} instead of a Lens.
   *
   * @throws NullPointerException if {@code source} is non-null and {@code get(source)} returns
   *     {@code null}. In a composed read the NPE surfaces from the method-reference receiver
   *     dispatch at whichever lens leaf first receives a {@code null} input — the carrier {@link
   *     Optional#of} call itself is only reached when the very outermost {@code getOption} sees a
   *     non-null source whose top-level getter returns {@code null}.
   */
  @Override
  default Optional<A> getOption(final S source) {
    if (source == null) return Optional.empty();
    return Optional.of(get(source));
  }

  /**
   * Traversal projection of this Lens: a {@code null} source yields an empty stream rather than
   * dispatching {@code get(null)} through the captured method reference. Lets multi-hop bean paths
   * short-circuit on nullable intermediates inside composed reads. A non-null source whose getter
   * legitimately returns {@code null} produces a one-element {@code Stream.of(null)} — {@code
   * Stream} is the lenient carrier (cf. {@link #getOption} where {@link Optional} is strict).
   * Downstream stream operators that route through {@link Optional#of} ({@link
   * java.util.stream.Stream#findFirst}, for example) will NPE on that null element; callers that
   * care about preserving the empty-vs-null distinction should drain via iterator. Direct {@code
   * .get(null)} on the atomic Lens still NPEs.
   */
  @Override
  default Stream<A> getAll(final S source) {
    if (source == null) return Stream.empty();
    return Stream.of(get(source));
  }

  /** Build a Lens from a getter and an {@code (S, A) -> S} setter. */
  static <S, A> Lens<S, A> of(
    final Function<? super S, ? extends A> get,
    final BiFunction<? super S, ? super A, ? extends S> set
  ) {
    return new Lens<>() {
      @Override
      public A get(final S source) {
        return get.apply(source);
      }

      @Override
      public S set(final S source, final A value) {
        return set.apply(source, value);
      }
    };
  }

  /** {@code Lens . Lens = Lens} */
  default <B> Lens<S, B> then(final Lens<A, B> next) {
    final var self = this;
    return new Lens<>() {
      @Override
      public B get(final S source) {
        return next.get(self.get(source));
      }

      @Override
      public S set(final S source, final B value) {
        return self.set(source, next.set(self.get(source), value));
      }
    };
  }

  /** {@code Lens . Iso = Lens} */
  default <B> Lens<S, B> then(final Iso<A, B> next) {
    final var self = this;
    return new Lens<>() {
      @Override
      public B get(final S source) {
        return next.to(self.get(source));
      }

      @Override
      public S set(final S source, final B value) {
        return self.set(source, next.from(value));
      }
    };
  }

  /** {@code Lens . Prism = Affine} */
  default <B> Affine<S, B> then(final Prism<A, B> next) {
    final var self = this;
    return new Affine<>() {
      @Override
      public Optional<B> getOption(final S source) {
        return next.getOption(self.get(source));
      }

      @Override
      public S modify(final S source, final Function<? super B, ? extends B> f) {
        final var a = self.get(source);
        return next
          .getOption(a)
          .map(b -> self.set(source, next.reverseGet(f.apply(b))))
          .orElse(source);
      }
    };
  }
}
