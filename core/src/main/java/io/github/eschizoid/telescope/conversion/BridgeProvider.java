package io.github.eschizoid.telescope.conversion;

import java.util.List;

/**
 * Service-provider interface for runtime discovery of a {@code @Bridge}-generated converter by its
 * {@code (source, target)} pair. The {@code @Bridge} processor emits one implementation per
 * carrier-form bridge and registers it through {@link java.util.ServiceLoader} ({@code
 * META-INF/services}), so {@link io.github.eschizoid.telescope.Telescope#mapperForward(Class,
 * Class, io.github.eschizoid.telescope.mapping.MapStep...)} can locate the bridge without knowing
 * the package or class name it was emitted under.
 *
 * <p>This closes the gap left by the name-derived sibling probe: a carrier-form {@code @Bridge}
 * lives in the carrier's package (a third module that sees both source and target), which a probe
 * keyed off the source class's package can't reach. Locating by {@code (source, target)} through
 * this SPI is package-agnostic and works across the module path (each generated provider declares
 * itself with {@code provides … with …}) and the class path ({@code META-INF/services}).
 *
 * <p>{@link #bridge()} is typed as {@link Object} because the {@code Telescope<Source, Target>}
 * optic type is the consumer's to know; the runtime factory casts it back. Implementations must
 * expose a public no-argument constructor for {@code ServiceLoader} instantiation.
 */
public interface BridgeProvider {
  /**
   * The resource path, followed by a class's binary name, under which the processor lists the pairs
   * of the class's {@code @Bridge} fields, one {@code source -> target} line each, when it compiled
   * the class without the javac tree API and so compiled none of their rows.
   */
  String UNREAD_ROWS_PREFIX = "META-INF/telescope/unread-bridge-fields/";

  /** The bridge's source type — the {@code A} in {@code Telescope<A, B>}. */
  Class<?> sourceType();

  /** The bridge's target type — the {@code B} in {@code Telescope<A, B>}. */
  Class<?> targetType();

  /** The generated {@code BRIDGE} constant — a {@code Telescope<Source, Target>}, opaque here. */
  Object bridge();

  /**
   * The class whose {@code @Bridge} field declared this bridge, or null for a bridge a
   * {@code @Bridge} on a class declared. A mapper factory called from that class for this pair
   * returns a mapper over the bridge.
   */
  default Class<?> declaringType() {
    return null;
  }

  /**
   * For a bridge a field declared, the simple names of the classes of the rows its mapper call
   * builds, in order; a factory handed other rows refuses the bridge, since it was generated from a
   * different declaration. Empty for a bridge a class declared.
   */
  default List<String> rowKinds() {
    return List.of();
  }

  /**
   * For a bridge a field declared, the field's name, as {@code EDITS}; null for a bridge a class
   * declared. With {@link #declaringType()} it names the field in a refusal, and it names the
   * container bean a starter registers for a field whose rows call a service.
   */
  default String fieldName() {
    return null;
  }

  /**
   * For a bridge a field declared, the field's raw type, {@code Mapper}, {@code ForwardMapper} or
   * {@code EditMapper}, whose type arguments are {@link #sourceType()} and {@link #targetType()};
   * null for a bridge a class declared.
   */
  default Class<?> fieldType() {
    return null;
  }

  /**
   * For a bridge a field declared, the classes of service its rows call, their own and those of the
   * mappers they convert through, each once, in the order the field's mapper lists them in {@code
   * services()}. Read without initialising the declaring class, so a container integration can
   * decide at build time which fields it binds. Empty where the rows call none.
   */
  default List<Class<?>> services() {
    return List.of();
  }

  /**
   * For a bridge a field declared, the mapper the field holds, read from the field directly, which
   * initialises the declaring class; null for a bridge a class declared. A container integration
   * binds it to the container's beans.
   */
  default Object field() {
    return null;
  }

  /**
   * For a bridge a field declared, the accessor methods each row of its mapper call names, as
   * {@code source>target} with an empty side where a row names none, in order. Empty for a bridge a
   * class declared.
   */
  default List<String> rowAccessors() {
    return List.of();
  }

  /**
   * For a bridge a field declared, the target's properties, which {@code Mapper.into} writes. Empty
   * for a bridge a class declared.
   */
  default List<String> targetProperties() {
    return List.of();
  }

  /**
   * For a bridge a field declared, whether the bridge updates a target in place itself, which the
   * field's mapper's {@code Mapper.into} then calls: true where the target is a bean with a setter
   * for every property the bridge writes. False for a bridge a class declared, and where {@code
   * into} writes through the setters it looks up at run time instead.
   */
  default boolean writesInto() {
    return false;
  }

  /**
   * Codegen-support seam: the mapper a {@code Telescope.mapper} call for this bridge's pair
   * returns, given {@code built}, the mapper over this bridge, and {@code grant}, which {@link
   * BridgeRegistry#specialise(BridgeProvider, Mapper)} hands only to a registered provider. For a
   * bridge a field declared, an instance of the class generated for the field, whose {@code
   * forward}, {@code backward} and {@code patch}, and {@code into} where {@link #writesInto} says
   * so, call the bridge directly; {@code built} itself otherwise.
   */
  default Mapper<?, ?> mapper(final Mapper<?, ?> built, final BridgeGrant grant) {
    return built;
  }

  /** As {@link #mapper}, for a {@code Telescope.mapperForward} call and its forward mapper. */
  default ForwardMapper<?, ?> forwardMapper(final ForwardMapper<?, ?> built, final BridgeGrant grant) {
    return built;
  }

  /** As {@link #mapper}, for a {@code Telescope.edits} call and its edit mapper. */
  default EditMapper<?, ?> editMapper(final EditMapper<?, ?> built, final BridgeGrant grant) {
    return built;
  }
}
