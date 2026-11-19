package io.github.eschizoid.telescope.jackson;

import io.github.eschizoid.telescope.conversion.FromMapProvider;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.NullDefaults;
import io.github.eschizoid.telescope.internal.Records;
import io.github.eschizoid.telescope.internal.pairing.JsonPropertyKeys;
import io.github.eschizoid.telescope.internal.pairing.MapValueTypes;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import io.github.eschizoid.telescope.internal.pairing.Scalar;
import io.github.eschizoid.telescope.mapping.Extract;
import io.github.eschizoid.telescope.mapping.MapExtractStep;
import io.github.eschizoid.telescope.mapping.Require;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.core.JsonParser;

/**
 * Builds the frames a {@link JsonBinder} reads with, once, when the binder is built. Every property
 * of every type the target holds is resolved here to the key it reads and the {@link Read} for its
 * declared type, so reading a document decides nothing by type.
 *
 * <p>Which kind of reading a declared type gets is decided by {@link MapValueTypes#classify}, the
 * rule {@code fromMap} and the {@code @FromMap} binder use, so the three readers accept the same
 * types. A record or a bean the binder can build is read as a nested object by its own frame. A
 * value a JSON token carries is converted by {@link MapValueConversions}, the conversions the two
 * map readers apply, so an integer past an integral type's range, a character that is not one, a
 * locale tag that is not well formed or a JSON key that is no value of the map's key type is
 * refused by the same rule. What JSON adds is its kinds: a value is taken only from the JSON kind
 * its type is written as. A number is never read from text, text never from a number, and an
 * integral property never from a fraction token.
 *
 * <p>The token loop is boundary machinery at the same floor as the record and bean assembly it ends
 * in: a streaming parser has no reversible, lawful decomposition into optics, so this engine builds
 * values and does not compose them.
 */
final class Plan {

  private static final Map<String, Scalar> SCALARS = Arrays.stream(Scalar.values())
    .filter(scalar -> scalar.primitive() != null)
    .collect(Collectors.toUnmodifiableMap(Scalar::primitive, scalar -> scalar));

  private final List<MapExtractStep> rows;
  private final Set<MapExtractStep> used = Collections.newSetFromMap(new IdentityHashMap<>());
  private final Map<Class<?>, Frame<?>> frames = new HashMap<>();

  private Plan(final List<MapExtractStep> rows) {
    this.rows = rows;
  }

  /**
   * The frame for {@code target} and every type it holds, or a refusal of the first type, row or
   * property that cannot be read.
   *
   * @throws IllegalArgumentException naming what cannot be read and why
   */
  static <T> Frame<T> build(final Class<T> target, final MapExtractStep... rows) {
    for (final var row : rows) {
      if (!(row instanceof Extract<?, ?>) && !(row instanceof Require<?, ?>)) {
        throw new IllegalArgumentException(
          "TelescopeJson.fromJson rows must be built via MapExtractStep.extract(...) or MapExtractStep.required(...)"
        );
      }
    }
    if (!bindable(target)) throw new IllegalArgumentException(PairingMessages.jsonNotBindable(target.getName()));
    final var plan = new Plan(List.of(rows));
    final var frame = plan.frame(target);
    for (final var row : rows) {
      if (!plan.used.contains(row)) {
        throw new IllegalArgumentException(
          PairingMessages.jsonRowNeverReached(
            nameOf(row),
            LambdaIntrospection.implClassOf(row.targetAccessor()).getSimpleName(),
            target.getSimpleName()
          )
        );
      }
    }
    return frame;
  }

  /**
   * Whether the binder can build {@code raw} from a JSON object: a record, or a concrete class with
   * properties, outside the JDK and not a container.
   */
  static boolean bindable(final Class<?> raw) {
    if (raw == null || raw.isPrimitive() || raw.isArray() || raw.isEnum() || raw.isInterface()) return false;
    if (Modifier.isAbstract(raw.getModifiers()) || MapValueTypes.isJdk(raw.getName())) return false;
    if (Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)) return false;
    return raw.isRecord() || Beans.propertyNames(raw).length > 0;
  }

  /** The frame for {@code type}, registered before it is filled so a type can hold itself. */
  @SuppressWarnings("unchecked")
  private <T> Frame<T> frame(final Class<T> type) {
    final var existing = frames.get(type);
    if (existing != null) return (Frame<T>) existing;
    final var frame = new Frame<>(type);
    frames.put(type, frame);
    fill(frame);
    return frame;
  }

  private <T> void fill(final Frame<T> frame) {
    final var type = frame.type();
    final var record = type.isRecord();
    final var kind = record ? "component" : "property";
    final String[] names;
    final Type[] types;
    final Beans.BeanWriter<T> writer;
    final List<List<AnnotatedElement>> writes;
    final var readers = new ArrayList<AnnotatedElement>();
    if (record) {
      final var components = type.getRecordComponents();
      names = new String[components.length];
      types = new Type[components.length];
      for (var i = 0; i < components.length; i++) {
        names[i] = components[i].getName();
        types[i] = components[i].getGenericType();
        readers.add(components[i].getAccessor());
      }
      writes = JacksonAnnotations.recordWrites(type);
      writer = null;
    } else {
      writer = Beans.autoWriter(type);
      names = Beans.propertyNames(type);
      types = new Type[names.length];
      writes = new ArrayList<>();
      for (var i = 0; i < names.length; i++) {
        final var ambiguity = Beans.setterAmbiguity(writer, type, names[i]);
        if (ambiguity != null) throw new IllegalArgumentException("TelescopeJson.fromJson: " + ambiguity);
        types[i] = Beans.conversionType(writer, type, names[i]);
        writes.add(JacksonAnnotations.beanWrites(type, names[i]));
        readers.add(Beans.propertyGetter(type, names[i]));
      }
    }
    final var n = names.length;
    final var members = LinkedHashMap.<String, List<AnnotatedElement>>newLinkedHashMap(n);
    for (var i = 0; i < n; i++) {
      final var declaring = new ArrayList<>(writes.get(i));
      declaring.add(readers.get(i));
      members.put(names[i], declaring);
    }
    JacksonAnnotations.refuseUnsupported(type, kind, members);
    final var byName = rowsFor(type, List.of(names));
    if (writer != null) refuseUnwritableRows(type, writer, byName);

    // A row decides its property's key on its own; the annotations decide every other one.
    final var annotated = new JsonPropertyKeys.Keys[n];
    final var keys = new String[n];
    var readsOwnName = false;
    for (var i = 0; i < n; i++) {
      final var row = byName.get(names[i]);
      if (row == null) {
        annotated[i] = JacksonAnnotations.property(type, kind, names[i], writes.get(i), readers.get(i), record);
        keys[i] = annotated[i].key();
      } else {
        keys[i] = row.key();
      }
      readsOwnName |= keys[i].equals(names[i]) && (annotated[i] == null || !annotated[i].ignored());
    }
    final var declaredRequired = readsOwnName ? declaredRequired(type) : List.<String>of();

    final var declared = new String[n];
    final var reads = new Read[n];
    final var absents = new Object[n];
    final var required = new boolean[n];
    final var ignored = new boolean[n];
    final var indexByKey = HashMap.<String, Integer>newHashMap(n);
    for (var i = 0; i < n; i++) {
      final var name = names[i];
      final var declaredType = types[i];
      declared[i] = typeName(declaredType);
      absents[i] = absentOf(declaredType);
      final var ownKeyRequired = keys[i].equals(name) && declaredRequired.contains(name);
      final var row = byName.get(name);
      if (row != null) {
        reads[i] = rowRead(row);
        required[i] = row instanceof Require<?, ?> || ownKeyRequired;
      } else if (annotated[i].ignored()) {
        ignored[i] = true;
        continue;
      } else {
        required[i] = annotated[i].required() || ownKeyRequired;
        // A bean property its writer cannot set is never read, so its type is never resolved and
        // nothing about its value can refuse a document. A requirement on its key still holds.
        final var written = writer == null || writer.writes(name) || Beans.writtenOnlyConverted(writer, type, name);
        if (written) {
          final var declaredName = declared[i];
          reads[i] = resolve(declaredType).orRefuse(reason ->
            new IllegalArgumentException(
              PairingMessages.jsonUnbindable(kind, name, type.getSimpleName(), declaredName, reason)
            )
          );
        } else if (!required[i]) {
          continue;
        }
        for (final var alias : annotated[i].aliases()) claim(indexByKey, alias, i, names, type);
      }
      claim(indexByKey, keys[i], i, names, type);
    }
    final Function<Object[], T> construct =
      writer == null ? args -> Records.construct(type, args) : beanConstruct(type, writer, names, reads);
    frame.complete(kind, names, keys, declared, reads, absents, required, ignored, indexByKey, construct);
  }

  /**
   * Gives {@code key} to the property at {@code index}, refusing it when another property of the
   * same object reads it already: a streaming binder reads each value once.
   */
  private static void claim(
    final Map<String, Integer> indexByKey,
    final String key,
    final int index,
    final String[] names,
    final Class<?> type
  ) {
    final var previous = indexByKey.putIfAbsent(key, index);
    if (previous != null && previous != index) {
      throw new IllegalArgumentException(
        PairingMessages.jsonKeyReadTwice(key, names[previous], names[index], type.getSimpleName())
      );
    }
  }

  /** The rows that name a property of {@code type}, by the property they name. */
  private Map<String, MapExtractStep> rowsFor(final Class<?> type, final List<String> known) {
    final var byName = new LinkedHashMap<String, MapExtractStep>();
    for (final var row : rows) {
      if (!LambdaIntrospection.implClassOf(row.targetAccessor()).isAssignableFrom(type)) continue;
      final var name = nameOf(row);
      if (!known.contains(name)) {
        throw new IllegalArgumentException(PairingMessages.jsonRowOffTarget(name, type.getSimpleName(), known));
      }
      if (byName.put(name, row) != null) {
        throw new IllegalArgumentException(PairingMessages.jsonDuplicateRow(name, type.getSimpleName()));
      }
      used.add(row);
    }
    return byName;
  }

  private static String nameOf(final MapExtractStep row) {
    return PropertyNames.property(LambdaIntrospection.methodNameOf(row.targetAccessor()));
  }

  /**
   * A row's reading: the value as the document writes it, handed to the row's converter. An object
   * arrives as a {@code Map<String, Object>}, an array as a {@code List<Object>}, an integer as the
   * smallest of {@code Integer}, {@code Long} and {@code BigInteger} that holds it, and a fraction
   * as a {@code BigDecimal}, so no digit is lost before the converter sees it.
   */
  @SuppressWarnings("unchecked")
  private static Read rowRead(final MapExtractStep row) {
    final var converter = (Function<Object, Object>) row.converter();
    return parser -> converter.apply(natural(parser));
  }

  /**
   * Refuses a row naming a bean property the bean's writer has no way to set, as {@code fromMap}
   * refuses it: the value read for it would be dropped.
   */
  private static void refuseUnwritableRows(
    final Class<?> type,
    final Beans.BeanWriter<?> writer,
    final Map<String, MapExtractStep> byName
  ) {
    for (final var row : byName.entrySet()) {
      final var refusal = Beans.setterRefusal(writer, type, row.getKey());
      if (refusal != null) throw new IllegalArgumentException("TelescopeJson.fromJson: " + refusal);
      if (!writer.writes(row.getKey())) {
        throw new IllegalArgumentException(
          "TelescopeJson.fromJson: a row names property '" +
            row.getKey() +
            "' of " +
            type.getSimpleName() +
            ", which has no setter, builder method or constructor parameter to write it, so the value" +
            " read for key \"" +
            row.getValue().key() +
            "\" would be dropped. Remove the row, or give " +
            type.getSimpleName() +
            " a way to write '" +
            row.getKey() +
            "'."
        );
      }
    }
  }

  /**
   * A bean is built by its writer from the values by name. A property written only through a setter
   * its getter's type cannot reach is left out of the writer's build and set once the bean exists.
   */
  private static <T> Function<Object[], T> beanConstruct(
    final Class<T> type,
    final Beans.BeanWriter<T> writer,
    final String[] names,
    final Read[] reads
  ) {
    final var indexByName = HashMap.<String, Integer>newHashMap(names.length);
    final var afterBuild = new ArrayList<Integer>();
    final var convertedWriters = new ArrayList<BiConsumer<Object, Object>>();
    for (var i = 0; i < names.length; i++) {
      indexByName.put(names[i], i);
      if (reads[i] != null && Beans.writtenOnlyConverted(writer, type, names[i])) {
        afterBuild.add(i);
        convertedWriters.add(Beans.convertedWriter(type, names[i]));
      }
    }
    return args -> {
      final var bean = writer.construct(names, name -> {
        final var i = indexByName.get(name);
        return i == null ? null : args[i];
      });
      for (var j = 0; j < afterBuild.size(); j++) convertedWriters.get(j).accept(bean, args[afterBuild.get(j)]);
      return bean;
    };
  }

  /**
   * The keys the target's generated {@code @FromMap} binder declares required, so a JSON document
   * is held to the keys an untyped map is held to. Empty for a type with no registered binder.
   */
  private static List<String> declaredRequired(final Class<?> type) {
    final var loader = type.getClassLoader() != null ? type.getClassLoader() : Plan.class.getClassLoader();
    final var providers = ServiceLoader.load(FromMapProvider.class, loader).iterator();
    while (true) {
      final FromMapProvider provider;
      try {
        if (!providers.hasNext()) return List.of();
        provider = providers.next();
      } catch (final ServiceConfigurationError e) {
        continue;
      }
      if (provider.targetType() != type) continue;
      try {
        return provider.required();
      } catch (final UnsupportedOperationException e) {
        throw new IllegalArgumentException(
          "TelescopeJson.fromJson: " +
            type.getSimpleName() +
            " has a registered @FromMap binder, but " +
            e.getMessage(),
          e
        );
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Declared type → reading
  // ---------------------------------------------------------------------------------------------

  /** A reading, or the reason the declared type has none. */
  private record Resolution(Read read, String reason) {
    static Resolution reads(final Read read) {
      return new Resolution(read, null);
    }

    static Resolution refused(final String reason) {
      return new Resolution(null, reason);
    }

    Read orRefuse(final Function<String, RuntimeException> refusal) {
      if (read == null) throw refusal.apply(reason);
      return read;
    }
  }

  private static final MapValueTypes.TypeModel<Type> MODEL = new MapValueTypes.TypeModel<>() {
    @Override
    public String primitiveName(final Type type) {
      return type instanceof Class<?> c && c.isPrimitive() ? c.getName() : null;
    }

    @Override
    public String declaredName(final Type type) {
      final var raw = rawOf(type);
      return raw == null ? null : raw.getName();
    }

    @Override
    public List<Type> typeArguments(final Type type) {
      return type instanceof ParameterizedType p ? List.of(p.getActualTypeArguments()) : List.of();
    }

    @Override
    public boolean isEnum(final Type type) {
      return rawOf(type).isEnum();
    }

    /**
     * Every record and bean the binder can build is read as a nested object by its own frame.
     */
    @Override
    public boolean hasGeneratedBinder(final Type type) {
      return bindable(rawOf(type));
    }

    @Override
    public boolean isCollectionOrMap(final Type type) {
      final var raw = rawOf(type);
      return Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw);
    }
  };

  private Resolution resolve(final Type type) {
    final var classified = MapValueTypes.classify(type, MODEL);
    final var raw = rawOf(type);
    return switch (classified.kind()) {
      case SCALAR -> Resolution.reads(scalar(classified.scalar(), classified.primitive()));
      case ENUM -> Resolution.reads(enumRead(raw));
      case NESTED -> Resolution.reads(nested(frame(raw)));
      case LIST -> container(argument(type, 0), element -> list(element, absentOf(argument(type, 0))));
      case SET -> container(argument(type, 0), element -> set(element, absentOf(argument(type, 0))));
      case OPTIONAL -> container(argument(type, 0), Plan::optional);
      case MAP -> map(argument(type, 0), argument(type, 1));
      case CAST -> Resolution.reads(raw == String.class ? JsonReads::readText : JsonReads::readCharSequence);
      case AS_IS -> Resolution.reads(JsonReads::readNatural);
      case STRING_BUILT -> Resolution.reads(parser -> JsonReads.readValue(parser, raw));
      case COLLECTION_SUBTYPE, UNKNOWN_JDK, NO_BINDER, UNSUPPORTED -> Resolution.refused(
        PairingMessages.jsonNoReading(classified.kind(), raw == null ? null : raw.getName(), type.getTypeName())
      );
    };
  }

  private Resolution container(final Type element, final Function<Read, Read> lift) {
    final var resolved = resolve(element);
    return resolved.read() == null ? resolved : Resolution.reads(lift.apply(resolved.read()));
  }

  /**
   * A primitive or its wrapper, read only from the JSON kind it is written as, by the reading the
   * generated binder calls too.
   */
  private static Read scalar(final String primitive, final boolean unboxed) {
    return switch (primitive) {
      case "int" -> parser -> JsonReads.readInt(parser, unboxed);
      case "long" -> parser -> JsonReads.readLong(parser, unboxed);
      case "short" -> parser -> JsonReads.readShort(parser, unboxed);
      case "byte" -> parser -> JsonReads.readByte(parser, unboxed);
      case "double" -> parser -> JsonReads.readDouble(parser, unboxed);
      case "float" -> parser -> JsonReads.readFloat(parser, unboxed);
      case "boolean" -> parser -> JsonReads.readBoolean(parser, unboxed);
      case "char" -> parser -> JsonReads.readChar(parser, unboxed);
      default -> throw new IllegalStateException("not a primitive: " + primitive);
    };
  }

  /** An enum constant, named by its exact name in a JSON string. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Read enumRead(final Class<?> type) {
    JacksonAnnotations.refuseRenamedConstants(type);
    final Class<Enum> constants = (Class<Enum>) type;
    return parser -> JsonReads.readEnum(parser, constants);
  }

  /** A record or bean, built by its own frame from a JSON object. */
  private static Read nested(final Frame<?> frame) {
    final var name = frame.type().getSimpleName();
    return parser -> JsonReads.readObject(parser, name, frame::read);
  }

  /** A {@code List}, from a JSON array; a {@code null} element takes its type's absent value. */
  private static Read list(final Read element, final Object absent) {
    return parser -> JsonReads.readList(parser, element::from, absent);
  }

  /** A {@code Set}, from a JSON array, in the order the array holds its elements. */
  private static Read set(final Read element, final Object absent) {
    return parser -> JsonReads.readSet(parser, element::from, absent);
  }

  /** An {@code Optional}: present for any value; the caller answers {@code null} with empty. */
  private static Read optional(final Read element) {
    return parser -> JsonReads.readOptional(parser, element::from);
  }

  /**
   * A {@code Map}, from a JSON object. A JSON key is text, so the key type is one read from text: a
   * {@code String}, an enum constant's name, a JDK value type's text form, or a primitive's
   * literal. A key that appears twice keeps the value it holds last.
   */
  private Resolution map(final Type keyType, final Type valueType) {
    final var keyKind = MapValueTypes.classify(keyType, MODEL).kind();
    final var keyRefusal = PairingMessages.jsonNoKeyReading(keyKind, keyType.getTypeName());
    if (keyRefusal != null) return Resolution.refused(keyRefusal);
    final var keyClass = rawOf(keyType);
    if (keyKind == MapValueTypes.Kind.ENUM) JacksonAnnotations.refuseRenamedConstants(keyClass);
    final var value = resolve(valueType);
    if (value.read() == null) return value;
    final var valueRead = value.read();
    final var absent = absentOf(valueType);
    return Resolution.reads(parser -> JsonReads.readMap(parser, keyClass, valueRead::from, absent));
  }

  /**
   * The value as the document writes it, for a row's converter and for a property declared {@code
   * Object}.
   */
  static Object natural(final JsonParser parser) {
    return JsonReads.readNatural(parser);
  }

  // ---------------------------------------------------------------------------------------------
  // Types
  // ---------------------------------------------------------------------------------------------

  /**
   * What a property holds when the document leaves it without a value: a primitive's default, an
   * empty {@code List}, {@code Set}, {@code Map} or {@code Optional}, and {@code null} for anything
   * else. It is what {@code fromMap} and the {@code @FromMap} binder leave, so the three readers
   * agree on a missing key.
   */
  static Object absentOf(final Type type) {
    final var raw =
      type instanceof Class<?> c ? c : type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : null;
    if (raw != null && raw.isPrimitive()) return primitiveDefault(raw);
    if (raw == List.class || raw == Set.class || raw == Map.class || raw == Optional.class) {
      return NullDefaults.defaultFor(raw);
    }
    return null;
  }

  private static Object primitiveDefault(final Class<?> primitive) {
    if (primitive == boolean.class) return false;
    if (primitive == char.class) return '\0';
    if (primitive == long.class) return 0L;
    if (primitive == double.class) return 0.0d;
    if (primitive == float.class) return 0.0f;
    if (primitive == short.class) return (short) 0;
    if (primitive == byte.class) return (byte) 0;
    return 0;
  }

  /**
   * The class a declared type names, or null for a primitive, an array, a type variable or a
   * wildcard. The reflection API reports the raw type of a parameterized type as a {@code Class}.
   */
  private static Class<?> rawOf(final Type type) {
    if (type instanceof Class<?> c) return c.isPrimitive() || c.isArray() ? null : c;
    return type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : null;
  }

  private static Type argument(final Type type, final int index) {
    return ((ParameterizedType) type).getActualTypeArguments()[index];
  }

  /** A declared type as a message names it: {@code List<Line>}, {@code int}. */
  static String typeName(final Type type) {
    if (type instanceof Class<?> c) return c.getSimpleName();
    if (type instanceof ParameterizedType p) {
      return (
        typeName(p.getRawType()) +
        Arrays.stream(p.getActualTypeArguments()).map(Plan::typeName).collect(Collectors.joining(", ", "<", ">"))
      );
    }
    return type.getTypeName();
  }
}
