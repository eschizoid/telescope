package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.introspection.OpticReport;
import io.github.eschizoid.telescope.introspection.Trace;
import io.github.eschizoid.telescope.mapping.MapStep;
import java.util.List;
import java.util.function.Supplier;

/**
 * A mapper whose direction is the edit's, built by {@link Telescope#edits(Class, Class,
 * MapStep...)}: {@code P} is what an edit sends, such as the body of a PATCH, a create request or
 * the changes a service works out, and {@code E} is what it writes into, usually an entity. Its
 * rows read from {@code P} and write to {@code E}, so a {@code compute} or {@code constant} row
 * runs on the way in.
 *
 * <pre>{@code
 * EDITS.patchInto(order, changes); // writes only what the client sent, in place
 * EDITS.into(order, replacement);  // a PUT: every property the mapper accounts for, a null included
 * final Order created = EDITS.forward(request);
 * }</pre>
 *
 * <p>The mapper is strict on both sides. When it is built, every property of {@code E} that a
 * member can write is filled by a row, by the property of its name on {@code P}, or by the
 * collection write as the back-reference of an element, or a {@code keep} row says no edit writes
 * it; and every property of {@code P} is read by a row or by the property of its name on {@code E},
 * or a {@code drop} row leaves it out. With {@code telescope-jpa}, an identifier with a generator
 * and a version count as accounted for too: no operation writes them into a target it is handed,
 * and a new target built by {@code forward} carries the value the source sends under their name,
 * which is how a collection of entities is matched by {@code @Id}.
 *
 * <p>There is no {@code backward} and no reverse view: a {@code compute}, a {@code constant} and a
 * {@code keep} have no inverse, and what a client sees of an entity is a mapper of its own. An edit
 * mapper is neither a {@link Mapper} nor a {@link ForwardMapper}.
 *
 * @param <P> what an edit sends
 * @param <E> what it writes into
 */
public class EditMapper<P, E> {

  // The mapper that converts forward and writes in place: the runtime path's, or the one over the
  // generated bridge.
  private final Mapper<P, E> engine;
  // The generated bridge this mapper runs, or null on the runtime path.
  private final BridgeFn<P, E> generated;
  // Over a generated bridge with no in-place writes of its own, the mapper the runtime path builds
  // from the same rows, which into, patchInto and patch run; built the first time one is called.
  private final Supplier<EditMapper<P, E>> runtime;
  private volatile EditMapper<P, E> built;
  private final List<OpticNode> explainTrail;
  // The services the mapper's rows call, its own and those of the mappers it converts through, or
  // null where it calls none.
  private final ServiceNeeds services;

  private EditMapper(
    final Mapper<P, E> engine,
    final BridgeFn<P, E> generated,
    final Supplier<EditMapper<P, E>> runtime,
    final List<OpticNode> explainTrail
  ) {
    this.engine = engine;
    this.generated = generated;
    this.runtime = runtime;
    this.explainTrail = List.copyOf(explainTrail);
    this.services = null;
  }

  /**
   * Codegen-support seam: the state of {@code built}, an edit mapper over a generated bridge, for
   * the class the {@code @Bridge} processor generates for a field. That class overrides {@link
   * #forward}, {@link #into}, {@link #patchInto} and {@link #patch} with one static call each into
   * the field's bridge; every other method is this class's own, and final. {@code grant} is as for
   * the matching {@link Mapper} constructor.
   *
   * @throws IllegalArgumentException if {@code grant} is not the one core hands out, or {@code
   *     built} is not an edit mapper over a generated bridge
   */
  protected EditMapper(final EditMapper<P, E> built, final BridgeGrant grant) {
    if (grant != BridgeGrant.INSTANCE) throw new IllegalArgumentException(
      "Only the class generated for a @Bridge field extends EditMapper, with the grant its provider is handed"
    );
    if (built.generated == null) throw new IllegalArgumentException(
      "Only an edit mapper over a generated bridge is specialised by a generated class"
    );
    this.engine = built.engine;
    this.generated = built.generated;
    this.runtime = built.runtime;
    this.explainTrail = built.explainTrail;
    this.services = built.services;
  }

  /** {@code from}, holding {@code services}. */
  private EditMapper(final EditMapper<P, E> from, final ServiceNeeds services) {
    this.engine = from.engine;
    this.generated = from.generated;
    this.runtime = from.runtime;
    this.explainTrail = from.explainTrail;
    this.services = services;
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> {@code mapper}, holding {@code services}, the
   * services its rows call.
   */
  public static <P, E> EditMapper<P, E> withServiceNeeds(final EditMapper<P, E> mapper, final ServiceNeeds services) {
    return new EditMapper<>(mapper, services);
  }

  /** The services this mapper's rows call, or null where they call none. */
  final ServiceNeeds serviceNeeds() {
    return services;
  }

  /** The generated bridge this mapper runs, or null on the runtime path. */
  final BridgeFn<P, E> generated() {
    return generated;
  }

  /**
   * The classes of service this mapper's rows call, through {@link
   * io.github.eschizoid.telescope.mapping.Mapping#service service(...)}: those of its own rows,
   * then those of each edit mapper it converts through, each class once. Empty for a mapper calling
   * none.
   */
  public final List<Class<?>> services() {
    return services == null ? List.of() : services.classes();
  }

  /**
   * A copy of this mapper bound to {@code services}, one value for each class {@link #services()}
   * lists, matched by {@code isInstance}, so a container's proxy binds as the bean it stands for.
   * This mapper is left as it was. In an application on a telescope starter, the container injects
   * the {@code @Bridge} field's mapper already bound; {@code with} is for code with no container
   * and for tests.
   *
   * <pre>{@code
   * final var edits = OrderMappers.EDITS.with(new Catalog(products)); // in a test
   * }</pre>
   *
   * @throws IllegalArgumentException naming what is wrong, for a null, a value no service accepts,
   *     two values for one service, or a service not given
   */
  @SuppressWarnings("unchecked")
  public final EditMapper<P, E> with(final Object... services) {
    if (this.services == null) {
      ServiceNeeds.refuseAny(
        "EditMapper<" + sourceClass().getSimpleName() + ", " + targetClass().getSimpleName() + ">",
        services
      );
      return this;
    }
    return (EditMapper<P, E>) this.services.bind(this, services);
  }

  /**
   * Refuses a call while the mapper's rows call a service and it is bound to none.
   *
   * @throws IllegalStateException naming the first row that calls one
   */
  private void requireBound() {
    if (services != null) services.requireBound(this);
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> The edit mapper the runtime path builds over
   * {@code engine}, the mapper {@link Mapper#ofEdits} built from its rows.
   */
  public static <P, E> EditMapper<P, E> ofRuntime(final Mapper<P, E> engine, final List<OpticNode> explainTrail) {
    return new EditMapper<>(engine, null, null, explainTrail);
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> The edit mapper over {@code bridge}, a bridge
   * generated for an {@code edits(...)} field: every operation is one call into it where the bridge
   * {@linkplain BridgeFn#writesEdits writes edits}, and {@code forward} alone otherwise, the other
   * operations running on the mapper {@code runtime} builds from the same rows.
   */
  public static <P, E> EditMapper<P, E> ofGenerated(
    final BridgeFn<P, E> bridge,
    final Class<P> sourceClass,
    final Class<E> targetClass,
    final Supplier<EditMapper<P, E>> runtime,
    final List<OpticNode> explainTrail
  ) {
    return new EditMapper<>(
      Mapper.ofGeneratedForwardOnly(bridge, sourceClass, targetClass, explainTrail),
      bridge,
      runtime,
      explainTrail
    );
  }

  /**
   * Whether the generated bridge writes {@code target} itself in {@code operation}: it has that
   * write, and {@code target} is of exactly the class it binds each setter on. A subclass, whose
   * own setters can take a value instead, is written by the runtime path, as {@code Mapper.into}
   * writes one.
   */
  private boolean writesItself(final E target, final int operation) {
    return (
      (generated.writesEdits() & operation) != 0 && (target == null || target.getClass() == engine.targetClass())
    );
  }

  /** The runtime path's mapper over the same rows, which a bridge with no in-place writes uses. */
  private EditMapper<P, E> runtime() {
    var known = built;
    if (known == null) {
      known = runtime.get();
      built = known;
    }
    return known;
  }

  /**
   * <b>Module-internal seam — NOT public API.</b> The mapper a {@code via} row naming {@code
   * mapper} converts through, which only another edit mapper accepts.
   */
  public static Mapper<?, ?> engineOf(final EditMapper<?, ?> mapper) {
    return mapper.engine;
  }

  /**
   * Builds a new {@code E} from {@code source}, every property the mapper accounts for written as
   * its row gives it, a null included; a kept property takes the value a new {@code E} is built
   * with, and so does a property the persistence mapping generates, an identifier or a version, at
   * every level, whatever the source sends under its name. Null for a null source.
   */
  public E forward(final P source) {
    requireBound();
    return generated != null ? generated.forward(source) : engine.forward(source);
  }

  /**
   * Writes into {@code target}, in place, every property the mapper accounts for, a null included,
   * which is a PUT; a kept property, and one no edit writes, keeps the value {@code target} holds.
   * Properties are written as {@link Mapper#into} writes them: through setters, a collection
   * refilled in the container the target holds or through the owner's adder, so a managed
   * collection keeps its identity. A target path is written into the objects {@code target} holds
   * along it, building an object only where it holds null.
   *
   * @return {@code target}
   * @throws NullPointerException if {@code target} or {@code source} is null
   * @throws UnsupportedOperationException if {@code target} is a record
   * @throws IllegalArgumentException if {@code target}'s class has no setter for a property the
   *     mapper writes other than a container, or nothing can write a container property
   */
  public E into(final E target, final P source) {
    requireBound();
    if (generated == null) return engine.into(target, source);
    return writesItself(target, BridgeFn.EDIT_INTO)
      ? generated.editInto(target, source)
      : runtime().into(target, source);
  }

  /**
   * Writes into {@code target}, in place, only what {@code partial} sends, which is a PATCH. A
   * property is sent where the value its row reads from the partial is not null, a primitive always
   * being sent, and is written only where the value the row gives is not null, so a {@code
   * toOrElse} writes no default for a property the partial leaves out. A {@code compute} or {@code
   * constant} row runs on every call and writes a value that is not null. A container the partial
   * sends is written whole: an empty list empties the collection, and a null one leaves it alone.
   * Every property is written as {@link #into} writes it.
   *
   * <pre>{@code
   * final Order order = orders.findByNumber(number); // the entity the persistence context manages
   * EDITS.patchInto(order, changes);
   * }</pre>
   *
   * @return {@code target}
   * @throws NullPointerException if {@code target} is null
   * @throws UnsupportedOperationException if {@code target} is a record
   * @throws IllegalArgumentException as {@link #into} throws, for a property the partial sends
   */
  public E patchInto(final E target, final P partial) {
    requireBound();
    if (generated == null) return engine.editPatchInto(target, partial);
    return writesItself(target, BridgeFn.EDIT_PATCH_INTO)
      ? generated.editPatchInto(target, partial)
      : runtime().patchInto(target, partial);
  }

  /**
   * A copy of {@code base} with what {@code partial} sends laid over it, decided as {@link
   * #patchInto} decides it, for a record or another {@code E} that cannot be written in place.
   * {@code base} is left as it was; a property the partial does not send is the one {@code base}
   * holds, handed across as it is. Null for a null base.
   *
   * @throws UnsupportedOperationException if {@code E} is an enum or a class no write strategy can
   *     build
   */
  public E patch(final E base, final P partial) {
    requireBound();
    if (generated == null) return engine.editPatch(base, partial);
    return (generated.writesEdits() & BridgeFn.EDIT_PATCH) != 0
      ? generated.editPatch(base, partial)
      : runtime().patch(base, partial);
  }

  /**
   * What this mapper does, as a queryable {@link OpticReport}: the properties it pairs, the
   * conversions it applies, and the properties it leaves out with the reason, a kept one among
   * them.
   */
  public final OpticReport explain() {
    return new OpticReport(explainTrail);
  }

  /** Runs {@link #forward} on {@code input} and reports what each property became. */
  public final Trace trace(final P input) {
    return MappingTraces.of(input, forward(input), explainTrail);
  }

  /** This mapper's {@link #forward} alone, as a {@link ForwardMapper}. */
  public final ForwardMapper<P, E> toForwardMapper() {
    return ForwardMapper.create(this::forward, engine.sourceClass(), engine.targetClass(), explainTrail);
  }

  /** The class an edit sends. */
  public final Class<P> sourceClass() {
    return engine.sourceClass();
  }

  /** The class an edit writes into. */
  public final Class<E> targetClass() {
    return engine.targetClass();
  }

  /**
   * <b>Codegen-support seam — NOT public API.</b> The conversion of one {@code P} into one {@code
   * E} as a container policy writes an element in place: {@code forward}, then the check and write
   * {@link #into} makes, over one element; null where this mapper writes none in place. Another
   * edit mapper converting the elements of a collection through this one hands it to the container
   * policy.
   *
   * @return the conversion, or null
   */
  public final ContainerCopy.Elements intoElements() {
    requireBound();
    if (generated == null) return engine.intoElements();
    return (generated.writesEdits() & BridgeFn.EDIT_INTO) != 0 ? generated.targetWrite() : runtime().intoElements();
  }
}
