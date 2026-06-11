package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.conversion.Mapper;
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
 * <p><b>Permitted impls.</b> Sealed over four package-private records in sibling files in this
 * package — {@link SameTypedTo}, {@link TypedTransformTo}, {@link Via}, {@link Drop}. Users
 * construct via the static factories below; the record types are not public API.
 */
public sealed interface Mapping<A, B> extends MapStep permits SameTypedTo, TypedTransformTo, Via, Drop, TelescopeTo {
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
   * Nested-target correspondence: stamp a flat source field through a multi-hop {@link Telescope}
   * on the target side. Closes the gap with MapStruct's {@code @Mapping(source = "flat", target =
   * "a.b.c")} by letting the second argument be a real {@code Telescope<B, X>} built with the same
   * {@code .field(...)} navigation users already know for read/update — the IDE refactor follows
   * each hop and {@code javac} catches a missing accessor everywhere.
   *
   * <pre>{@code
   * Telescope.mapper(Order.class, OrderDto.class,
   *     to(Order::getName,         OrderDto::getFullName),
   *     to(Order::getCustomerName,
   *        Telescope.of(OrderDto.class)
   *            .field(OrderDto::getShipping)
   *            .field(Shipping::getRecipient)
   *            .field(Recipient::getFullName)));
   * }</pre>
   *
   * <p>The engine applies this row at the <em>outer</em> {@code (source, target)} pair only — after
   * the base auto-recursion produces a target value, {@code targetTelescope.set(b,
   * srcAcc.apply(a))} overlays the leaf. Backward direction is the mirror: read at the target
   * telescope, write to the source via the accessor's lens.
   */
  static <A, B, X> Mapping<A, B> to(final Accessor<A, X> src, final Telescope<B, X> targetTelescope) {
    return new TelescopeTo<>(src, targetTelescope);
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
}
