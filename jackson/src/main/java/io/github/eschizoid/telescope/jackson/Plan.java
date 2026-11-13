package io.github.eschizoid.telescope.jackson;

import io.github.eschizoid.telescope.conversion.FromMapProvider;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.NullDefaults;
import io.github.eschizoid.telescope.internal.Records;
import io.github.eschizoid.telescope.internal.pairing.MapValueTypes;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.internal.pairing.PropertyNames;
import io.github.eschizoid.telescope.internal.pairing.Scalar;
import io.github.eschizoid.telescope.mapping.Extract;
import io.github.eschizoid.telescope.mapping.MapExtractStep;
import io.github.eschizoid.telescope.mapping.Require;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.InputCoercionException;

/**
 * Builds the frames a {@link JsonBinder} reads with, once, when the binder is built. Every property
 * of every type the target holds is resolved here to the key it reads and the {@link Read} for its
 * declared type, so reading a document decides nothing by type.
 *
 * <p>Which kind of reading a declared type gets is decided by {@link MapValueTypes#classify}, the
 * rule {@code fromMap} and the {@code @FromMap} binder use, so the three readers accept the same
 * types. A record or a bean the binder can build is read as a nested object by its own frame. The
 * JSON readings are stricter than the map conversions in one way: a value is taken only from the
 * JSON kind its type is written as. A number is never read from text, text never from a number, and
 * an integral property never from a fraction or from an integer past its range.
 *
 * <p>The token loop is boundary machinery at the same floor as the record and bean assembly it ends
 * in: a streaming parser has no reversible, lawful decomposition into optics, so this engine builds
 * values and does not compose them.
 */
final class Plan {

  private static final String ROW = "name it with an extract(key, accessor, converter) row";

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
    if (!bindable(target)) {
      throw new IllegalArgumentException(PairingMessages.jsonNotBindable(target.getName()));
    }
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
    if (raw == null || raw.isPrimitive() || raw.isArray() || raw.isEnum() || raw.isInterface()) {
      return false;
    }
    if (Modifier.isAbstract(raw.getModifiers()) || MapValueTypes.isJdk(raw.getName())) {
      return false;
    }
    if (Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)) {
      return false;
    }
    return raw.isRecord() || Beans.propertyNames(raw).length > 0;
  }

  /** The frame for {@code type}, registered before it is filled so a type can hold itself. */
  @SuppressWarnings("unchecked")
  private <T> Frame<T> frame(final Class<T> type) {
    final var existing = frames.get(type);
    if (existing != null) {
      return (Frame<T>) existing;
    }
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
    if (record) {
      final var components = type.getRecordComponents();
      names = new String[components.length];
      types = new Type[components.length];
      for (var i = 0; i < components.length; i++) {
        names[i] = components[i].getName();
        types[i] = components[i].getGenericType();
      }
      writer = null;
    } else {
      writer = Beans.autoWriter(type);
      names = Beans.propertyNames(type);
      types = new Type[names.length];
      for (var i = 0; i < names.length; i++) {
        final var ambiguity = Beans.setterAmbiguity(writer, type, names[i]);
        if (ambiguity != null) {
          throw new IllegalArgumentException("TelescopeJson.fromJson: " + ambiguity);
        }
        types[i] = Beans.conversionType(writer, type, names[i]);
      }
    }
    final var byName = rowsFor(type, List.of(names));
    if (writer != null) {
      refuseUnwritableRows(type, writer, byName);
    }
    final var readsOwnName = Arrays.stream(names).anyMatch(name ->
      !byName.containsKey(name) || byName.get(name).key().equals(name)
    );
    final var declaredRequired = readsOwnName ? declaredRequired(type) : List.<String>of();

    final var n = names.length;
    final var keys = new String[n];
    final var declared = new String[n];
    final var reads = new Read[n];
    final var absents = new Object[n];
    final var required = new boolean[n];
    final var indexByKey = HashMap.<String, Integer>newHashMap(n);
    for (var i = 0; i < n; i++) {
      final var name = names[i];
      final var declaredType = types[i];
      declared[i] = typeName(declaredType);
      absents[i] = absentOf(declaredType);
      final var row = byName.get(name);
      if (row != null) {
        keys[i] = row.key();
        reads[i] = rowRead(row);
        required[i] = row instanceof Require<?, ?> || (row.key().equals(name) && declaredRequired.contains(name));
      } else {
        keys[i] = name;
        required[i] = declaredRequired.contains(name);
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
      }
      final var previous = indexByKey.put(keys[i], i);
      if (previous != null) {
        throw new IllegalArgumentException(
          PairingMessages.jsonKeyReadTwice(keys[i], names[previous], name, type.getSimpleName())
        );
      }
    }
    final Function<Object[], T> construct = writer == null
      ? args -> Records.construct(type, args)
      : beanConstruct(type, writer, names, reads);
    frame.complete(kind, names, keys, declared, reads, absents, required, indexByKey, construct);
  }

  /** The rows that name a property of {@code type}, by the property they name. */
  private Map<String, MapExtractStep> rowsFor(final Class<?> type, final List<String> known) {
    final var byName = new LinkedHashMap<String, MapExtractStep>();
    for (final var row : rows) {
      if (!LambdaIntrospection.implClassOf(row.targetAccessor()).isAssignableFrom(type)) {
        continue;
      }
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
      if (refusal != null) {
        throw new IllegalArgumentException("TelescopeJson.fromJson: " + refusal);
      }
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
      for (var j = 0; j < afterBuild.size(); j++) {
        convertedWriters.get(j).accept(bean, args[afterBuild.get(j)]);
      }
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
        if (!providers.hasNext()) {
          return List.of();
        }
        provider = providers.next();
      } catch (final ServiceConfigurationError e) {
        continue;
      }
      if (provider.targetType() != type) {
        continue;
      }
      try {
        return provider.required();
      } catch (final UnsupportedOperationException e) {
        throw new IllegalArgumentException(
          "TelescopeJson.fromJson: " + type.getSimpleName() + " has a registered @FromMap binder, but " + e.getMessage(),
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
      if (read == null) {
        throw refusal.apply(reason);
      }
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

    /** Every record and bean the binder can build is read as a nested object by its own frame. */
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
      case CAST -> Resolution.reads(text(PairingMessages.withArticle(raw.getSimpleName())));
      case AS_IS -> Resolution.reads(Plan::natural);
      case STRING_BUILT -> Resolution.reads(stringBuilt(raw));
      case COLLECTION_SUBTYPE -> Resolution.refused(
        raw.getName() + " is a collection subtype; declare it as List/Set/Map/Optional, or " + ROW
      );
      case UNKNOWN_JDK -> Resolution.refused(raw.getName() + " can't be read from a JSON value; " + ROW);
      case NO_BINDER -> Resolution.refused(
        raw.getName() + " is neither a record nor a class with properties to bind; " + ROW
      );
      case UNSUPPORTED -> Resolution.refused(
        type.getTypeName() + " can't be read from a JSON value (type variable / array / unsupported kind); " + ROW
      );
    };
  }

  private Resolution container(final Type element, final Function<Read, Read> lift) {
    final var resolved = resolve(element);
    return resolved.read() == null ? resolved : Resolution.reads(lift.apply(resolved.read()));
  }

  /** A primitive or its wrapper, read only from the JSON kind it is written as. */
  private static Read scalar(final String primitive, final boolean unboxed) {
    final var what = PairingMessages.scalarPhrase(SCALARS.get(primitive), unboxed, null);
    return switch (primitive) {
      case "int" -> integral(what, JsonParser::getIntValue);
      case "long" -> integral(what, JsonParser::getLongValue);
      case "short" -> integral(what, JsonParser::getShortValue);
      // jackson-core's own byte reading accepts 128 through 255 as unsigned and wraps them, so
      // the signed range is enforced here.
      case "byte" -> integral(what, parser -> {
        final var value = parser.getIntValue();
        if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
          throw Refused.value(parser, what, null);
        }
        return (byte) value;
      });
      case "double" -> parser -> {
        requireNumber(parser, what);
        final var value = parser.getDoubleValue();
        if (Double.isInfinite(value)) {
          throw Refused.value(parser, what, null);
        }
        return value;
      };
      case "float" -> parser -> {
        requireNumber(parser, what);
        final var value = parser.getFloatValue();
        if (Float.isInfinite(value)) {
          throw Refused.value(parser, what, null);
        }
        return value;
      };
      case "boolean" -> parser -> switch (parser.currentToken()) {
        case VALUE_TRUE -> Boolean.TRUE;
        case VALUE_FALSE -> Boolean.FALSE;
        default -> throw Refused.value(parser, what, null);
      };
      case "char" -> parser -> {
        if (parser.currentToken() != JsonToken.VALUE_STRING || parser.getStringLength() != 1) {
          throw Refused.value(parser, what, null);
        }
        return parser.getString().charAt(0);
      };
      default -> throw new IllegalStateException("not a primitive: " + primitive);
    };
  }

  /**
   * An integral value: an integer token in the type's range. A fraction is refused rather than
   * truncated, and an integer past the range is refused rather than wrapped.
   */
  private static Read integral(final String what, final Function<JsonParser, Object> value) {
    return parser -> {
      if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
        throw Refused.value(parser, what, null);
      }
      try {
        return value.apply(parser);
      } catch (final InputCoercionException outOfRange) {
        throw Refused.value(parser, what, outOfRange);
      }
    };
  }

  private static void requireNumber(final JsonParser parser, final String what) {
    final var token = parser.currentToken();
    if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) {
      throw Refused.value(parser, what, null);
    }
  }

  /** An enum constant, named by its exact name in a JSON string. */
  private static Read enumRead(final Class<?> type) {
    final var constants = new HashMap<String, Object>();
    for (final var constant : type.getEnumConstants()) {
      constants.put(((Enum<?>) constant).name(), constant);
    }
    final var what = PairingMessages.scalarPhrase(Scalar.ENUM, false, type.getSimpleName());
    return parser -> {
      if (parser.currentToken() == JsonToken.VALUE_STRING) {
        final var constant = constants.get(parser.getString());
        if (constant != null) {
          return constant;
        }
      }
      throw Refused.value(parser, what, null);
    };
  }

  /** {@code String} or {@code CharSequence}: a JSON string, as it stands. */
  private static Read text(final String what) {
    return parser -> {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        throw Refused.value(parser, what, null);
      }
      return parser.getString();
    };
  }

  /**
   * A JDK value type. {@code BigDecimal} and {@code BigInteger} are numbers in JSON and are read
   * from a number token, every digit kept; every other type in the table is read from its text.
   */
  private static Read stringBuilt(final Class<?> type) {
    final var what = PairingMessages.withArticle(type.getSimpleName());
    if (type == BigDecimal.class) {
      return parser -> {
        requireNumber(parser, what);
        return parser.getDecimalValue();
      };
    }
    if (type == BigInteger.class) {
      return parser -> {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
          throw Refused.value(parser, what, null);
        }
        return parser.getBigIntegerValue();
      };
    }
    final var build = MapValueTypes.stringBuilt(type.getName()).orElseThrow().build();
    return parser -> {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        throw Refused.value(parser, what, null);
      }
      try {
        return build.apply(parser.getString());
      } catch (final RuntimeException unparsable) {
        throw Refused.value(parser, what, unparsable);
      }
    };
  }

  /** A record or bean, built by its own frame from a JSON object. */
  private static Read nested(final Frame<?> frame) {
    final var what = PairingMessages.withArticle(frame.type().getSimpleName());
    return parser -> {
      if (parser.currentToken() != JsonToken.START_OBJECT) {
        throw Refused.value(parser, what, null);
      }
      return frame.read(parser);
    };
  }

  /** A {@code List}, from a JSON array; a {@code null} element takes its type's absent value. */
  private static Read list(final Read element, final Object absent) {
    return parser -> {
      if (parser.currentToken() != JsonToken.START_ARRAY) {
        throw Refused.value(parser, "a List", null);
      }
      final var out = new ArrayList<>();
      addElements(parser, element, absent, out);
      return out;
    };
  }

  /** A {@code Set}, from a JSON array, in the order the array holds its elements. */
  private static Read set(final Read element, final Object absent) {
    return parser -> {
      if (parser.currentToken() != JsonToken.START_ARRAY) {
        throw Refused.value(parser, "a Set", null);
      }
      final var out = new LinkedHashSet<>();
      addElements(parser, element, absent, out);
      return out;
    };
  }

  private static void addElements(
    final JsonParser parser,
    final Read element,
    final Object absent,
    final Collection<Object> out
  ) {
    var index = 0;
    for (var token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
      if (token == JsonToken.VALUE_NULL) {
        out.add(absent);
      } else {
        try {
          out.add(element.from(parser));
        } catch (final Refused refused) {
          throw refused.at(index);
        }
      }
      index++;
    }
  }

  /** An {@code Optional}: present for any value; the caller answers {@code null} with empty. */
  private static Read optional(final Read element) {
    return parser -> Optional.ofNullable(element.from(parser));
  }

  /**
   * A {@code Map}, from a JSON object. A JSON key is text, so the key type is one read from text: a
   * {@code String}, an enum constant's name, a JDK value type's text form, or a primitive's
   * literal. A key that appears twice keeps the value it holds last.
   */
  private Resolution map(final Type keyType, final Type valueType) {
    final var key = keyRead(keyType);
    if (key.read() == null) {
      return key;
    }
    final var value = resolve(valueType);
    if (value.read() == null) {
      return value;
    }
    final var keyRead = key.read();
    final var valueRead = value.read();
    final var absent = absentOf(valueType);
    return Resolution.reads(parser -> {
      if (parser.currentToken() != JsonToken.START_OBJECT) {
        throw Refused.value(parser, "a Map", null);
      }
      final var out = new LinkedHashMap<>();
      for (var name = parser.nextName(); name != null; name = parser.nextName()) {
        try {
          final var k = keyRead.from(parser);
          final var token = parser.nextToken();
          out.put(k, token == JsonToken.VALUE_NULL ? absent : valueRead.from(parser));
        } catch (final Refused refused) {
          throw refused.at(name);
        }
      }
      return out;
    });
  }

  /** How a JSON key, the parser on its name, becomes a key of the declared type. */
  private static Resolution keyRead(final Type type) {
    final var classified = MapValueTypes.classify(type, MODEL);
    final var raw = rawOf(type);
    final Function<String, Object> parse;
    final String what;
    switch (classified.kind()) {
      case CAST -> {
        return Resolution.reads(JsonParser::currentName);
      }
      case ENUM -> {
        final var constants = new HashMap<String, Object>();
        for (final var constant : raw.getEnumConstants()) {
          constants.put(((Enum<?>) constant).name(), constant);
        }
        what = PairingMessages.scalarPhrase(Scalar.ENUM, false, raw.getSimpleName());
        parse = name -> {
          final var constant = constants.get(name);
          if (constant == null) {
            throw new IllegalArgumentException(name);
          }
          return constant;
        };
      }
      case STRING_BUILT -> {
        what = PairingMessages.withArticle(raw.getSimpleName());
        parse = MapValueTypes.stringBuilt(raw.getName()).orElseThrow().build();
      }
      case SCALAR -> {
        what = PairingMessages.scalarPhrase(SCALARS.get(classified.scalar()), false, null);
        parse = keyScalar(classified.scalar());
      }
      default -> {
        return Resolution.refused(
          "a JSON object's keys are text, and " +
            type.getTypeName() +
            " is not read from text; declare the key as a String, an enum or a JDK value type, or " +
            ROW
        );
      }
    }
    return Resolution.reads(parser -> {
      try {
        return parse.apply(parser.currentName());
      } catch (final RuntimeException unparsable) {
        throw Refused.value(parser, what, unparsable);
      }
    });
  }

  private static Function<String, Object> keyScalar(final String primitive) {
    return switch (primitive) {
      case "int" -> Integer::valueOf;
      case "long" -> Long::valueOf;
      case "short" -> Short::valueOf;
      case "byte" -> Byte::valueOf;
      case "double" -> Double::valueOf;
      case "float" -> Float::valueOf;
      case "boolean" -> name -> switch (name) {
        case "true" -> Boolean.TRUE;
        case "false" -> Boolean.FALSE;
        default -> throw new IllegalArgumentException(name);
      };
      case "char" -> name -> {
        if (name.length() != 1) {
          throw new IllegalArgumentException(name);
        }
        return name.charAt(0);
      };
      default -> throw new IllegalStateException("not a primitive: " + primitive);
    };
  }

  /**
   * The value as the document writes it, for a row's converter and for a property declared {@code
   * Object}.
   */
  static Object natural(final JsonParser parser) {
    return switch (parser.currentToken()) {
      case START_OBJECT -> {
        final var out = new LinkedHashMap<String, Object>();
        for (var name = parser.nextName(); name != null; name = parser.nextName()) {
          parser.nextToken();
          out.put(name, natural(parser));
        }
        yield out;
      }
      case START_ARRAY -> {
        final var out = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          out.add(natural(parser));
        }
        yield out;
      }
      case VALUE_STRING -> parser.getString();
      case VALUE_NUMBER_INT -> parser.getNumberValue();
      case VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
      case VALUE_TRUE -> Boolean.TRUE;
      case VALUE_FALSE -> Boolean.FALSE;
      case VALUE_NULL -> null;
      default -> throw new IllegalStateException("not a value token: " + parser.currentToken());
    };
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
    final var raw = type instanceof Class<?> c
      ? c
      : type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : null;
    if (raw != null && raw.isPrimitive()) {
      return primitiveDefault(raw);
    }
    if (raw == List.class || raw == Set.class || raw == Map.class || raw == Optional.class) {
      return NullDefaults.defaultFor(raw);
    }
    return null;
  }

  private static Object primitiveDefault(final Class<?> primitive) {
    if (primitive == boolean.class) {
      return false;
    }
    if (primitive == char.class) {
      return '\0';
    }
    if (primitive == long.class) {
      return 0L;
    }
    if (primitive == double.class) {
      return 0.0d;
    }
    if (primitive == float.class) {
      return 0.0f;
    }
    if (primitive == short.class) {
      return (short) 0;
    }
    if (primitive == byte.class) {
      return (byte) 0;
    }
    return 0;
  }

  /**
   * The class a declared type names, or null for a primitive, an array, a type variable or a
   * wildcard. The reflection API reports the raw type of a parameterized type as a {@code Class}.
   */
  private static Class<?> rawOf(final Type type) {
    if (type instanceof Class<?> c) {
      return c.isPrimitive() || c.isArray() ? null : c;
    }
    return type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : null;
  }

  private static Type argument(final Type type, final int index) {
    return ((ParameterizedType) type).getActualTypeArguments()[index];
  }

  /** A declared type as a message names it: {@code List<Line>}, {@code int}. */
  static String typeName(final Type type) {
    if (type instanceof Class<?> c) {
      return c.getSimpleName();
    }
    if (type instanceof ParameterizedType p) {
      return (
        typeName(p.getRawType()) +
        Arrays.stream(p.getActualTypeArguments()).map(Plan::typeName).collect(Collectors.joining(", ", "<", ">"))
      );
    }
    return type.getTypeName();
  }
}
