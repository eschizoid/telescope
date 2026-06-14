package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Edit;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
 * <p><b>Permitted impls.</b> Sealed over four package-private records in sibling files in this
 * package — {@link SameTypedTo}, {@link TypedTransformTo}, {@link Via}, {@link Drop}. Users
 * construct via the static factories below; the record types are not public API.
 */
public sealed interface Mapping<A, B>
  extends MapStep
  permits
    SameTypedTo,
    TypedTransformTo,
    ForwardOnlyTransformTo,
    Via,
    Drop,
    TelescopeTo,
    FromTelescopeTo,
    TelescopeToTelescope,
    Constant,
    Compute
{
  /**
   * Source class this row keys against — the declaring class of the source accessor, recovered via
   * {@code SerializedLambda}. May be {@code null} for permits whose source side is a {@code
   * Telescope<A, ?>} (root class isn't recoverable at runtime — generics erased); the engine pins
   * the row to the outer mapper pair instead.
   *
   * <p>Internal accessor exposed so {@code DeepMap} can key overrides by {@code (sourceClass,
   * targetClass)} without a cross-interface cast. Not intended as a user-facing introspection
   * surface — implementations live in package-private records.
   */
  Class<A> sourceClass();

  /**
   * Target class this row keys against — the declaring class of the target accessor. May be {@code
   * null} for permits whose target side is a {@code Telescope<B, ?>}; same outer-pair pinning as
   * for {@link #sourceClass}.
   *
   * <p>Internal accessor — see {@link #sourceClass()}.
   */
  Class<B> targetClass();

  /**
   * Source record component name this row claims — the source accessor's method name recovered via
   * {@code SerializedLambda}. May be {@code null} for permits whose source side is a {@code
   * Telescope<A, ?>}: the path's leaf isn't a top-level source field.
   *
   * <p>Internal accessor — see {@link #sourceClass()}.
   */
  String sourceField();

  /**
   * Target record component name this row claims — the target accessor's method name. May be {@code
   * null} for permits whose target side is a {@code Telescope<B, ?>}: the path's leaf isn't a
   * top-level target field.
   *
   * <p>Internal accessor — see {@link #sourceClass()}.
   */
  String targetField();

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
   * Forward-only typed-transform correspondence: same shape as {@link #to(Accessor, Accessor,
   * Function, Function)} but the backward function is supplied automatically and throws {@link
   * UnsupportedOperationException} at the row level if invoked. Use this for one-shot entity →
   * DB-schema mappings whose backward direction is never called — saves the boilerplate of passing
   * an unused inverse function, and the throw message names the field so the failure is
   * self-diagnosing if a downstream user does call {@link
   * io.github.eschizoid.telescope.conversion.Mapper#backward} on the resulting mapper.
   *
   * <pre>{@code
   * forward(UserEntity::createdAt, UserDto::createdAtIso, Instant::toString)
   * }</pre>
   *
   * <p>Naming chain: {@code Mapping.forward(...)} rows feed the {@link
   * io.github.eschizoid.telescope.Telescope#mapperForward(Class, Class, MapStep...)} factory,
   * producing a {@link io.github.eschizoid.telescope.conversion.ForwardMapper}. The three names
   * rhyme so the forward-only family is self-discoverable at the call site.
   *
   * <p>For the bidirectional variant, supply the inverse explicitly via {@link #to(Accessor,
   * Accessor, Function, Function)}.
   */
  static <A, B, X, Y> Mapping<A, B> forward(
    final Accessor<A, X> src,
    final Accessor<B, Y> tgt,
    final Function<? super X, ? extends Y> fn
  ) {
    return new ForwardOnlyTransformTo<>(src, tgt, fn);
  }

  /**
   * Enum correspondence by constant name. Maps each {@code SE} constant to the {@code TE} constant
   * of the same {@link Enum#name() name}; backward direction is symmetric. Closes MapStruct's
   * {@code @ValueMapping} gap for the common "status enums that line up by name" case without
   * forcing the user to hand-write a 2-arg typed transform with {@code Enum.valueOf} on both sides
   * — the exhaustiveness check that comes free here is the value-add.
   *
   * <pre>{@code
   * enumTo(UserEntity::status, UserDto::status, EntityStatus.class, DtoStatus.class)
   * }</pre>
   *
   * <p><b>Exhaustiveness validation runs at factory time.</b> Every constant of {@code srcEnum}
   * must have a same-named constant in {@code tgtEnum}, and vice versa. Mismatches throw {@link
   * IllegalArgumentException} naming the missing constants so the user sees a clear diff at
   * mapper-build time instead of an {@link IllegalArgumentException} at the first call site that
   * hits the unmatched constant. If your enums genuinely differ in cardinality, use {@link
   * #to(Accessor, Accessor, Function, Function)} with explicit lambdas that handle the mismatch
   * however you want (default, throw, map-to-null).
   *
   * <p><b>Lattice routing:</b> this is a thin convenience over {@link TypedTransformTo} — the
   * forward and backward closures are {@code Enum.valueOf(targetClass, source.name())}. The
   * existing lattice composition rules apply unchanged; codegen recognises the enum-shaped pair at
   * the {@code @Bridge} processor and may in a future revision emit a switch expression for the
   * per-pair dispatch instead of routing through the captured {@link Function}.
   */
  static <A, B, SE extends Enum<SE>, TE extends Enum<TE>> Mapping<A, B> enumTo(
    final Accessor<A, SE> src,
    final Accessor<B, TE> tgt,
    final Class<SE> srcEnum,
    final Class<TE> tgtEnum
  ) {
    validateEnumCorrespondence(srcEnum, tgtEnum);
    return new TypedTransformTo<>(
      src,
      tgt,
      (SE s) -> Enum.valueOf(tgtEnum, s.name()),
      (TE t) -> Enum.valueOf(srcEnum, t.name())
    );
  }

  private static <SE extends Enum<SE>, TE extends Enum<TE>> void validateEnumCorrespondence(
    final Class<SE> srcEnum,
    final Class<TE> tgtEnum
  ) {
    final java.util.Set<String> srcNames = new java.util.TreeSet<>();
    for (final var c : srcEnum.getEnumConstants()) srcNames.add(c.name());
    final java.util.Set<String> tgtNames = new java.util.TreeSet<>();
    for (final var c : tgtEnum.getEnumConstants()) tgtNames.add(c.name());
    final var missingInTarget = new java.util.TreeSet<>(srcNames);
    missingInTarget.removeAll(tgtNames);
    final var missingInSource = new java.util.TreeSet<>(tgtNames);
    missingInSource.removeAll(srcNames);
    if (missingInTarget.isEmpty() && missingInSource.isEmpty()) return;
    final var msg = new StringBuilder("Mapping.enumTo(")
      .append(srcEnum.getSimpleName())
      .append(" ↔ ")
      .append(tgtEnum.getSimpleName())
      .append("): exhaustiveness check failed.");
    if (!missingInTarget.isEmpty()) {
      msg
        .append(" Source constants missing on target: ")
        .append(missingInTarget)
        .append(" (add to ")
        .append(tgtEnum.getSimpleName())
        .append(" or use Mapping.to(src, tgt, fwd, bwd) with explicit fallback handling).");
    }
    if (!missingInSource.isEmpty()) {
      msg
        .append(" Target constants missing on source: ")
        .append(missingInSource)
        .append(" (add to ")
        .append(srcEnum.getSimpleName())
        .append(" or use Mapping.to(src, tgt, fwd, bwd) with explicit fallback handling).");
    }
    throw new IllegalArgumentException(msg.toString());
  }

  /**
   * Null-coalescing same-typed correspondence: when the source accessor returns {@code null}, the
   * target receives {@code defaultValue} instead. Source and target share the same leaf type {@code
   * X}; non-null source values pass through unchanged. Closes MapStruct's {@code defaultValue} gap
   * without the inline {@code n == null ? FALLBACK : n} lambda.
   *
   * <pre>{@code
   * toOrElse(UserEntity::displayName, UserDto::displayName, "(unnamed)")
   * }</pre>
   *
   * <p>Backward direction is identity — the default value, if it lands on the target, round-trips
   * back to the source slot as that same value rather than the original null. Accept the asymmetry
   * when the default is a domain-meaningful sentinel rather than a placeholder; reach for an
   * explicit 4-arg {@link #to(Accessor, Accessor, Function, Function)} when both directions need
   * bespoke logic.
   */
  static <A, B, X> Mapping<A, B> toOrElse(final Accessor<A, X> src, final Accessor<B, X> tgt, final X defaultValue) {
    return new TypedTransformTo<>(src, tgt, x -> x == null ? defaultValue : x, y -> y);
  }

  /**
   * Predicate-gated null-coalescing same-typed correspondence: the target receives {@code
   * defaultValue} when {@code missing.test(srcValue)} returns true. Generalises {@link
   * #toOrElse(Accessor, Accessor, Object)} (which is strict-null) to cover empty-string,
   * empty-collection, zero-numeric, or any custom predicate-equivalent of "missing". MapStruct has
   * no equivalent — {@code defaultValue} is strictly null-checked at the bytecode level.
   *
   * <pre>{@code
   * toOrElse(Src::displayName, Dst::displayName, "(unnamed)", String::isBlank)
   * toOrElse(Src::items,       Dst::items,       List.of(),  List::isEmpty)
   * }</pre>
   *
   * <p>Backward direction is identity; the asymmetry of {@link #toOrElse(Accessor, Accessor,
   * Object)} applies here too. Reach for the explicit 4-arg {@link #to(Accessor, Accessor,
   * Function, Function)} when both directions need bespoke logic.
   */
  static <A, B, X> Mapping<A, B> toOrElse(
    final Accessor<A, X> src,
    final Accessor<B, X> tgt,
    final X defaultValue,
    final Predicate<? super X> missing
  ) {
    Objects.requireNonNull(missing, "Mapping.toOrElse: missing predicate is null");
    // Null-short-circuit BEFORE the predicate fires so predicates like String::isBlank /
    // List::isEmpty don't NPE on a null source value. Matches the 3-arg toOrElse's strict-null
    // semantics for the leading-null case.
    return new TypedTransformTo<>(src, tgt, x -> x == null || missing.test(x) ? defaultValue : x, y -> y);
  }

  /**
   * Lazy null-coalescing same-typed correspondence: like {@link #toOrElse(Accessor, Accessor,
   * Object)} but the fallback comes from a {@link Supplier} so an expensive-to-construct default is
   * only materialized when the source actually is {@code null}. Closes MapStruct's {@code
   * defaultExpression = "java(…)"} gap.
   *
   * <pre>{@code
   * toOrElseGet(UserEntity::createdAt, UserDto::createdAt, Instant::now)
   * }</pre>
   *
   * <p>Same backward-is-identity trade-off as {@link #toOrElse(Accessor, Accessor, Object)} — the
   * supplied value, once it lands on the target, round-trips back to the source slot as itself
   * rather than the original null.
   */
  static <A, B, X> Mapping<A, B> toOrElseGet(
    final Accessor<A, X> src,
    final Accessor<B, X> tgt,
    final Supplier<? extends X> supplier
  ) {
    return new TypedTransformTo<>(src, tgt, x -> x == null ? supplier.get() : x, y -> y);
  }

  /**
   * Predicate-gated lazy null-coalescing same-typed correspondence: {@code supplier.get()} runs
   * when {@code missing.test(srcValue)} returns true. Generalises {@link #toOrElseGet(Accessor,
   * Accessor, Supplier)} the same way the 4-arg {@link #toOrElse(Accessor, Accessor, Object,
   * Predicate)} generalises {@link #toOrElse(Accessor, Accessor, Object)}.
   *
   * <pre>{@code
   * toOrElseGet(Src::traceId, Dst::traceId, UUID::randomUUID, String::isBlank)
   * }</pre>
   */
  static <A, B, X> Mapping<A, B> toOrElseGet(
    final Accessor<A, X> src,
    final Accessor<B, X> tgt,
    final Supplier<? extends X> supplier,
    final Predicate<? super X> missing
  ) {
    Objects.requireNonNull(missing, "Mapping.toOrElseGet: missing predicate is null");
    // Null-short-circuit BEFORE the predicate fires — see the parallel toOrElse overload for the
    // rationale (String::isBlank / List::isEmpty would NPE on null).
    return new TypedTransformTo<>(src, tgt, x -> x == null || missing.test(x) ? supplier.get() : x, y -> y);
  }

  /**
   * Nested-target correspondence: stamp a flat source field through a multi-hop {@link Telescope}
   * on the target side. Closes the gap with MapStruct's {@code @Mapping(source = "flat", target =
   * "a.b.c")} by letting the second argument be a real {@code Telescope<B, X>} built with the same
   * {@code .field(...)} navigation users already know for read/update — the IDE refactor follows
   * each hop and {@code javac} catches a missing accessor everywhere.
   *
   * <pre>{@code
   * // Runtime form — Telescope.of(...).field(...).field(...)
   * Telescope.mapper(Order.class, OrderDto.class,
   *     to(Order::getName,         OrderDto::getFullName),
   *     to(Order::getCustomerName,
   *        Telescope.of(OrderDto.class)
   *            .field(OrderDto::getShipping)
   *            .field(Shipping::getRecipient)
   *            .field(Recipient::getFullName)));
   *
   * // Codegen form — @Focus-generated navigator, same Telescope value, fully typed each hop.
   * // Mapping.to accepts both interchangeably; pick whichever the call site is closest to.
   * Telescope.mapper(Order.class, OrderDto.class,
   *     to(Order::getCustomerName,
   *        OrderDtoTelescope.of().shipping().recipient().fullName()));
   * }</pre>
   *
   * <p>The engine applies this row at the <em>outer</em> {@code (source, target)} pair only — after
   * the base auto-recursion produces a target value, {@code targetTelescope.set(b,
   * srcAcc.apply(a))} overlays the leaf. Backward direction is the mirror: read at the target
   * telescope, write to the source via the accessor's lens.
   *
   * <p><b>Intermediate allocation.</b> When the source is genuinely flat (no same-name field on the
   * source matching the telescope's top-level target hop), the engine synthesizes a recursive
   * default-tree instance of the intermediate's type so the overlay can descend without NPEing on a
   * null hop. Works for record intermediates at arbitrary depth (the type system drives the
   * recursion); bean intermediates without a no-arg ctor or builder still need a {@link
   * #via(Accessor, Accessor, Mapper)} workaround. Primitive defaults follow JLS rules ({@code 0} /
   * {@code false} / {@code 0.0}).
   */
  static <A, B, X> Mapping<A, B> to(final Accessor<A, X> src, final Telescope<B, X> targetTelescope) {
    return new TelescopeTo<>(src, targetTelescope);
  }

  /**
   * Nested-source correspondence: source is a multi-hop {@link Telescope}, target is a flat
   * accessor. Mirror of {@link #to(Accessor, Telescope)} — same fluent navigation on the source
   * side, single accessor on the target. Closes MapStruct's {@code @Mapping(source = "a.b.c",
   * target = "flat")} for that direction.
   *
   * <pre>{@code
   * Telescope.mapper(Order.class, OrderDto.class,
   *     to(Telescope.of(Order.class).field(Order::getCustomer).field(Customer::getEmail),
   *        OrderDto::getRecipientEmail));
   * }</pre>
   *
   * <p>Engine: applied at the outer {@code (source, target)} pair only. Forward: reads at {@code
   * srcTelescope}, writes to {@code tgt} accessor on a rebuilt {@code b}. Backward: reads {@code
   * tgt} on {@code b}, writes through {@code srcTelescope.set(a, value)} on a rebuilt {@code a}.
   */
  static <A, B, X> Mapping<A, B> to(final Telescope<A, X> srcTelescope, final Accessor<B, X> tgt) {
    return new FromTelescopeTo<>(srcTelescope, tgt);
  }

  /**
   * Both-nested correspondence: source and target sides are multi-hop {@link Telescope}s. Closes
   * MapStruct's {@code @Mapping(source = "a.b.c", target = "x.y.z")} for the both-nested case.
   *
   * <pre>{@code
   * Telescope.mapper(Order.class, OrderDto.class,
   *     to(Telescope.of(Order.class).field(Order::getCustomer).field(Customer::getEmail),
   *        Telescope.of(OrderDto.class).field(OrderDto::getShipping).field(Shipping::getEmail)));
   * }</pre>
   *
   * <p>Engine: applied at the outer pair only. Forward overlay: {@code tgt.set(b, src.read(a))}.
   * Backward overlay: {@code src.set(a, tgt.read(b))}.
   *
   * <p><b>Broadcast on many-focus telescopes.</b> When either side carries a many-focus telescope
   * (built with {@code .each(...)} / {@code .eachValue(...)} / {@code .whenPresent(...)}), the
   * lattice's intrinsic {@link Telescope#set} broadcasts the value to every focus on the target
   * side, and {@link Telescope#read} returns the first focus on the source side. For positional N:N
   * — Nth source value at Nth target focus — use {@link #zip(Telescope, Telescope)} instead.
   */
  static <A, B, X> Mapping<A, B> to(final Telescope<A, X> srcTelescope, final Telescope<B, X> targetTelescope) {
    return new TelescopeToTelescope<>(srcTelescope, targetTelescope, TelescopeToTelescope.Kind.BROADCAST);
  }

  /**
   * Positional N:N correspondence between two many-focus telescopes. The Nth source value lands at
   * the Nth target focus; cardinality is enforced at apply time — a mismatch throws rather than
   * silently truncating.
   *
   * <pre>{@code
   * Telescope.mapper(Cart.class, CartDto.class,
   *     zip(Telescope.of(Cart.class).each(Cart::items).field(Item::name),
   *         Telescope.of(CartDto.class).each(CartDto::lines).field(Line::label)));
   * }</pre>
   *
   * <p>Distinct from {@link #to(Telescope, Telescope)}, which broadcasts on the many side. {@code
   * zip} is the explicit positional semantic — the call-site reader knows which write semantic
   * they're getting from the method name.
   *
   * <p>Engine: applied at the outer pair only. Forward fixup uses {@link Telescope#toList} on the
   * source and {@link Telescope#updateIndexed} on the target with a cardinality check; backward
   * fixup is the mirror.
   */
  static <A, B, X> Mapping<A, B> zip(final Telescope<A, X> srcTelescope, final Telescope<B, X> targetTelescope) {
    return new TelescopeToTelescope<>(srcTelescope, targetTelescope, TelescopeToTelescope.Kind.ZIP);
  }

  /**
   * Nested correspondence: map {@code src}'s leaf through a pre-built {@link Mapper}. The mapper
   * supplies both directions; any custom rules it bakes in (typed transforms, nested mappers of its
   * own) survive — the deep recursion uses it as-is at this slot instead of building its own.
   *
   * <p>The mapper can be at <em>element-level</em> or at <em>accessor-level</em>; telescope detects
   * which one the user passed and lifts through {@code List} / {@code Set} / {@code Optional} /
   * {@code Map<K, V>} automatically when the accessor returns a container and the mapper's
   * source/target classes are the element types.
   *
   * <pre>{@code
   * via(UserEntity::address,  UserDto::address,  addressMapper)   // scalar pair, no lift
   * via(TeamEntity::members,  TeamDto::members,  userMapper)      // List pair, auto-lifts
   * via(OrderEntity::giftWrap, OrderDto::giftWrap, addressMapper) // Optional pair, auto-lifts
   * }</pre>
   */
  static <A, B> Mapping<A, B> via(
    final Accessor<A, ?> src,
    final Accessor<B, ?> tgt,
    final Mapper<?, ?> elementMapper
  ) {
    return new Via<>(src, tgt, elementMapper);
  }

  /**
   * Drop a source field — declare it intentionally NOT mapped to the target so the strict deep-map
   * factory accepts the pair without requiring a same-name target property. Use this when one side
   * of a cross-paradigm pair carries fields the other side shouldn't (or doesn't) see — e.g.
   * internal {@code metadata} that mustn't leak to a partner-facing DTO.
   *
   * <pre>{@code
   * Telescope.mapper(
   *     Order.class, PartnerShippingLabel.class,
   *     to(Order::orderNumber, PartnerShippingLabel::getTrackingReference),
   *     via(Order::lineItems,  PartnerShippingLabel::getItems, partnerItemMapper),
   *     drop(Order::metadata));   // PartnerShippingLabel has no metadata field; this is OK
   * }</pre>
   *
   * <p>The dropped field is read-side-only: {@code forward(order)} simply does not propagate it to
   * the target, and {@code backward(label)} reconstructs the record with the field set to whatever
   * the source-side reflection chooses (typically {@code null} for nullable record components, or
   * the type's default value otherwise — see the source-class's component-default contract).
   */
  static <A, B> Mapping<A, B> drop(final Accessor<A, ?> src) {
    return new Drop<>(src, null);
  }

  /**
   * Drop a source field scoped to a specific nested {@code (source, target)} pair. Use this when
   * the field to drop lives on a type the recursion lands on multiple times with different targets
   * — only the (source, {@code target}) pair gets the field elided; other recursions on the same
   * source class stay strict.
   *
   * <pre>{@code
   * Telescope.mapper(
   *     Order.class, PartnerShippingLabel.class,
   *     to(Order::orderNumber,   PartnerShippingLabel::getTrackingReference),
   *     via(Order::lineItems,    PartnerShippingLabel::getItems, partnerItemMapper),
   *     drop(Order::metadata),                       // top-level Order → PartnerShippingLabel
   *     drop(Customer::tags, PartnerCustomer.class)); // nested Customer → PartnerCustomer
   * }</pre>
   *
   * <p>Symmetrical with {@link #via(Accessor, Accessor, Mapper)} carrying both accessors — the
   * difference is that there is no target accessor to recover the target class from, so the user
   * supplies the class directly. Use the single-arg {@link #drop(Accessor)} when the drop scopes to
   * the top-level mapper.
   */
  static <A, B> Mapping<A, B> drop(final Accessor<A, ?> src, final Class<?> target) {
    @SuppressWarnings("unchecked")
    final var castTarget = (Class<B>) target;
    return new Drop<>(src, castTarget);
  }

  /**
   * Stamp a fixed value onto a target field at forward time. Closes MapStruct's
   * {@code @Mapping(target = "tenant", constant = "production")} — the literal lives at the call
   * site, statically typed against the target leaf, no string parser.
   *
   * <pre>{@code
   * Telescope.mapper(Order.class, OrderDto.class,
   *     to(Order::id, OrderDto::id),
   *     constant(OrderDto::tenant,    "production"),
   *     constant(OrderDto::apiVersion, 7));
   * }</pre>
   *
   * <p><b>Forward-only.</b> The source side has no slot for this value, so {@code
   * mapper.backward(dto)} silently drops it — the rebuilt source carries the type default at the
   * dual slot. Same retraction semantics as {@link #drop(Accessor)} on the source side.
   *
   * <p><b>Eager.</b> The literal is captured once at row construction; every forward call stamps
   * the same reference. For values that should be re-evaluated per call (timestamps, fresh
   * containers, IDs) use {@link #compute(Accessor, Supplier)} instead — a literal {@code
   * constant(Tgt::metadata, new HashMap<>())} would share one map across every forward call, which
   * is almost never what you want.
   */
  static <A, B, X> Mapping<A, B> constant(final Accessor<B, X> tgt, final X value) {
    final Class<B> tgtClass = LambdaIntrospection.implClassOf(tgt);
    return new Constant<>(Telescope.of(tgtClass).field(tgt), value);
  }

  /**
   * Stamp a freshly-evaluated value onto a target field at every forward call. Closes MapStruct's
   * {@code @Mapping(target = "createdAt", expression = "java(Instant.now())")} but in plain typed
   * Java — the {@link Supplier} is type-checked against the target leaf by {@code javac}, no
   * string-templated expression body.
   *
   * <pre>{@code
   * Telescope.mapper(Order.class, OrderDto.class,
   *     to(Order::id,       OrderDto::id),
   *     compute(OrderDto::createdAt, Instant::now),     // fresh timestamp per call
   *     compute(OrderDto::traceId,   UUID::randomUUID), // fresh ID per call
   *     compute(OrderDto::metadata,  HashMap::new));    // fresh container per call
   * }</pre>
   *
   * <p><b>Forward-only.</b> Same backward-drop semantics as {@link #constant(Accessor, Object)}.
   * Note that this means {@code mapper.forward(mapper.backward(dto))} does NOT round-trip a {@code
   * compute} row's value — by design: a supplier like {@code Instant::now} cannot be inverted.
   * Document any roundtrip assumption explicitly.
   *
   * <p><b>Lazy.</b> The supplier fires on every forward call, not once at row construction. Use
   * this whenever the value involves mutable state, time, randomness, or a fresh allocation; use
   * {@link #constant(Accessor, Object)} when the value is genuinely a shared literal.
   */
  static <A, B, X> Mapping<A, B> compute(final Accessor<B, X> tgt, final Supplier<? extends X> supplier) {
    final Class<B> tgtClass = LambdaIntrospection.implClassOf(tgt);
    return new Compute<>(Telescope.of(tgtClass).field(tgt), supplier);
  }

  /**
   * Nested-target variant of {@link #constant(Accessor, Object)} — stamp a fixed value at a
   * multi-hop target location via a {@link Telescope}. Closes MapStruct's {@code @Mapping(target =
   * "a.b.c", constant = "...")} for nested targets.
   *
   * <pre>{@code
   * // Runtime form
   * Telescope.mapper(Order.class, OrderDto.class,
   *     to(Order::id, OrderDto::id),
   *     constant(
   *         Telescope.of(OrderDto.class).field(OrderDto::shipping).field(Shipping::country),
   *         "US"));
   *
   * // Codegen form — same value-level Telescope, fully typed each hop
   * Telescope.mapper(Order.class, OrderDto.class,
   *     to(Order::id, OrderDto::id),
   *     constant(OrderDtoTelescope.of().shipping().country(), "US"));
   * }</pre>
   *
   * <p>Forward-only. Intermediate hops without a same-name source counterpart are allocated as a
   * recursive default-tree (records only); see {@link #to(Accessor, Telescope)} for the same
   * allocation behavior on the {@code to(srcAcc, tgtTelescope)} family.
   */
  static <A, B, X> Mapping<A, B> constant(final Telescope<B, X> targetTelescope, final X value) {
    return new Constant<>(targetTelescope, value);
  }

  /**
   * Nested-target variant of {@link #compute(Accessor, Supplier)} — invoke the supplier per forward
   * call and stamp the result at a multi-hop target location via a {@link Telescope}. Closes
   * MapStruct's {@code @Mapping(target = "a.b.c", expression = "java(...)")} for nested targets.
   *
   * <pre>{@code
   * Telescope.mapper(Order.class, OrderDto.class,
   *     to(Order::id, OrderDto::id),
   *     compute(OrderDtoTelescope.of().audit().createdAt(), Instant::now));
   * }</pre>
   *
   * <p>Forward-only; same intermediate-allocation behavior as {@link #constant(Telescope, Object)}.
   */
  static <A, B, X> Mapping<A, B> compute(final Telescope<B, X> targetTelescope, final Supplier<? extends X> supplier) {
    return new Compute<>(targetTelescope, supplier);
  }
}
