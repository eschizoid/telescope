package io.github.eschizoid.telescope.jackson;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import tools.jackson.core.Version;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.deser.Deserializers;
import tools.jackson.databind.deser.std.StdConvertingDeserializer;
import tools.jackson.databind.ser.Serializers;
import tools.jackson.databind.ser.std.StdConvertingSerializer;
import tools.jackson.databind.type.TypeFactory;
import tools.jackson.databind.util.Converter;

/**
 * A Jackson 3 module that reads and writes each domain type it is given through the wire type a
 * {@link Mapper} converts it from. For a {@code Mapper<Wire, Domain>}, reading a {@code Domain}
 * reads a {@code Wire} and calls {@code forward}, and writing one calls {@code backward} and writes
 * the {@code Wire}:
 *
 * <pre>{@code
 * JsonMapper json = JsonMapper.builder()
 *     .addModule(TelescopeModule.of(OrderMappers.WIRE))
 *     .build();
 * Order order = json.readValue(body, Order.class);   // OrderWire → Order
 * String out = json.writeValueAsString(order);       // Order → OrderWire
 * }</pre>
 *
 * <p>The module carries the mappers it is given and no others; it never discovers them. Each
 * direction is Jackson's own converting deserializer or serializer, so the wire type is read and
 * written with everything Jackson knows about it: its annotations, polymorphic type handling, the
 * {@code @JsonFormat} and view of the property holding it, and {@code @JsonInclude(NON_EMPTY)},
 * which converts the value and asks the wire serializer. A domain type is mapped at every depth,
 * inside a list, a map or another wire type, and a value whose class extends a registered domain
 * type, such as a persistence proxy, is written through that registration.
 *
 * <p>Jackson answers a JSON {@code null} without calling a deserializer, so a mapper never sees one;
 * how a {@code null} reads is the wire type's configuration.
 *
 * <p>Not supported: a domain type as a {@code Map} key, which needs a key deserializer the module
 * does not provide, and {@code @JsonUnwrapped} on a domain-typed property. {@code
 * convertValue(value, Domain.class)} goes through the module too, so it expects the wire shape.
 */
public final class TelescopeModule extends JacksonModule {

  private static final TypeFactory TYPES = TypeFactory.createDefaultInstance();

  private final List<Mapper<?, ?>> mappers;
  private final Map<Class<?>, Mapper<?, ?>> byDomain;

  /**
   * Jackson registers a module once per registration id. Every instance has its own, so a second
   * module registered on the same mapper builder is installed rather than dropped.
   */
  private final Object registrationId = new Object();

  private TelescopeModule(final List<Mapper<?, ?>> mappers) {
    this.mappers = List.copyOf(mappers);
    this.byDomain = refuseMisfits(this.mappers);
  }

  /**
   * A module carrying {@code mappers}, each a {@code Mapper<Wire, Domain>}.
   *
   * @param mappers the mappers, each between a wire type and the domain type it reads and writes
   * @return the module
   * @throws IllegalStateException for a mapper the module cannot install without changing what
   *     Jackson does with other types, or without being passed over by Jackson; the message names
   *     the mapper and why
   */
  public static TelescopeModule of(final Mapper<?, ?>... mappers) {
    Objects.requireNonNull(mappers, "mappers");
    return builder().addAll(List.of(mappers)).build();
  }

  /**
   * A builder, for a module whose mappers are collected in more than one place.
   *
   * @return an empty builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The mappers the module carries, in the order they were given.
   *
   * @return the mappers; unmodifiable
   */
  public List<Mapper<?, ?>> mappers() {
    return mappers;
  }

  @Override
  public String getModuleName() {
    return "telescope";
  }

  @Override
  public Version version() {
    return Version.unknownVersion();
  }

  @Override
  public Object getRegistrationId() {
    return registrationId;
  }

  @Override
  public void setupModule(final SetupContext context) {
    context.addDeserializers(new MappedReads());
    context.addSerializers(new MappedWrites());
  }

  @Override
  public String toString() {
    return "TelescopeModule" + byDomain.keySet().stream().map(Class::getSimpleName).toList();
  }

  /** Collects mappers for a {@link TelescopeModule}. */
  public static final class Builder {

    private final List<Mapper<?, ?>> mappers = new ArrayList<>();

    private Builder() {}

    /**
     * Adds a {@code Mapper<Wire, Domain>}.
     *
     * @param mapper the mapper
     * @return this builder
     */
    public Builder add(final Mapper<?, ?> mapper) {
      mappers.add(Objects.requireNonNull(mapper, "mapper"));
      return this;
    }

    /**
     * Adds each of {@code mappers}, in order.
     *
     * @param mappers the mappers
     * @return this builder
     */
    public Builder addAll(final Collection<? extends Mapper<?, ?>> mappers) {
      for (final var mapper : Objects.requireNonNull(mappers, "mappers")) {
        add(mapper);
      }
      return this;
    }

    /**
     * The module.
     *
     * @return a module carrying the mappers added so far
     * @throws IllegalStateException as {@link TelescopeModule#of} does
     */
    public TelescopeModule build() {
      return new TelescopeModule(mappers);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Registration checks
  // ---------------------------------------------------------------------------------------------

  /**
   * The mappers by domain type, or a refusal of the first that would misbehave once installed. Each
   * check refuses a shape Jackson would otherwise get wrong without a word.
   */
  private static Map<Class<?>, Mapper<?, ?>> refuseMisfits(final List<Mapper<?, ?>> mappers) {
    final var byDomain = new LinkedHashMap<Class<?>, Mapper<?, ?>>();
    for (final var mapper : mappers) {
      final var wire = mapper.sourceClass();
      final var domain = mapper.targetClass();
      final var pair = describe(mapper);
      if (isContainer(wire) || isContainer(domain)) {
        throw new IllegalStateException(
          "TelescopeModule: " +
            pair +
            " converts a container, which a lifted mapper (liftList() and its siblings) does. Installed, it" +
            " would read and write every " +
            (isContainer(domain) ? domain : wire).getSimpleName() +
            " the JSON mapper meets. Register the element mapper instead; a List<Domain> property reads" +
            " its elements through it."
        );
      }
      if (wire == domain) {
        throw new IllegalStateException(
          "TelescopeModule: " +
            pair +
            " reads and writes one type, so reading its wire type would find the module again and never" +
            " return."
        );
      }
      final var shape = TYPES.constructType(domain);
      if (shape.isEnumType() || shape.isPrimitive()) {
        throw new IllegalStateException(
          "TelescopeModule: " +
            pair +
            " has " +
            (shape.isEnumType() ? "an enum" : "a primitive") +
            " for its domain type, which Jackson reads and writes through a lookup the module takes no" +
            " part in, so the mapper would be passed over. Map the type that holds it instead."
        );
      }
      final var annotation = jacksonAnnotation(domain);
      if (annotation != null) {
        throw new IllegalStateException(
          "TelescopeModule: " +
            pair +
            " has a domain type that carries " +
            annotation +
            ", which says how Jackson reads or writes it. Jackson settles a module against each such" +
            " annotation differently, so one of the two would be dropped without a word. Remove the" +
            " annotation, or leave this mapper out of the module."
        );
      }
      final var earlier = byDomain.put(domain, mapper);
      if (earlier != null) {
        throw new IllegalStateException(
          "TelescopeModule: " +
            describe(earlier) +
            " and " +
            pair +
            " both have " +
            domain.getSimpleName() +
            " for their domain type, and one JSON mapper reads a type one way. Give each its own" +
            " JsonMapper."
        );
      }
    }
    for (final var mapper : mappers) {
      final var wire = mapper.sourceClass();
      for (final var other : mappers) {
        final var domain = other.targetClass();
        if (domain.isAssignableFrom(wire)) {
          throw new IllegalStateException(
            "TelescopeModule: the wire type of " +
              describe(mapper) +
              " is " +
              (domain == wire ? domain.getSimpleName() : PairingMessages.withArticle(domain.getSimpleName())) +
              ", the domain type of " +
              describe(other) +
              ", so writing it would run that mapper too, and reading it would chain the two. Register" +
              " one of them, or give each its own JsonMapper."
          );
        }
      }
    }
    return byDomain;
  }

  private static boolean isContainer(final Class<?> type) {
    final var shape = TYPES.constructType(type);
    return shape.isContainerType() || shape.isReferenceType();
  }

  private static String describe(final Mapper<?, ?> mapper) {
    return "the mapper " + mapper.sourceClass().getSimpleName() + " → " + mapper.targetClass().getSimpleName();
  }

  /** The class-level and member annotations that settle how Jackson reads or writes a type. */
  private static final List<Class<? extends Annotation>> CLASS_LEVEL = List.of(
    JsonDeserialize.class,
    JsonSerialize.class,
    JsonFormat.class
  );

  private static final List<Class<? extends Annotation>> MEMBER_LEVEL = List.of(JsonCreator.class, JsonValue.class);

  /**
   * The first annotation on {@code type}, or on one of its constructors, methods or fields, that
   * settles how Jackson reads or writes it, or null.
   */
  private static String jacksonAnnotation(final Class<?> type) {
    for (final var annotation : CLASS_LEVEL) {
      if (type.isAnnotationPresent(annotation)) {
        return "@" + annotation.getSimpleName();
      }
    }
    final var members = Stream.of(type.getDeclaredConstructors(), type.getDeclaredMethods(), type.getDeclaredFields())
      .flatMap(Stream::<AnnotatedElement>of)
      .toList();
    for (final var member : members) {
      for (final var annotation : MEMBER_LEVEL) {
        if (member.isAnnotationPresent(annotation)) {
          return "@" + annotation.getSimpleName() + " on " + member;
        }
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // Installed handlers
  // ---------------------------------------------------------------------------------------------

  /**
   * The registration a value of {@code type} is written through: its own class's, else the nearest
   * superclass's, else the first interface's found breadth first.
   */
  private Mapper<?, ?> writtenThrough(final Class<?> type) {
    for (var c = type; c != null; c = c.getSuperclass()) {
      final var mapper = byDomain.get(c);
      if (mapper != null) {
        return mapper;
      }
    }
    final var seen = new HashSet<Class<?>>();
    final var queue = new ArrayDeque<Class<?>>();
    for (var c = type; c != null; c = c.getSuperclass()) {
      queue.addAll(List.of(c.getInterfaces()));
    }
    while (!queue.isEmpty()) {
      final var next = queue.removeFirst();
      if (!seen.add(next)) {
        continue;
      }
      final var mapper = byDomain.get(next);
      if (mapper != null) {
        return mapper;
      }
      queue.addAll(List.of(next.getInterfaces()));
    }
    return null;
  }

  private final class MappedReads extends Deserializers.Base {

    @Override
    public ValueDeserializer<?> findBeanDeserializer(
      final JavaType type,
      final DeserializationConfig config,
      final BeanDescription.Supplier description
    ) {
      final var mapper = byDomain.get(type.getRawClass());
      return mapper == null ? null : reading(mapper);
    }

    @Override
    public boolean hasDeserializerFor(final DeserializationConfig config, final Class<?> valueType) {
      return byDomain.containsKey(valueType);
    }
  }

  private final class MappedWrites extends Serializers.Base {

    @Override
    public ValueSerializer<?> findSerializer(
      final SerializationConfig config,
      final JavaType type,
      final BeanDescription.Supplier description,
      final JsonFormat.Value format
    ) {
      final var mapper = writtenThrough(type.getRawClass());
      return mapper == null ? null : writing(mapper);
    }
  }

  private static <W, D> ValueDeserializer<D> reading(final Mapper<W, D> mapper) {
    return new StdConvertingDeserializer<>(new Conversion<>(mapper.sourceClass(), mapper.targetClass(), mapper::forward));
  }

  private static <W, D> ValueSerializer<?> writing(final Mapper<W, D> mapper) {
    return new StdConvertingSerializer(new Conversion<>(mapper.targetClass(), mapper.sourceClass(), mapper::backward));
  }

  /**
   * One direction of a mapper as a Jackson converter. Its types are the mapper's classes, so
   * Jackson finds the handler for the other side by them rather than by reading type arguments
   * that erasure has removed.
   */
  private record Conversion<I, O>(Class<I> in, Class<O> out, Function<I, O> convert) implements Converter<I, O> {
    @Override
    public O convert(final DeserializationContext context, final I value) {
      return convert.apply(value);
    }

    @Override
    public O convert(final SerializationContext context, final I value) {
      return convert.apply(value);
    }

    @Override
    public JavaType getInputType(final TypeFactory types) {
      return types.constructType(in);
    }

    @Override
    public JavaType getOutputType(final TypeFactory types) {
      return types.constructType(out);
    }
  }
}
