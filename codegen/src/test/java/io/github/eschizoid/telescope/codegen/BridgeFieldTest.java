package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A {@code @Bridge} on a mapper field: the processor compiles the rows of the field's mapper call
 * into a generated bridge, the field's mapper runs that bridge, and the same call built without the
 * processor gives the same result from the same rows.
 */
class BridgeFieldTest {

  private static final String HEADER =
    "package fb;\n" +
    "import static io.github.eschizoid.telescope.mapping.Mapping.*;\n" +
    "import io.github.eschizoid.telescope.Telescope;\n" +
    "import io.github.eschizoid.telescope.annotations.Bridge;\n" +
    "import io.github.eschizoid.telescope.conversion.*;\n" +
    "import io.github.eschizoid.telescope.mapping.*;\n";

  /** Records the class that called each converter, which names the path that ran it. */
  private static final String CONV =
    "package fb;\n" +
    "public final class Conv {\n" +
    "  public static final java.util.List<String> CALLERS = new java.util.ArrayList<>();\n" +
    "  private static void called() {\n" +
    "    CALLERS.add(new Throwable().getStackTrace()[2].getClassName());\n" +
    "  }\n" +
    "  public static String trim(final String s) { called(); return s == null ? null : s.trim(); }\n" +
    "  public static String pad(final String s) { called(); return s == null ? null : \" \" + s + \" \"; }\n" +
    "  public static String region() { called(); return \"eu\"; }\n" +
    "  public static final Conv INSTANCE = new Conv();\n" +
    "  public String shout(final String s) { called(); return s == null ? null : s.toUpperCase(); }\n" +
    "  public final java.util.function.Supplier<String> held = () -> \"held\";\n" +
    "}\n";

  private static final List<JavaFileObject> MODEL = List.of(
    ProcessorHarness.source("fb.Address", "package fb; public record Address(String city, String zip) {}"),
    ProcessorHarness.source("fb.AddressDto", "package fb; public record AddressDto(String city, String zip) {}"),
    ProcessorHarness.source("fb.Handle", "package fb; public record Handle(String value) {}"),
    ProcessorHarness.source(
      "fb.User",
      "package fb; public record User(long id, String email, String name, String nick, String secret," +
        " String handle, Address address) {}"
    ),
    ProcessorHarness.source(
      "fb.UserDto",
      "package fb; public record UserDto(long userId, String contact, String name, String nick, String kind," +
        " String region, Handle handle, Address address) {}"
    ),
    ProcessorHarness.source(
      "fb.UserView",
      "package fb; public record UserView(String email, String loud, String plain, AddressDto address) {}"
    ),
    ProcessorHarness.source("fb.Conv", CONV),
    ProcessorHarness.source(
      "fb.AddressMappers",
      HEADER +
        "public final class AddressMappers {\n" +
        "  @Bridge public static final Mapper<Address, AddressDto> ADDRESS =" +
        " Telescope.mapper(Address.class, AddressDto.class);\n" +
        "}\n"
    )
  );

  private static final String USER_ROWS =
    "Telescope.mapperForward(User.class, UserDto.class,\n" +
    "      to(User::id, UserDto::userId),\n" +
    "      toOneWay(User::email, UserDto::contact, String::toLowerCase),\n" +
    "      to(User::name, UserDto::name, Conv::trim, Conv::pad),\n" +
    "      toOrElse(User::nick, UserDto::nick, \"anon\"),\n" +
    "      toOneWay(User::handle, UserDto::handle, Handle::new),\n" +
    "      drop(User::secret),\n" +
    "      constant(UserDto::kind, \"web\"),\n" +
    "      compute(UserDto::region, Conv::region));\n";

  private static final String VIEW_ROWS =
    "Telescope.mapperForward(User.class, UserView.class,\n" +
    "      to(User::email, UserView::email),\n" +
    "      toOneWay(User::name, UserView::loud, Conv.INSTANCE::shout),\n" +
    "      toOneWay(User::name, UserView::plain, Conv.INSTANCE::shout),\n" +
    "      via(User::address, UserView::address, AddressMappers.ADDRESS));\n";

  private static JavaFileObject[] sources(final String... extra) {
    final var all = new ArrayList<>(MODEL);
    for (var i = 0; i < extra.length; i += 2) all.add(ProcessorHarness.source(extra[i], extra[i + 1]));
    return all.toArray(JavaFileObject[]::new);
  }

  private static ProcessorHarness.Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(
      List.<Processor>of(new BridgeProcessor(), new MapperVerifierProcessor()),
      List.of(),
      sources
    );
  }

  /** A loader over the compiled classes and generated resources, as a class path holds them. */
  private static URLClassLoader load(final ProcessorHarness.Compilation c) throws IOException {
    final var dir = Files.createTempDirectory("bridge-field");
    for (final var e : c.classes().entrySet())
      write(dir.resolve(e.getKey().replace('.', '/') + ".class"), e.getValue());
    for (final var e : c.resources().entrySet()) write(dir.resolve(e.getKey()), e.getValue().getBytes());
    return new URLClassLoader(new URL[] { dir.toUri().toURL() }, BridgeFieldTest.class.getClassLoader());
  }

  private static void write(final Path file, final byte[] bytes) throws IOException {
    Files.createDirectories(file.getParent());
    Files.write(file, bytes);
  }

  private static Object field(final ClassLoader loader, final String owner, final String name) throws Exception {
    return loader.loadClass(owner).getField(name).get(null);
  }

  /** A field that is not public, read as code of its own package would. */
  private static Object packageField(final ClassLoader loader, final String owner, final String name) throws Exception {
    final var field = loader.loadClass(owner).getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
  }

  private static Object user(final ClassLoader loader, final String nick) throws Exception {
    final var address = loader.loadClass("fb.Address").getConstructors()[0].newInstance("Paris", "75001");
    return loader
      .loadClass("fb.User")
      .getConstructors()[0].newInstance(7L, "Ada@Example.COM", " ada ", nick, "s3cr3t", "@ada", address);
  }

  @SuppressWarnings("unchecked")
  private static List<String> callers(final ClassLoader loader) throws Exception {
    return (List<String>) field(loader, "fb.Conv", "CALLERS");
  }

  @Test
  @DisplayName("a field's mapper runs the bridge generated from its rows, and gives the runtime mapper's result")
  void aFieldsMapperRunsItsGeneratedBridge() throws Exception {
    final var c = compile(
      sources(
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge public static final ForwardMapper<User, UserDto> USER = " +
          USER_ROWS +
          "  @Bridge public static final ForwardMapper<User, UserView> VIEW = " +
          VIEW_ROWS +
          "}\n",
        "fb.RuntimeMappers",
        HEADER +
          "public final class RuntimeMappers {\n" +
          "  public static final ForwardMapper<User, UserDto> USER = " +
          USER_ROWS.replace("UserDto.class", "UserDto.class") +
          "}\n",
        "fb.RuntimeViews",
        HEADER +
          "public final class RuntimeViews {\n" +
          "  public static final ForwardMapper<User, UserView> VIEW = " +
          VIEW_ROWS +
          "}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    assertTrue(c.generated().containsKey("fb.UserToUserDtoBridge"), c.generated()::toString);
    assertTrue(c.generated().containsKey("fb.UserToUserViewBridge"), c.generated()::toString);
    try (final var loader = load(c)) {
      for (final var nick : new String[] { "lovelace", null }) {
        final var user = user(loader, nick);
        @SuppressWarnings("unchecked")
        final var generated = (ForwardMapper<Object, Object>) field(loader, "fb.Mappers", "USER");
        @SuppressWarnings("unchecked")
        final var runtime = (ForwardMapper<Object, Object>) field(loader, "fb.RuntimeMappers", "USER");
        callers(loader).clear();
        final var fromGenerated = generated.forward(user);
        final var generatedCallers = List.copyOf(callers(loader));
        callers(loader).clear();
        final var fromRuntime = runtime.forward(user);
        final var runtimeCallers = List.copyOf(callers(loader));
        assertEquals(fromRuntime, fromGenerated);
        assertEquals(OpticNode.Runs.GENERATED, generated.explain().declared().orElseThrow().runs());
        assertTrue(runtime.explain().declared().isEmpty());
        assertTrue(
          generatedCallers.stream().allMatch(n -> n.startsWith("fb.UserToUserDtoBridge")),
          generatedCallers::toString
        );
        assertFalse(runtimeCallers.isEmpty());
        assertTrue(
          runtimeCallers.stream().noneMatch(n -> n.startsWith("fb.UserToUserDtoBridge")),
          runtimeCallers::toString
        );
      }
      final var user = user(loader, "x");
      @SuppressWarnings("unchecked")
      final var view = (ForwardMapper<Object, Object>) field(loader, "fb.Mappers", "VIEW");
      @SuppressWarnings("unchecked")
      final var runtimeView = (ForwardMapper<Object, Object>) field(loader, "fb.RuntimeViews", "VIEW");
      assertEquals(runtimeView.forward(user), view.forward(user));
      assertEquals(
        "UserView[email=Ada@Example.COM, loud= ADA , plain= ADA , address=AddressDto[city=Paris, zip=75001]]",
        view.forward(user).toString()
      );
      @SuppressWarnings("unchecked")
      final var address = (Mapper<Object, Object>) field(loader, "fb.AddressMappers", "ADDRESS");
      final var a = loader.loadClass("fb.Address").getConstructors()[0].newInstance("Oslo", "0150");
      assertEquals(a, address.backward(address.forward(a)));
    }
  }

  @Test
  @DisplayName("without the processor, the same field builds the runtime mapper from its rows")
  void withoutTheProcessorTheFieldBuildsTheRuntimeMapper() throws Exception {
    final var c = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      sources(
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge public static final ForwardMapper<User, UserDto> USER = " +
          USER_ROWS +
          "}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var mapper = (ForwardMapper<Object, Object>) field(loader, "fb.Mappers", "USER");
      callers(loader).clear();
      assertEquals(
        "UserDto[userId=7, contact=ada@example.com, name=ada, nick=lovelace, kind=web, region=eu," +
          " handle=Handle[value=@ada], address=Address[city=Paris, zip=75001]]",
        mapper.forward(user(loader, "lovelace")).toString()
      );
      assertTrue(
        callers(loader)
          .stream()
          .noneMatch(n -> n.contains("Bridge")),
        callers(loader)::toString
      );
    }
  }

  static List<String[]> refusals() {
    return List.of(
      new String[] {
        "toOneWay(User::name, UserDto::name, s -> s.trim())",
        "an inline lambda can't be compiled into the generated bridge; write a method reference",
      },
      new String[] { "Rows.ID", "a @Bridge mapper compiles only rows written in place as a Mapping factory call" },
      new String[] {
        "constant(UserDto::kind, String.valueOf(1))",
        "a value in a @Bridge mapper must be a literal or an enum constant",
      },
      new String[] { "when(User::isAdmin, drop(User::secret))", "when(...) has no generated form yet" },
      new String[] {
        "to(Telescope.of(User.class).field(User::address).field(a -> a.city()), UserDto::kind)",
        "an inline lambda in a path can't be compiled into the generated bridge",
      },
      new String[] {
        "to(Telescope.of(User.class).field(User::address).fieldByName(\"city\", String.class), UserDto::kind)",
        "fieldByName(...) in a path has no generated form",
      },
      new String[] {
        "to(Telescope.of(User.class).field(User::address).then(Telescope.of(Address.class).field(Address::city))," +
        " UserDto::kind)",
        "then(...) in a path has no generated form",
      },
      new String[] { "to(Rows.CITY, UserDto::kind)", "this argument is not such a path" },
      new String[] {
        "to(Telescope.of(Address.class).field(Address::city), UserDto::kind)",
        "this path starts at Address, which is not User or a class it extends",
      },
      new String[] {
        "to(User::nick, Telescope.of(UserDto.class).field(UserDto::handle).field(Handle::value))",
        "a row writing into a target path has no generated form yet",
      },
      new String[] {
        "to(Telescope.of(User.class).field(User::address).field(Address::city)," +
        " Telescope.of(UserDto.class).field(UserDto::address).field(Address::city))",
        "a row whose source and target are both telescope paths has no generated form yet",
      },
      new String[] {
        "to(User::email, UserDto::contact), to(Telescope.of(User.class).field(User::email), UserDto::contact)",
        "duplicate override row for target field 'contact'",
      },
      new String[] { "to(Address::city, AddressDto::city)", "a row keyed to a nested pair has no generated form yet" },
      new String[] {
        "toOneWay(User::name, UserDto::name, new Conv()::shout)",
        "has a receiver the generated bridge cannot name",
      },
      new String[] { "via(User::address, UserDto::address, Rows.RUNTIME)", "names another @Bridge mapper field" },
      new String[] {
        "toOneWay(User::name, UserDto::name, Conv::trim), toOneWay(User::name, UserDto::nick, Conv::pad)",
        "is converted differently for 'nick'",
      },
      new String[] {
        "to(User::name, UserDto::name), to(User::nick, UserDto::name)",
        "duplicate override row for target field 'name'",
      },
      new String[] {
        "drop(User::secret, Address.class)",
        "drop(...) keyed to a nested pair has no generated form yet",
      },
      new String[] {
        "to(User::name, UserDto::name, NoCtor.class)",
        "NoCtor has no no-argument constructor the generated bridge can call",
      },
      new String[] { "to(User::name, UserDto::name, AbstractFn.class)", "AbstractFn is abstract or not a class" },
      new String[] { "to(User::toString, UserDto::kind)", "User::toString is not the accessor of a property of User" },
      new String[] {
        "constant(UserDto::nick, 5)",
        "the value 5 is int, which the java.lang.String property 'nick' does not hold; write it as a java.lang.String value",
      },
      new String[] {
        "constant(UserDto::userId, 5)",
        "the value 5 is int, which the long property 'userId' does not hold; write it as a long value",
      },
      new String[] { "drop(User::secret), drop(User::secret)", "a property is dropped once, by a row of its own" },
      new String[] {
        "to(User::name, UserDto::name), drop(User::name)",
        PairingMessages.droppedAndWritten("User", "UserDto", "name"),
      },
      new String[] {
        "drop(User::name), to(User::name, UserDto::name)",
        PairingMessages.droppedAndWritten("User", "UserDto", "name"),
      },
      new String[] {
        "toOneWay(User::name, UserDto::name, Mappers::lower)",
        "Mappers.lower cannot be called from the generated bridge in package 'fb'",
      },
      new String[] {
        "toOrElse(User::nick, UserDto::nick, \"a\", String::isBlank)",
        "toOrElse(...) with a predicate has no generated form yet",
      },
      new String[] {
        "constant(UserDto::kind, \"a\"), compute(UserDto::kind, Conv::region)",
        "duplicate override row for target field 'kind'",
      },
      new String[] { "compute(UserDto::kind, Rows.MUTABLE)", "MUTABLE is not static and final" },
      new String[] {
        "compute(UserDto::kind, Rows.RAW)",
        "Rows.RAW gives java.lang.Object, which the java.lang.String property 'kind' does not hold",
      },
      new String[] {
        "io.github.eschizoid.telescope.mapping.WriteHint.writeBeans(" +
        "io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.valueOf(\"SETTERS\"))",
        "a @Bridge mapper compiles a write hint whose strategy is a WriteStrategy constant written in place",
      },
      new String[] { "compute(UserDto::kind, Conv.INSTANCE.held)", "held is not static and final" },
      new String[] {
        "Mapping.<User, UserDto, Object>compute(UserDto::userId, Conv::region)",
        "Conv::region gives java.lang.String, which the long property 'userId' does not hold",
      },
      new String[] {
        "compute(UserDto::kind, HIDDEN)",
        "Mappers.HIDDEN cannot be called from the generated bridge in package 'fb'",
      },
      new String[] {
        "io.github.eschizoid.telescope.mapping.WriteHint.writeBeans(Rows.STRATEGY)",
        "a @Bridge mapper compiles a write hint whose strategy is a WriteStrategy constant written in place",
      },
      new String[] {
        "io.github.eschizoid.telescope.mapping.WriteHint.writeBean(UserDto.class.asSubclass(UserDto.class)," +
        " io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS)",
        "a @Bridge mapper compiles a writeBean hint keyed to a class literal",
      }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refusals")
  @DisplayName("a row the generated bridge cannot express is refused by name")
  void aRowTheBridgeCannotExpressIsRefusedByName(final String rows, final String message) {
    final var c = compile(
      sources(
        "fb.Rows",
        HEADER +
          "final class Rows {\n" +
          "  static final Mapping<User, UserDto> ID = to(User::id, UserDto::userId);\n" +
          "  static final Telescope<User, String> CITY =" +
          " Telescope.of(User.class).field(User::address).field(Address::city);\n" +
          "  static final Mapper<Address, AddressDto> RUNTIME =" +
          " Telescope.mapper(Address.class, AddressDto.class);\n" +
          "  static java.util.function.Supplier<String> MUTABLE = () -> \"m\";\n" +
          "  @SuppressWarnings(\"rawtypes\") static final java.util.function.Supplier RAW = () -> 1;\n" +
          "  static final WriteHint.WriteStrategy STRATEGY = WriteHint.WriteStrategy.SETTERS;\n" +
          "}\n",
        "fb.Admin",
        "package fb; final class Admin { static boolean is(User u) { return false; } }",
        "fb.NoCtor",
        "package fb; public final class NoCtor implements io.github.eschizoid.telescope.conversion.BridgeFn<String, String> {" +
          " public NoCtor(int x) {} public String forward(String s) { return s; } public String backward(String s) { return s; } }",
        "fb.AbstractFn",
        "package fb; public abstract class AbstractFn implements io.github.eschizoid.telescope.conversion.BridgeFn<String, String> {}",
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge static final ForwardMapper<User, UserDto> USER =" +
          " Telescope.mapperForward(User.class, UserDto.class, " +
          rows.replace("User::isAdmin", "Admin::is") +
          ");\n  private static String lower(final String s) { return s; }\n" +
          "  private static final java.util.function.Supplier<String> HIDDEN = () -> \"h\";\n}\n"
      )
    );
    assertFalse(c.success(), "the row must be refused");
    assertTrue(c.hasError(message), c::errorMessages);
  }

  @Test
  @DisplayName("a field that is not static final, or not a mapper call, is refused by name")
  void aFieldThatIsNotAMapperCallIsRefused() {
    final var notFinal = compile(
      sources(
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge static ForwardMapper<User, UserDto> USER = Telescope.mapperForward(User.class, UserDto.class);\n" +
          "}\n"
      )
    );
    assertTrue(notFinal.hasError("must be static final"), notFinal::errorMessages);
    final var helper = compile(
      sources(
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge static final ForwardMapper<User, UserDto> USER = build();\n" +
          "  static ForwardMapper<User, UserDto> build() { return Telescope.mapperForward(User.class, UserDto.class); }\n" +
          "}\n"
      )
    );
    assertTrue(helper.hasError("must be initialised by a Telescope.mapper(...)"), helper::errorMessages);
  }

  static List<String[]> unsanctionedCalls() {
    return List.of(
      new String[] {
        "the same pair in a static field",
        "  static final ForwardMapper<User, UserDto> OTHER = Telescope.mapperForward(User.class, UserDto.class," +
        " drop(User::nick), toOneWay(User::handle, UserDto::handle, Handle::new));",
      },
      new String[] {
        "classes held in a variable",
        "  static final Class<User> SOURCE = User.class;\n" +
        "  static final ForwardMapper<User, UserDto> OTHER = Telescope.mapperForward(SOURCE, UserDto.class);",
      },
      new String[] { "a static block", "  static { Telescope.mapperForward(Address.class, AddressDto.class); }" },
      new String[] {
        "a generic helper the class calls from a static field",
        "  static <X, Y> ForwardMapper<X, Y> make(final Class<X> s, final Class<Y> t, final MapStep... rows) {" +
        " return Telescope.mapperForward(s, t, rows); }\n" +
        "  static final ForwardMapper<User, UserDto> SWAP = make(User.class, UserDto.class, drop(User::nick));",
      },
      new String[] {
        "a method, another pair",
        "  static ForwardMapper<Address, AddressDto> later() {" +
        " return Telescope.mapperForward(Address.class, AddressDto.class); }",
      },
      new String[] {
        "a lambda",
        "  static final java.util.function.Supplier<ForwardMapper<Address, AddressDto>> LATER =" +
        " () -> Telescope.mapperForward(Address.class, AddressDto.class);",
      },
      new String[] {
        "a statically imported factory",
        "  static Object later() {" +
        " return io.github.eschizoid.telescope.Telescope.mapperForward(Address.class, AddressDto.class); }",
      },
      new String[] {
        "a method reference to the factory, called from a static field",
        "  interface Factory { ForwardMapper<User, UserDto> make(Class<User> s, Class<UserDto> t, MapStep... rows); }\n" +
        "  static final Factory FACTORY = Telescope::mapperForward;\n" +
        "  static final ForwardMapper<User, UserDto> SWAP = FACTORY.make(User.class, UserDto.class," +
        " drop(User::secret), toOneWay(User::handle, UserDto::handle, Handle::new));",
      },
      new String[] {
        "the factory reached through a receiver expression, for the same pair",
        "  static final ForwardMapper<User, UserDto> SWAP = ((Telescope<?, ?>) null).mapperForward(User.class," +
        " UserDto.class, drop(User::secret), toOneWay(User::handle, UserDto::handle, Handle::new));",
      }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unsanctionedCalls")
  @DisplayName("a class with a @Bridge field builds no other mapper but a static field for another pair")
  void aClassWithABridgeFieldBuildsNoOtherMapper(final String name, final String member) {
    final var c = compile(
      sources(
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge public static final ForwardMapper<User, UserDto> USER = Telescope.mapperForward(User.class," +
          " UserDto.class, drop(User::secret), toOneWay(User::handle, UserDto::handle, Handle::new));\n" +
          member +
          "\n}\n"
      )
    );
    assertFalse(c.success(), name);
    assertTrue(
      c.hasError("so every mapper the class builds is a static field initialised by Telescope.mapper(...)"),
      c::errorMessages
    );
  }

  @Test
  @DisplayName("a static field for another pair, and a nested class, may build mappers beside a @Bridge field")
  void aStaticFieldForAnotherPairIsAllowed() throws Exception {
    final var c = compile(
      sources(
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge public static final ForwardMapper<User, UserDto> USER = Telescope.mapperForward(User.class," +
          " UserDto.class, drop(User::secret), toOneWay(User::handle, UserDto::handle, Handle::new));\n" +
          "  public static final ForwardMapper<Address, AddressDto> PLAIN =" +
          " Telescope.mapperForward(Address.class, AddressDto.class);\n" +
          "  public static final class Inner {\n" +
          "    public static final ForwardMapper<User, UserDto> SAME = Telescope.mapperForward(User.class," +
          " UserDto.class, toOneWay(User::name, UserDto::name, Conv::trim)," +
          " toOneWay(User::handle, UserDto::handle, Handle::new));\n" +
          "  }\n" +
          "}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var field = (ForwardMapper<Object, Object>) field(loader, "fb.Mappers", "USER");
      assertEquals(OpticNode.Runs.GENERATED, field.explain().declared().orElseThrow().runs());
      @SuppressWarnings("unchecked")
      final var plain = (ForwardMapper<Object, Object>) field(loader, "fb.Mappers", "PLAIN");
      assertTrue(plain.explain().declared().isEmpty());
      @SuppressWarnings("unchecked")
      final var same = (ForwardMapper<Object, Object>) field(loader, "fb.Mappers$Inner", "SAME");
      assertTrue(same.explain().declared().isEmpty(), "a nested class is its own caller");
      final var user = user(loader, "lovelace");
      assertNotEquals(field.forward(user), same.forward(user), "the nested class's rows run, not the field's bridge");
    }
  }

  @Test
  @DisplayName("a call the class's text names otherwise is refused where it is, once javac has resolved it")
  void aResolvedCallIsRefusedWhereItIs() {
    final var c = compile(
      sources(
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge public static final ForwardMapper<User, UserDto> USER = Telescope.mapperForward(User.class," +
          " UserDto.class, drop(User::secret), toOneWay(User::handle, UserDto::handle, Handle::new));\n" +
          "  static final java.util.function.BiFunction<Class<Address>, Class<AddressDto>," +
          " ForwardMapper<Address, AddressDto>> LATER =\n" +
          "      Telescope::mapperForward;\n" +
          "}\n"
      )
    );
    assertFalse(c.success());
    final var refusal = c
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
      .filter(d -> d.getMessage(null).contains("so every mapper the class builds is a static field"))
      .findFirst()
      .orElseThrow(() -> new AssertionError(c.errorMessages()));
    assertEquals(10, refusal.getLineNumber());
    assertEquals(7, refusal.getColumnNumber());
  }

  @Test
  @DisplayName("a @Bridge field on the source record itself runs its bridge, and gives the runtime mapper's result")
  void aFieldOnTheSourceRecordRunsItsBridge() throws Exception {
    final var ticket =
      HEADER +
      "public record Ticket(Long id, String title, String secret) {\n" +
      "  @Bridge static final Mapper<Ticket, TicketView> TO_VIEW = Telescope.mapper(Ticket.class, TicketView.class,\n" +
      "      to(Ticket::id, TicketView::ticketId), to(Ticket::title, TicketView::title, Conv::trim, Conv::pad)," +
      " drop(Ticket::secret));\n" +
      "}\n";
    final var view = "package fb; public record TicketView(Long ticketId, String title) {}";
    final var generated = compile(sources("fb.Ticket", ticket, "fb.TicketView", view));
    assertTrue(generated.success(), generated::errorMessages);
    assertTrue(generated.generated().containsKey("fb.TicketToTicketViewBridge"), generated.generated()::toString);
    final var runtime = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      sources("fb.Ticket", ticket, "fb.TicketView", view)
    );
    assertTrue(runtime.success(), runtime::errorMessages);
    final Object fromGenerated;
    final Object back;
    try (final var loader = load(generated)) {
      @SuppressWarnings("unchecked")
      final var mapper = (Mapper<Object, Object>) packageField(loader, "fb.Ticket", "TO_VIEW");
      assertEquals(OpticNode.Runs.GENERATED, mapper.explain().declared().orElseThrow().runs());
      assertEquals("fb.TicketToTicketViewBridgeProvider$FieldMapper", mapper.getClass().getName());
      final var t = loader.loadClass("fb.Ticket").getConstructors()[0].newInstance(5L, " Ada ", "s");
      callers(loader).clear();
      fromGenerated = mapper.forward(t).toString();
      back = mapper.backward(mapper.forward(t)).toString();
      assertTrue(
        callers(loader)
          .stream()
          .allMatch(n -> n.startsWith("fb.TicketToTicketViewBridge")),
        callers(loader)::toString
      );
    }
    try (final var loader = load(runtime)) {
      @SuppressWarnings("unchecked")
      final var mapper = (Mapper<Object, Object>) packageField(loader, "fb.Ticket", "TO_VIEW");
      assertTrue(mapper.explain().declared().isEmpty());
      final var t = loader.loadClass("fb.Ticket").getConstructors()[0].newInstance(5L, " Ada ", "s");
      assertEquals(fromGenerated, mapper.forward(t).toString());
      assertEquals(back, mapper.backward(mapper.forward(t)).toString());
    }
    assertEquals("TicketView[ticketId=5, title=Ada]", fromGenerated);
  }

  @Test
  @DisplayName("a converter giving a class wider than the target's property is written after a check, on both paths")
  void aWiderConverterResultIsCheckedOnBothPaths() throws Exception {
    final var wide =
      HEADER +
      "public record Wide(String value, String other) {\n" +
      "  @Bridge static final ForwardMapper<Wide, WideView> TO_VIEW = Telescope.mapperForward(Wide.class," +
      " WideView.class,\n" +
      "      toOneWay(Wide::value, WideView::value, Widen.INSTANCE::up)," +
      " toOneWay(Wide::other, WideView::other, Widen.INSTANCE::number));\n" +
      "}\n";
    final var view = "package fb; public record WideView(String value, String other) {}";
    final var widen =
      "package fb;\n" +
      "public final class Widen {\n" +
      "  public static final Widen INSTANCE = new Widen();\n" +
      "  public Object up(final String s) { return s == null ? null : s + \"!\"; }\n" +
      "  public Object number(final String s) { return s == null ? null : s.length(); }\n" +
      "}\n";
    final var generated = compile(sources("fb.Wide", wide, "fb.WideView", view, "fb.Widen", widen));
    assertTrue(generated.success(), generated::errorMessages);
    assertTrue(
      generated
        .generated()
        .get("fb.WideToWideViewBridgeValueFn")
        .contains("BridgeFn<java.lang.String, java.lang.Object>"),
      () -> generated.generated().get("fb.WideToWideViewBridgeValueFn")
    );
    final var runtime = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      sources("fb.Wide", wide, "fb.WideView", view, "fb.Widen", widen)
    );
    assertTrue(runtime.success(), runtime::errorMessages);
    final var outcomes = new ArrayList<String>();
    for (final var compilation : List.of(generated, runtime)) {
      try (final var loader = load(compilation)) {
        @SuppressWarnings("unchecked")
        final var mapper = (ForwardMapper<Object, Object>) packageField(loader, "fb.Wide", "TO_VIEW");
        final var wideClass = loader.loadClass("fb.Wide");
        outcomes.add(mapper.forward(wideClass.getConstructors()[0].newInstance("a", null)).toString());
        final var thrown = assertThrows(RuntimeException.class, () ->
          mapper.forward(wideClass.getConstructors()[0].newInstance("a", "bc"))
        );
        outcomes.add(thrown.getClass().getSimpleName());
      }
    }
    assertEquals("WideView[value=a!, other=null]", outcomes.get(0));
    assertEquals(outcomes.subList(0, 2), outcomes.subList(2, 4));
  }

  @Test
  @DisplayName("a converter whose result is a type variable is typed by the class Java infers for it, on both paths")
  void anInferredConverterResultIsCheckedOnBothPaths() throws Exception {
    final var bag =
      HEADER +
      "public record Bag(java.util.List<CharSequence> items) {\n" +
      "  @Bridge static final ForwardMapper<Bag, BagView> TO_VIEW = Telescope.mapperForward(Bag.class," +
      " BagView.class,\n" +
      "      toOneWay(Bag::items, BagView::items, Pick.INSTANCE::first));\n" +
      "}\n";
    final var view = "package fb; public record BagView(String items) {}";
    final var pick =
      "package fb;\n" +
      "public final class Pick {\n" +
      "  public static final Pick INSTANCE = new Pick();\n" +
      "  public <T> T first(final java.util.List<T> items) { return items == null ? null : items.get(0); }\n" +
      "}\n";
    final var generated = compile(sources("fb.Bag", bag, "fb.BagView", view, "fb.Pick", pick));
    assertTrue(generated.success(), generated::errorMessages);
    final var fn = generated.generated().get("fb.BagToBagViewBridgeItemsFn");
    assertTrue(fn.contains("BridgeFn<java.util.List<java.lang.CharSequence>, java.lang.CharSequence>"), fn);
    final var runtime = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      sources("fb.Bag", bag, "fb.BagView", view, "fb.Pick", pick)
    );
    assertTrue(runtime.success(), runtime::errorMessages);
    final var outcomes = new ArrayList<String>();
    for (final var compilation : List.of(generated, runtime)) {
      try (final var loader = load(compilation)) {
        @SuppressWarnings("unchecked")
        final var mapper = (ForwardMapper<Object, Object>) packageField(loader, "fb.Bag", "TO_VIEW");
        final var bagClass = loader.loadClass("fb.Bag");
        outcomes.add(mapper.forward(bagClass.getConstructors()[0].newInstance(List.of("a", "b"))).toString());
        final var thrown = assertThrows(RuntimeException.class, () ->
          mapper.forward(bagClass.getConstructors()[0].newInstance(List.of(new StringBuilder("c"))))
        );
        outcomes.add(thrown.getClass().getSimpleName());
      }
    }
    assertEquals("BagView[items=a]", outcomes.get(0));
    assertEquals(outcomes.subList(0, 2), outcomes.subList(2, 4));
  }

  @Test
  @DisplayName("a converting row on a property typed with an unbounded or a super wildcard runs alike on both paths")
  void aConverterOverOtherWildcardsRunsOnBothPaths() throws Exception {
    final var bins =
      HEADER +
      "public record Bins(java.util.List<?> any, java.util.List<? super Integer> sink) {\n" +
      "  @Bridge static final ForwardMapper<Bins, BinsView> TO_VIEW = Telescope.mapperForward(Bins.class," +
      " BinsView.class,\n" +
      "      toOneWay(Bins::any, BinsView::any, Copy.INSTANCE::any)," +
      " toOneWay(Bins::sink, BinsView::sink, Copy.INSTANCE::sink));\n" +
      "}\n";
    final var view =
      "package fb; public record BinsView(java.util.List<?> any, java.util.List<? super Integer> sink) {}";
    final var copy =
      "package fb;\n" +
      "public final class Copy {\n" +
      "  public static final Copy INSTANCE = new Copy();\n" +
      "  public java.util.List<?> any(final java.util.List<?> l) { return l == null ? null : l.reversed(); }\n" +
      "  public java.util.List<? super Integer> sink(final java.util.List<? super Integer> l) {\n" +
      "    return l == null ? null : java.util.List.copyOf(l);\n" +
      "  }\n" +
      "}\n";
    final var generated = compile(sources("fb.Bins", bins, "fb.BinsView", view, "fb.Copy", copy));
    assertTrue(generated.success(), generated::errorMessages);
    final var any = generated.generated().get("fb.BinsToBinsViewBridgeAnyFn");
    assertTrue(any.contains("BridgeFn<java.util.List<?>, java.util.List<?>>"), any);
    final var sink = generated.generated().get("fb.BinsToBinsViewBridgeSinkFn");
    assertTrue(
      sink.contains("BridgeFn<java.util.List<? super java.lang.Integer>, java.util.List<? super java.lang.Integer>>"),
      sink
    );
    final var runtime = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      sources("fb.Bins", bins, "fb.BinsView", view, "fb.Copy", copy)
    );
    assertTrue(runtime.success(), runtime::errorMessages);
    final var outcomes = new ArrayList<String>();
    for (final var compilation : List.of(generated, runtime)) {
      try (final var loader = load(compilation)) {
        @SuppressWarnings("unchecked")
        final var mapper = (ForwardMapper<Object, Object>) packageField(loader, "fb.Bins", "TO_VIEW");
        final var source = loader
          .loadClass("fb.Bins")
          .getConstructors()[0].newInstance(List.of("a", "b"), List.<Object>of(1, "n"));
        outcomes.add(mapper.forward(source).toString());
      }
    }
    assertEquals("BinsView[any=[b, a], sink=[1, n]]", outcomes.get(0));
    assertEquals(outcomes.get(0), outcomes.get(1));
  }

  @Test
  @DisplayName("a converter whose result only the call's target types is checked, and refused alike on both paths")
  void aTargetTypedConverterResultIsCheckedOnBothPaths() throws Exception {
    final var lone =
      HEADER +
      "public record Lone(String name) {\n" +
      "  @Bridge static final ForwardMapper<Lone, LoneView> TO_VIEW = Telescope.mapperForward(Lone.class," +
      " LoneView.class, toOneWay(Lone::name, LoneView::name, Gen::length));\n" +
      "}\n";
    final var view = "package fb; public record LoneView(String name) {}";
    final var gen =
      "package fb;\n" +
      "public final class Gen {\n" +
      "  private Gen() {}\n" +
      "  @SuppressWarnings(\"unchecked\")\n" +
      "  public static <T> T length(final String s) { return (T) (Object) Integer.valueOf(s.length()); }\n" +
      "}\n";
    final var generated = compile(sources("fb.Lone", lone, "fb.LoneView", view, "fb.Gen", gen));
    assertTrue(generated.success(), generated::errorMessages);
    final var runtime = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      sources("fb.Lone", lone, "fb.LoneView", view, "fb.Gen", gen)
    );
    assertTrue(runtime.success(), runtime::errorMessages);
    final var refusals = new ArrayList<String>();
    for (final var compilation : List.of(generated, runtime)) {
      try (final var loader = load(compilation)) {
        @SuppressWarnings("unchecked")
        final var mapper = (ForwardMapper<Object, Object>) packageField(loader, "fb.Lone", "TO_VIEW");
        final var source = loader.loadClass("fb.Lone").getConstructors()[0].newInstance("abc");
        refusals.add(assertThrows(ClassCastException.class, () -> mapper.forward(source)).getMessage());
      }
    }
    assertEquals(
      PairingMessages.resultClassMismatch("fb.LoneView", "name", "java.lang.Integer", "java.lang.String"),
      refusals.get(0)
    );
    assertEquals(refusals.get(0), refusals.get(1));
  }

  static List<String[]> variableTypedResults() {
    return List.of(
      new String[] { "toOneWay(Lot::name, LotView::name, Gen::same)", "LotView[name=abc, any=null, names=null]" },
      new String[] { "toOneWay(Lot::name, LotView::any, Gen::length)", "LotView[name=null, any=3, names=null]" },
      new String[] { "toOneWay(Lot::name, LotView::names, Gen::listed)", "LotView[name=null, any=null, names=[abc]]" }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("variableTypedResults")
  @DisplayName(
    "a converter result its argument types, or written into Object or a class given type arguments, runs alike"
  )
  void aVariableTypedResultTheCallFixesRunsOnBothPaths(final String row, final String expected) throws Exception {
    final var lot =
      HEADER +
      "public record Lot(String name) {\n" +
      "  @Bridge static final ForwardMapper<Lot, LotView> TO_VIEW = Telescope.mapperForward(Lot.class," +
      " LotView.class, " +
      row +
      ");\n" +
      "}\n";
    final var view = "package fb; public record LotView(String name, Object any, java.util.List<String> names) {}";
    final var gen =
      "package fb;\n" +
      "public final class Gen {\n" +
      "  private Gen() {}\n" +
      "  public static <T> T same(final T t) { return t; }\n" +
      "  @SuppressWarnings(\"unchecked\")\n" +
      "  public static <T> T length(final String s) { return (T) (Object) Integer.valueOf(s.length()); }\n" +
      "  @SuppressWarnings(\"unchecked\")\n" +
      "  public static <T> T listed(final String s) { return (T) java.util.List.of(s); }\n" +
      "}\n";
    final var generated = compile(sources("fb.Lot", lot, "fb.LotView", view, "fb.Gen", gen));
    assertTrue(generated.success(), generated::errorMessages);
    final var runtime = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      sources("fb.Lot", lot, "fb.LotView", view, "fb.Gen", gen)
    );
    assertTrue(runtime.success(), runtime::errorMessages);
    for (final var compilation : List.of(generated, runtime)) {
      try (final var loader = load(compilation)) {
        @SuppressWarnings("unchecked")
        final var mapper = (ForwardMapper<Object, Object>) packageField(loader, "fb.Lot", "TO_VIEW");
        final var source = loader.loadClass("fb.Lot").getConstructors()[0].newInstance("abc");
        assertEquals(expected, mapper.forward(source).toString());
      }
    }
  }

  static List<String[]> typeVariableFills() {
    return List.of(
      new String[] {
        "Mapping.<Plain, Stamped<String>, String>constant(Stamped<String>::at, \"now\")",
        "AtConstant",
        "java.lang.String",
        "Stamped[id=p, at=now]",
      },
      new String[] {
        "Mapping.<Plain, Stamped<String>, String>compute(Stamped<String>::at, Clock::now)",
        "AtCompute",
        "java.lang.String",
        "Stamped[id=p, at=now]",
      },
      new String[] {
        "Mapping.<Plain, Stamped<StringBuilder>, StringBuilder>compute(Stamped<StringBuilder>::at, StringBuilder::new)",
        "AtCompute",
        "java.lang.StringBuilder",
        "Stamped[id=p, at=]",
      }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("typeVariableFills")
  @DisplayName("a row filling a property typed by its class's variable supplies the value's own class, on both paths")
  void aFillIntoATypeVariableSuppliesTheValuesClass(
    final String row,
    final String suffix,
    final String supplied,
    final String expected
  ) throws Exception {
    final var plain =
      HEADER +
      "public record Plain(String id) {\n" +
      "  @Bridge @SuppressWarnings({ \"rawtypes\", \"unchecked\" })\n" +
      "  static final ForwardMapper<Plain, Stamped> FILL = Telescope.mapperForward(Plain.class, Stamped.class, " +
      row +
      ");\n" +
      "}\n";
    final var stamped = "package fb; public record Stamped<T>(String id, T at) {}";
    final var clock = "package fb; public final class Clock { public static String now() { return \"now\"; } }";
    final var generated = compile(sources("fb.Plain", plain, "fb.Stamped", stamped, "fb.Clock", clock));
    assertTrue(generated.success(), generated::errorMessages);
    final var fill = generated.generated().get("fb.PlainToStampedBridge" + suffix);
    assertTrue(fill.contains("implements java.util.function.Supplier<" + supplied + ">"), fill);
    final var runtime = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      sources("fb.Plain", plain, "fb.Stamped", stamped, "fb.Clock", clock)
    );
    assertTrue(runtime.success(), runtime::errorMessages);
    for (final var compilation : List.of(generated, runtime)) {
      try (final var loader = load(compilation)) {
        @SuppressWarnings("unchecked")
        final var mapper = (ForwardMapper<Object, Object>) packageField(loader, "fb.Plain", "FILL");
        final var source = loader.loadClass("fb.Plain").getConstructors()[0].newInstance("p");
        assertEquals(expected, mapper.forward(source).toString());
      }
    }
  }

  @Test
  @DisplayName("a field initialised by a statically imported factory runs its bridge")
  void aStaticallyImportedFactoryRunsItsBridge() throws Exception {
    final var c = compile(
      sources(
        "fb.Ticket",
        "package fb;\n" +
          "import static io.github.eschizoid.telescope.Telescope.mapper;\n" +
          "import static io.github.eschizoid.telescope.mapping.Mapping.to;\n" +
          "import io.github.eschizoid.telescope.annotations.Bridge;\n" +
          "import io.github.eschizoid.telescope.conversion.Mapper;\n" +
          "public record Ticket(Long id, String title) {\n" +
          "  @Bridge public static final Mapper<Ticket, TicketView> VIEW =" +
          " mapper(Ticket.class, TicketView.class, to(Ticket::id, TicketView::ticketId));\n" +
          "  public TicketView toView() { return VIEW.forward(this); }\n" +
          "}\n",
        "fb.TicketView",
        "package fb; public record TicketView(Long ticketId, String title) {}"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    assertTrue(c.generated().containsKey("fb.TicketToTicketViewBridge"), c.generated()::toString);
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var view = (Mapper<Object, Object>) field(loader, "fb.Ticket", "VIEW");
      assertEquals(OpticNode.Runs.GENERATED, view.explain().declared().orElseThrow().runs());
      final var ticket = loader.loadClass("fb.Ticket").getConstructors()[0].newInstance(3L, "t");
      assertEquals("TicketView[ticketId=3, title=t]", ticket.getClass().getMethod("toView").invoke(ticket).toString());
    }
  }

  private static final String ITEM_MAP =
    "package fb;\n" +
    "public final class ItemMap {\n" +
    "  public static ItemDto forward(final Item i) { return new ItemDto(i.name().toUpperCase()); }\n" +
    "  public static Item backward(final ItemDto d) { return new Item(d.label().toLowerCase()); }\n" +
    "}\n";

  private static final String GENERIC_MAP =
    "package fb;\n" +
    "public final class ItemMap {\n" +
    "  public static <T extends Item> ItemDto forward(final T i) { return new ItemDto(i.name() + \"!\"); }\n" +
    "  public static Item backward(final ItemDto d) { return new Item(d.label().replace(\"!\", \"\")); }\n" +
    "}\n";

  private static final String WRAPPING_MAP =
    "package fb;\n" +
    "public final class ItemMap {\n" +
    "  public static long forward(final Integer i) { return i * 10L; }\n" +
    "  public static Integer backward(final Long l) { return (int) (l / 10); }\n" +
    "}\n";

  private static final String PRIMITIVE_MAP =
    "package fb;\n" +
    "public final class ItemMap {\n" +
    "  public static long forward(final int i) { return i * 10L; }\n" +
    "  public static int backward(final long l) { return (int) (l / 10); }\n" +
    "}\n";

  private static final String INFERRED_MAP =
    "package fb;\n" +
    "public final class ItemMap {\n" +
    "  @SuppressWarnings(\"unchecked\")\n" +
    "  public static <D> D forward(final Item i) { return (D) new ItemDto(i.name() + \"?\"); }\n" +
    "  public static Item backward(final ItemDto d) { return new Item(d.label().replace(\"?\", \"\")); }\n" +
    "}\n";

  static List<String[]> viaShapes() {
    final var items = "java.util.Arrays.asList(new Item(\"a\"), null, new Item(\"b\"))";
    return List.of(
      new String[] { "a scalar", "Item", "ItemDto", ITEM_MAP, "new Item(\"a\")" },
      new String[] { "a null scalar", "Item", "ItemDto", ITEM_MAP, "null" },
      new String[] { "a List", "java.util.List<Item>", "java.util.List<ItemDto>", ITEM_MAP, items },
      new String[] {
        "a Set",
        "java.util.Set<Item>",
        "java.util.Set<ItemDto>",
        ITEM_MAP,
        "new java.util.LinkedHashSet<>(" + items + ")",
      },
      new String[] {
        "an Optional",
        "java.util.Optional<Item>",
        "java.util.Optional<ItemDto>",
        ITEM_MAP,
        "java.util.Optional.of(new Item(\"a\"))",
      },
      new String[] {
        "an empty Optional",
        "java.util.Optional<Item>",
        "java.util.Optional<ItemDto>",
        ITEM_MAP,
        "java.util.Optional.empty()",
      },
      new String[] {
        "a Map's values",
        "java.util.Map<String, Item>",
        "java.util.Map<String, ItemDto>",
        ITEM_MAP,
        "new java.util.TreeMap<>(java.util.Map.of(\"k\", new Item(\"a\"), \"m\", new Item(\"b\")))",
      },
      new String[] {
        "a generic forward over a List",
        "java.util.List<Item>",
        "java.util.List<ItemDto>",
        GENERIC_MAP,
        items,
      },
      new String[] {
        "a forward whose result its call infers, over a List",
        "java.util.List<Item>",
        "java.util.List<ItemDto>",
        INFERRED_MAP,
        items,
      },
      new String[] {
        "a forward returning a primitive, over a List",
        "java.util.List<Integer>",
        "java.util.List<Long>",
        WRAPPING_MAP,
        "java.util.Arrays.asList(1, null, 3)",
      },
      new String[] {
        "a forward taking a primitive, over a List of its wrapper",
        "java.util.List<Integer>",
        "java.util.List<Long>",
        PRIMITIVE_MAP,
        "java.util.Arrays.asList(1, null, 3)",
      },
      new String[] {
        "a forward taking a primitive, over an Optional of its wrapper",
        "java.util.Optional<Integer>",
        "java.util.Optional<Long>",
        PRIMITIVE_MAP,
        "java.util.Optional.of(4)",
      },
      new String[] {
        "a forward taking a primitive, over a Map's values of its wrapper",
        "java.util.Map<String, Integer>",
        "java.util.Map<String, Long>",
        PRIMITIVE_MAP,
        "new java.util.TreeMap<>(java.util.Map.of(\"k\", 2, \"m\", 5))",
      }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("viaShapes")
  @DisplayName("a via row of two methods converts the same on both paths, element by element in a container")
  void aViaRowOfTwoMethodsAgreesOnBothPaths(
    final String shape,
    final String sourceType,
    final String targetType,
    final String itemMap,
    final String sample
  ) throws Exception {
    final var rows = "via(Src::value, Dst::value, ItemMap::forward, ItemMap::backward)";
    final var c = compile(
      sources(
        "fb.Item",
        "package fb; public record Item(String name) {}",
        "fb.ItemDto",
        "package fb; public record ItemDto(String label) {}",
        "fb.ItemMap",
        itemMap,
        "fb.Src",
        "package fb; public record Src(" + sourceType + " value) {}",
        "fb.Dst",
        "package fb; public record Dst(" + targetType + " value) {}",
        "fb.Fields",
        HEADER +
          "public final class Fields {\n" +
          "  @Bridge public static final Mapper<Src, Dst> GEN = Telescope.mapper(Src.class, Dst.class, " +
          rows +
          ");\n}\n",
        "fb.Plain",
        HEADER +
          "public final class Plain {\n" +
          "  public static final Mapper<Src, Dst> RUN = Telescope.mapper(Src.class, Dst.class, " +
          rows +
          ");\n}\n",
        "fb.Probe",
        "package fb;\n" +
          "public final class Probe {\n" +
          "  public static String run(final io.github.eschizoid.telescope.conversion.Mapper<Src, Dst> m) {\n" +
          "    final var d = m.forward(new Src(" +
          sample +
          "));\n" +
          "    return d + \" | \" + m.backward(d);\n" +
          "  }\n" +
          "}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    assertTrue(c.generated().get("fb.SrcToDstBridge").contains("fb.ItemMap.forward("), shape);
    try (final var loader = load(c)) {
      final var run = loader.loadClass("fb.Probe").getMethod("run", Mapper.class);
      final var generated = (Mapper<?, ?>) field(loader, "fb.Fields", "GEN");
      final var runtime = (Mapper<?, ?>) field(loader, "fb.Plain", "RUN");
      assertEquals(OpticNode.Runs.GENERATED, generated.explain().declared().orElseThrow().runs(), shape);
      assertTrue(runtime.explain().declared().isEmpty(), shape);
      assertEquals(run.invoke(null, runtime), run.invoke(null, generated), shape);
    }
  }

  private static final String SHAPES =
    "package fb;\n" + "public sealed interface Shape3 permits Circle3, Square3 { String name(); }\n";

  private static final String SHAPE_MAP =
    "package fb;\n" +
    "public final class ShapeMap {\n" +
    "  public static ShapeDto3 forward(final Shape3 s) { return new ShapeDto3(s.name()); }\n" +
    "  public static Circle3 backward(final ShapeDto3 d) { return new Circle3(d.name()); }\n" +
    "}\n";

  private static final String GENERIC_SHAPE_MAP =
    "package fb;\n" +
    "public final class ShapeMap {\n" +
    "  public static <T extends Shape3> ShapeDto3 forward(final T s) { return new ShapeDto3(s.name()); }\n" +
    "  public static Circle3 backward(final ShapeDto3 d) { return new Circle3(d.name()); }\n" +
    "}\n";

  private static final String WHOLE_MAP =
    "package fb;\n" +
    "public final class ShapeMap {\n" +
    "  public static java.util.List<ShapeDto3> forward(final java.util.List<Circle3> s) {\n" +
    "    return s.stream().map(c -> new ShapeDto3(c.name())).toList();\n" +
    "  }\n" +
    "  public static java.util.ArrayList<Circle3> backward(final java.util.List<ShapeDto3> d) {\n" +
    "    return new java.util.ArrayList<>(d.stream().map(x -> new Circle3(x.name())).toList());\n" +
    "  }\n" +
    "}\n";

  static List<String[]> viaClassTakenShapes() {
    final var mixed = "java.util.List.of(new Circle3(\"a\"), new Square3(\"b\"))";
    final var circles = "java.util.List.of(new Circle3(\"a\"), new Circle3(\"b\"))";
    return List.of(
      new String[] {
        "a narrowed forward over a scalar",
        "Shape3",
        "ShapeDto3",
        SHAPE_MAP,
        "new Square3(\"a\")",
        "Shape3",
      },
      new String[] {
        "a narrowed forward over a list of the interface",
        "java.util.List<Shape3>",
        "java.util.List<ShapeDto3>",
        SHAPE_MAP,
        mixed,
        "Shape3",
      },
      new String[] {
        "a narrowed forward over a list of the interface holding one subclass",
        "java.util.List<Shape3>",
        "java.util.List<ShapeDto3>",
        SHAPE_MAP,
        circles,
        "Shape3",
      },
      new String[] {
        "a narrowed whole-container forward",
        "java.util.List<Circle3>",
        "java.util.List<ShapeDto3>",
        WHOLE_MAP,
        circles,
        "List",
      },
      new String[] {
        "a forward of a supertype over a list of the class backward returns",
        "java.util.List<Circle3>",
        "java.util.List<ShapeDto3>",
        SHAPE_MAP,
        circles,
        "",
      },
      new String[] {
        "a generic forward over a list of the class backward returns",
        "java.util.List<Circle3>",
        "java.util.List<ShapeDto3>",
        GENERIC_SHAPE_MAP,
        circles,
        "",
      }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("viaClassTakenShapes")
  @DisplayName("a via row of two methods converts on both paths, or is refused on both in the same words")
  void aViaRowOfTwoMethodsAgreesOnTheClassItTakes(
    final String shape,
    final String sourceType,
    final String targetType,
    final String shapeMap,
    final String sample,
    final String refusedFor
  ) throws Exception {
    final var model = new String[] {
      "fb.Shape3",
      SHAPES,
      "fb.Circle3",
      "package fb; public record Circle3(String name) implements Shape3 {}",
      "fb.Square3",
      "package fb; public record Square3(String name) implements Shape3 {}",
      "fb.ShapeDto3",
      "package fb; public record ShapeDto3(String name) {}",
      "fb.ShapeMap",
      shapeMap,
      "fb.Src",
      "package fb; public record Src(" + sourceType + " value) {}",
      "fb.Dst",
      "package fb; public record Dst(" + targetType + " value) {}",
      "fb.Plain",
      HEADER +
      "public final class Plain {\n" +
      "  public static Mapper<Src, Dst> build() { return Telescope.mapper(Src.class, Dst.class," +
      " via(Src::value, Dst::value, ShapeMap::forward, ShapeMap::backward)); }\n" +
      "  public static String run(final Mapper<Src, Dst> m) {\n" +
      "    final var d = m.forward(new Src(" +
      sample +
      "));\n" +
      "    return d + \" | \" + m.backward(d);\n" +
      "  }\n" +
      "}\n",
    };
    final var fields = new String[] {
      "fb.Fields",
      HEADER +
      "public final class Fields {\n" +
      "  @Bridge public static final Mapper<Src, Dst> GEN = Telescope.mapper(Src.class, Dst.class," +
      " via(Src::value, Dst::value, ShapeMap::forward, ShapeMap::backward));\n}\n",
    };
    final var all = new ArrayList<>(List.of(model));
    all.addAll(List.of(fields));
    final var generated = compile(sources(all.toArray(String[]::new)));
    final var runtime = ProcessorHarness.compileFully(List.of(), List.of(), sources(model));
    assertTrue(runtime.success(), runtime::errorMessages);
    try (final var loader = load(runtime)) {
      final var build = loader.loadClass("fb.Plain").getMethod("build");
      if (!refusedFor.isEmpty()) {
        final var words =
          "which is not one; have backward return " + refusedFor + ", or name the class: via(src, tgt, Hw.class)";
        assertFalse(generated.success(), shape);
        assertTrue(generated.hasError(words), generated::errorMessages);
        assertTrue(
          generated.hasError("In the rows of @Bridge field Fields.GEN: Deep map Src"),
          generated::errorMessages
        );
        final var refused = assertThrows(InvocationTargetException.class, () -> build.invoke(null));
        assertTrue(refused.getCause().getMessage().contains(words), refused.getCause()::getMessage);
        final var shared = refused.getCause().getMessage().substring(refused.getCause().getMessage().indexOf(": ") + 2);
        assertTrue(generated.hasError(shared), () -> shared + "\nvs\n" + generated.errorMessages());
        return;
      }
      assertTrue(generated.success(), generated::errorMessages);
      final var run = loader.loadClass("fb.Plain").getMethod("run", Mapper.class);
      final var fromRuntime = run.invoke(null, build.invoke(null));
      try (final var generatedLoader = load(generated)) {
        final var field = (Mapper<?, ?>) field(generatedLoader, "fb.Fields", "GEN");
        assertEquals(OpticNode.Runs.GENERATED, field.explain().declared().orElseThrow().runs(), shape);
        final var runGenerated = generatedLoader.loadClass("fb.Plain").getMethod("run", Mapper.class);
        assertEquals(fromRuntime, runGenerated.invoke(null, field), shape);
      }
    }
  }

  static List<String[]> viaClassShapes() {
    final var items = "java.util.Arrays.asList(new Item(\"a\"), null, new Item(\"b\"))";
    return List.of(
      new String[] {
        "each element through the class's element methods",
        "java.util.List<Item>",
        "java.util.List<ItemDto>",
        "  public static ItemDto forward(final Item i) { return new ItemDto(i.name().toUpperCase()); }\n" +
        "  public static Item backward(final ItemDto d) { return new Item(d.label().toLowerCase()); }\n",
        items,
      },
      new String[] {
        "a scalar through the overload its value binds",
        "Item",
        "ItemDto",
        "  public static ItemDto forward(final Item i) { return new ItemDto(i.name() + \"!\"); }\n" +
        "  public static ItemDto forward(final Object o) { return new ItemDto(\"o\"); }\n" +
        "  public static Item backward(final ItemDto d) { return new Item(d.label()); }\n",
        "new Item(\"a\")",
      },
      new String[] {
        "a backward returning a class forward does not take",
        "Shape4",
        "ItemDto",
        "  public static ItemDto forward(final Named4 n) { return new ItemDto(n.name()); }\n" +
        "  public static Shape4 backward(final ItemDto d) { return new NamedShape4(d.label()); }\n",
        "new NamedShape4(\"a\")",
      },
      new String[] {
        "a generic forward returning its own variable, with a generic backward",
        "java.util.List<String>",
        "java.util.List<String>",
        "  public static <T> T forward(final T t) { return t; }\n" +
        "  public static <T> T backward(final T t) { return t; }\n",
        "java.util.List.of(\"a\", \"b\")",
      },
      new String[] {
        "a class with element and whole-container overloads",
        "java.util.List<Item>",
        "java.util.List<ItemDto>",
        "  public static ItemDto forward(final Item i) { return new ItemDto(\"e:\" + i.name()); }\n" +
        "  public static java.util.List<ItemDto> forward(final java.util.List<Item> l) {\n" +
        "    return l.stream().map(i -> new ItemDto(\"w:\" + i.name())).toList();\n" +
        "  }\n" +
        "  public static Item backward(final ItemDto d) { return new Item(\"e:\" + d.label()); }\n" +
        "  public static java.util.List<Item> backward(final java.util.List<ItemDto> l) {\n" +
        "    return l.stream().map(d -> new Item(\"w:\" + d.label())).toList();\n" +
        "  }\n",
        "java.util.List.of(new Item(\"a\"))",
      },
      new String[] {
        "a primitive property through the overload taking the primitive",
        "int",
        "long",
        "  public static long forward(final int i) { return i * 10L; }\n" +
        "  public static long forward(final Integer i) { return -1L; }\n" +
        "  public static int backward(final long l) { return (int) (l / 10); }\n",
        "3",
      }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("viaClassShapes")
  @DisplayName("a via row naming a class converts the same on both paths")
  void aViaRowNamingAClassAgreesOnBothPaths(
    final String shape,
    final String sourceType,
    final String targetType,
    final String methods,
    final String sample
  ) throws Exception {
    agreesOnBothPaths(
      shape,
      sourceType,
      targetType,
      List.of("fb.Hw", "package fb; public final class Hw {\n" + methods + "}\n"),
      "Hw",
      sample
    );
  }

  static List<Object[]> classesTheBridgeCalls() {
    return List.of(
      new Object[] {
        "a package-private overload of a class in another package is one the bridge cannot call",
        List.of(
          "fb.other.OtherHw",
          "package fb.other; import fb.Item; import fb.ItemDto; public final class OtherHw {\n" +
            "  static ItemDto forward(final Item i) { return new ItemDto(\"other:\" + i.name()); }\n" +
            "  public static ItemDto forward(final Object o) { return new ItemDto(\"obj:\" + o); }\n" +
            "  public static Item backward(final ItemDto d) { return new Item(d.label()); }\n}\n"
        ),
        "fb.other.OtherHw",
      },
      new Object[] {
        "a package-private static a class inherits in the row's package is one the bridge calls",
        List.of(
          "fb.HwBase",
          "package fb; class HwBase {\n" +
            "  static ItemDto forward(final Item i) { return new ItemDto(\"base:\" + i.name()); }\n" +
            "  static Item backward(final ItemDto d) { return new Item(\"base:\" + d.label()); }\n}\n",
          "fb.Hw",
          "package fb; public final class Hw extends HwBase {}\n"
        ),
        "Hw",
      },
      new Object[] {
        "a static a subclass declares hides the superclass's of the same parameter",
        List.of(
          "fb.HwBase",
          "package fb; public class HwBase {\n" +
            "  public static ItemDto forward(final Item i) { return new ItemDto(\"base:\" + i.name()); }\n" +
            "  public static Item backward(final ItemDto d) { return new Item(\"base:\" + d.label()); }\n}\n",
          "fb.Hw",
          "package fb; public final class Hw extends HwBase {\n" +
            "  public static ItemDto forward(final Item i) { return new ItemDto(\"sub:\" + i.name()); }\n}\n"
        ),
        "Hw",
      }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("classesTheBridgeCalls")
  @DisplayName("a via row naming a class chooses among the methods the generated bridge can call, on both paths")
  void aViaRowChoosesTheMethodsTheBridgeCanCall(final String shape, final List<String> classes, final String using)
    throws Exception {
    agreesOnBothPaths(shape, "Item", "ItemDto", classes, using, "new Item(\"a\")");
  }

  /**
   * Converts {@code sample} through a via row naming {@code using}, one of {@code classes}, built
   * on the runtime path and generated from a {@code @Bridge} field, and holds the two results
   * equal.
   */
  private static void agreesOnBothPaths(
    final String shape,
    final String sourceType,
    final String targetType,
    final List<String> classes,
    final String using,
    final String sample
  ) throws Exception {
    final var modelList = new ArrayList<>(
      List.of(
        "fb.Item",
        "package fb; public record Item(String name) {}",
        "fb.ItemDto",
        "package fb; public record ItemDto(String label) {}",
        "fb.Named4",
        "package fb; public interface Named4 { String name(); }",
        "fb.Shape4",
        "package fb; public class Shape4 {\n" +
          "  private final String name;\n" +
          "  public Shape4(final String name) { this.name = name; }\n" +
          "  public String name() { return name; }\n" +
          "  @Override public String toString() { return getClass().getSimpleName() + \"(\" + name + \")\"; }\n}\n",
        "fb.NamedShape4",
        "package fb; public class NamedShape4 extends Shape4 implements Named4 {\n" +
          "  public NamedShape4(final String name) { super(name); }\n}\n",
        "fb.Src",
        "package fb; public record Src(" + sourceType + " value) {}",
        "fb.Dst",
        "package fb; public record Dst(" + targetType + " value) {}",
        "fb.Plain",
        HEADER +
          "public final class Plain {\n" +
          "  public static Mapper<Src, Dst> build() {" +
          " return Telescope.mapper(Src.class, Dst.class, via(Src::value, Dst::value, " +
          using +
          ".class)); }\n" +
          "  public static String run(final Mapper<Src, Dst> m) {\n" +
          "    final var d = m.forward(new Src(" +
          sample +
          "));\n" +
          "    return d + \" | \" + m.backward(d);\n" +
          "  }\n" +
          "}\n"
      )
    );
    modelList.addAll(classes);
    final var model = modelList.toArray(String[]::new);
    final var all = new ArrayList<>(List.of(model));
    all.addAll(
      List.of(
        "fb.Fields",
        HEADER +
          "public final class Fields {\n" +
          "  @Bridge public static final Mapper<Src, Dst> GEN =" +
          " Telescope.mapper(Src.class, Dst.class, via(Src::value, Dst::value, " +
          using +
          ".class));\n}\n"
      )
    );
    final var generated = compile(sources(all.toArray(String[]::new)));
    assertTrue(generated.success(), generated::errorMessages);
    final var runtime = ProcessorHarness.compileFully(List.of(), List.of(), sources(model));
    assertTrue(runtime.success(), runtime::errorMessages);
    final Object fromRuntime;
    try (final var loader = load(runtime)) {
      final var plain = loader.loadClass("fb.Plain");
      fromRuntime = plain.getMethod("run", Mapper.class).invoke(null, plain.getMethod("build").invoke(null));
    }
    try (final var loader = load(generated)) {
      final var field = (Mapper<?, ?>) field(loader, "fb.Fields", "GEN");
      assertEquals(OpticNode.Runs.GENERATED, field.explain().declared().orElseThrow().runs(), shape);
      assertEquals(fromRuntime, loader.loadClass("fb.Plain").getMethod("run", Mapper.class).invoke(null, field), shape);
    }
  }

  static List<String[]> classesARowCannotChooseFrom() {
    return List.of(
      new String[] {
        "an element method that cannot be chosen",
        "java.util.List<Item5>",
        "java.util.List<ItemDto5>",
        "  public static ItemDto5 forward(final Named5 n) { return new ItemDto5(n.name()); }\n" +
        "  public static ItemDto5 forward(final Labelled5 l) { return new ItemDto5(l.name()); }\n" +
        "  public static Item5 backward(final ItemDto5 d) { return new Item5(d.label()); }\n",
        "Deep map Src → Dst: via(...) of 'value': fb.Hw5's forward has several overloads that take an element of" +
        " java.util.List<fb.Item5>, so the one an element's class calls for cannot be chosen before the element is seen",
      },
      new String[] {
        "no forward",
        "Item5",
        "ItemDto5",
        "  public static Item5 backward(final ItemDto5 d) { return new Item5(d.label()); }\n",
        "Deep map Src → Dst: via(...) of 'value': fb.Hw5 declares no forward",
      },
      new String[] {
        "an instance forward",
        "Item5",
        "ItemDto5",
        "  public ItemDto5 forward(final Item5 i) { return new ItemDto5(i.name()); }\n" +
        "  public static Item5 backward(final ItemDto5 d) { return new Item5(d.label()); }\n",
        "Deep map Src → Dst: via(...) of 'value': fb.Hw5 declares no forward",
      },
      new String[] {
        "a forward no value of the property reaches through one overload",
        "Named5",
        "ItemDto5",
        "  public static ItemDto5 forward(final Item5 i) { return new ItemDto5(i.name()); }\n" +
        "  public static ItemDto5 forward(final Other5 o) { return new ItemDto5(o.name()); }\n" +
        "  public static Named5 backward(final ItemDto5 d) { return new Item5(d.label()); }\n",
        "Deep map Src → Dst: via(...) of 'value': fb.Hw5's forward takes no value of fb.Named5",
      },
      new String[] {
        "an array parameter the property never holds",
        "Item5",
        "ItemDto5",
        "  public static ItemDto5 forward(final Item5... i) { return new ItemDto5(i[0].name()); }\n" +
        "  public static Item5 backward(final ItemDto5 d) { return new Item5(d.label()); }\n",
        "Deep map Src → Dst: via row hands the source's 'value' to a mapper of Item5[], but it never holds a" +
        " Item5[]",
      }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("classesARowCannotChooseFrom")
  @DisplayName("a via row naming a class it cannot choose methods of is refused on both paths in the same words")
  void aViaRowNamingAClassItCannotChooseFromIsRefusedAlike(
    final String shape,
    final String sourceType,
    final String targetType,
    final String methods,
    final String words
  ) throws Exception {
    final var model = new String[] {
      "fb.Named5",
      "package fb; public interface Named5 { String name(); }",
      "fb.Labelled5",
      "package fb; public interface Labelled5 { String name(); }",
      "fb.Item5",
      "package fb; public record Item5(String name) implements Named5, Labelled5 {}",
      "fb.Other5",
      "package fb; public record Other5(String name) implements Named5 {}",
      "fb.ItemDto5",
      "package fb; public record ItemDto5(String label) {}",
      "fb.Hw5",
      "package fb; public final class Hw5 {\n" + methods + "}\n",
      "fb.Src",
      "package fb; public record Src(" + sourceType + " value) {}",
      "fb.Dst",
      "package fb; public record Dst(" + targetType + " value) {}",
      "fb.Plain",
      HEADER +
      "public final class Plain {\n" +
      "  public static Mapper<Src, Dst> build() {" +
      " return Telescope.mapper(Src.class, Dst.class, via(Src::value, Dst::value, Hw5.class)); }\n}\n",
    };
    final var all = new ArrayList<>(List.of(model));
    all.addAll(
      List.of(
        "fb.Fields",
        HEADER +
          "public final class Fields {\n" +
          "  @Bridge public static final Mapper<Src, Dst> GEN =" +
          " Telescope.mapper(Src.class, Dst.class, via(Src::value, Dst::value, Hw5.class));\n}\n"
      )
    );
    final var generated = compile(sources(all.toArray(String[]::new)));
    assertFalse(generated.success(), shape);
    final var refusals = generated
      .errors()
      .stream()
      .map(d -> d.getMessage(Locale.ROOT).replaceFirst("^In the rows of @Bridge field Fields\\.GEN: ", ""))
      .toList();
    final var runtime = ProcessorHarness.compileFully(List.of(), List.of(), sources(model));
    assertTrue(runtime.success(), runtime::errorMessages);
    try (final var loader = load(runtime)) {
      final var refused = assertThrows(InvocationTargetException.class, () ->
        loader.loadClass("fb.Plain").getMethod("build").invoke(null)
      );
      final var said = refused.getCause().getMessage();
      assertTrue(said.startsWith(words), () -> shape + ": " + said);
      assertTrue(
        refusals.contains(said),
        () -> shape + ": the runtime says\n  " + said + "\nthe processor\n  " + refusals
      );
    }
  }

  static List<String[]> rowsNamingAGeneratedBridge() {
    return List.of(
      new String[] { "a via row", "via(Src::value, Dst::value, ItemBridge::forward, ItemBridge::backward)" },
      new String[] { "a to row", "to(Src::value, Dst::value, ItemBridge::forward, ItemBridge::backward)" }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rowsNamingAGeneratedBridge")
  @DisplayName("a row naming a bridge the same compilation generates is read once that bridge exists")
  void aRowNamingAGeneratedBridgeIsReadOnceItExists(final String name, final String row) throws Exception {
    final var c = compile(
      sources(
        "fb.Item",
        "package fb; @io.github.eschizoid.telescope.annotations.Bridge(ItemDto.class) public record Item(String label) {}",
        "fb.ItemDto",
        "package fb; public record ItemDto(String label) {}",
        "fb.Src",
        "package fb; public record Src(Item value) {}",
        "fb.Dst",
        "package fb; public record Dst(ItemDto value) {}",
        "fb.Fields",
        HEADER +
          "public final class Fields {\n" +
          "  @Bridge public static final Mapper<Src, Dst> GEN = Telescope.mapper(Src.class, Dst.class, " +
          row +
          ");\n}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    assertTrue(c.generated().containsKey("fb.SrcToDstBridge"), () -> name + ": " + c.generated().keySet());
    try (final var loader = load(c)) {
      final var generated = (Mapper<?, ?>) field(loader, "fb.Fields", "GEN");
      assertEquals(OpticNode.Runs.GENERATED, generated.explain().declared().orElseThrow().runs(), name);
    }
  }

  static List<String[]> viaMethodRefusals() {
    return List.of(
      new String[] { "methods with other names", "Conv2::up, Conv2::down" },
      new String[] { "a backward of another class", "ItemMap::forward, Conv2::down" },
      new String[] { "a backward of another name", "ItemMap::forward, ItemMap::back" },
      new String[] { "an instance method", "Item::toDto, ItemMap::backward" },
      new String[] { "a backward of the same name on another class", "ItemMap::forward, Other::backward" }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("viaMethodRefusals")
  @DisplayName("a via row of two methods in a @Bridge mapper takes only one class's static forward and backward")
  void aViaRowOfOtherMethodsIsRefused(final String name, final String methods) {
    final var c = compile(
      sources(
        "fb.Item",
        "package fb; public record Item(String name) { public ItemDto toDto() { return new ItemDto(name); } }",
        "fb.ItemDto",
        "package fb; public record ItemDto(String label) {}",
        "fb.ItemMap",
        ITEM_MAP.replace(
          "  public static Item backward(",
          "  public static Item back(final ItemDto d) { return new Item(d.label()); }\n  public static Item backward("
        ),
        "fb.Conv2",
        "package fb; public final class Conv2 {\n" +
          "  public static ItemDto up(final Item i) { return new ItemDto(i.name()); }\n" +
          "  public static Item down(final ItemDto d) { return new Item(d.label()); }\n}\n",
        "fb.Other",
        "package fb; public final class Other {\n" +
          "  public static Item backward(final ItemDto d) { return new Item(d.label()); }\n}\n",
        "fb.Src",
        "package fb; public record Src(Item value) {}",
        "fb.Dst",
        "package fb; public record Dst(ItemDto value) {}",
        "fb.Fields",
        HEADER +
          "public final class Fields {\n" +
          "  @Bridge public static final Mapper<Src, Dst> GEN = Telescope.mapper(Src.class, Dst.class," +
          " via(Src::value, Dst::value, " +
          methods +
          "));\n}\n"
      )
    );
    assertFalse(c.success(), name);
    assertTrue(c.hasError("takes the static forward and backward methods of one class"), c::errorMessages);
  }

  @Test
  @DisplayName("a via row of two methods converts each element as the runtime path does, and refuses other methods")
  void aViaRowOfTwoMethodsConvertsEachElement() throws Exception {
    final var c = compile(
      sources(
        "fb.Item",
        "package fb; public record Item(String name) {}",
        "fb.ItemDto",
        "package fb; public record ItemDto(String label) {}",
        "fb.ItemMap",
        ITEM_MAP,
        "fb.Src",
        "package fb; public record Src(java.util.List<Item> value) {}",
        "fb.Dst",
        "package fb; public record Dst(java.util.List<ItemDto> value) {}",
        "fb.Fields",
        HEADER +
          "public final class Fields {\n" +
          "  @Bridge public static final Mapper<Src, Dst> GEN = Telescope.mapper(Src.class, Dst.class," +
          " via(Src::value, Dst::value, ItemMap::forward, ItemMap::backward));\n}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var generated = (Mapper<Object, Object>) field(loader, "fb.Fields", "GEN");
      final var item = loader.loadClass("fb.Item").getConstructors()[0];
      final var src = loader
        .loadClass("fb.Src")
        .getConstructors()[0].newInstance(Arrays.asList(item.newInstance("a"), null));
      assertEquals("Dst[value=[ItemDto[label=A], null]]", generated.forward(src).toString());
    }
    final var named = compile(
      sources(
        "fb.Item",
        "package fb; public record Item(String name) {}",
        "fb.ItemDto",
        "package fb; public record ItemDto(String label) {}",
        "fb.Conv2",
        "package fb; public final class Conv2 {\n" +
          "  public static ItemDto up(final Item i) { return new ItemDto(i.name()); }\n" +
          "  public static Item down(final ItemDto d) { return new Item(d.label()); }\n}\n",
        "fb.Src",
        "package fb; public record Src(Item value) {}",
        "fb.Dst",
        "package fb; public record Dst(ItemDto value) {}",
        "fb.Fields",
        HEADER +
          "public final class Fields {\n" +
          "  @Bridge public static final Mapper<Src, Dst> GEN = Telescope.mapper(Src.class, Dst.class," +
          " via(Src::value, Dst::value, Conv2::up, Conv2::down));\n}\n"
      )
    );
    assertFalse(named.success());
    assertTrue(named.hasError("takes the static forward and backward methods of one class"), named::errorMessages);
  }

  @Test
  @DisplayName("a @Bridge field of an interface is static final without saying so, and runs its bridge")
  void anInterfaceFieldIsStaticFinal() throws Exception {
    final var c = compile(
      sources(
        "fb.Mappers",
        HEADER +
          "public interface Mappers {\n" +
          "  @Bridge ForwardMapper<User, UserDto> USER = Telescope.mapperForward(User.class," +
          " UserDto.class, drop(User::secret), toOneWay(User::handle, UserDto::handle, Handle::new));\n" +
          "  ForwardMapper<Address, AddressDto> PLAIN = Telescope.mapperForward(Address.class, AddressDto.class);\n" +
          "}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var field = (ForwardMapper<Object, Object>) field(loader, "fb.Mappers", "USER");
      assertEquals(OpticNode.Runs.GENERATED, field.explain().declared().orElseThrow().runs());
      @SuppressWarnings("unchecked")
      final var plain = (ForwardMapper<Object, Object>) field(loader, "fb.Mappers", "PLAIN");
      assertTrue(plain.explain().declared().isEmpty());
    }
  }

  @Test
  @DisplayName("a field's bridge is named by its pair, and two pairs taking one name are refused in a field's terms")
  void aBridgeNameTakenTwiceIsRefusedForTheField() {
    final var view = "package fb; public record TicketView(Long ticketId, String title) {}";
    final var named = compile(
      sources(
        "fb.TicketView",
        view,
        "fb.Ticket",
        "package fb; public record Ticket(Long ticketId, String title) {}",
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge static final Mapper<Ticket, TicketView> TICKET = Telescope.mapper(Ticket.class, TicketView.class);\n" +
          "}\n"
      )
    );
    assertTrue(named.success(), named::errorMessages);
    assertTrue(named.generated().containsKey("fb.TicketToTicketViewBridge"), named.generated()::toString);
    final var twoPackages = compile(
      sources(
        "fb.TicketView",
        view,
        "fb.a.Ticket",
        "package fb.a; public record Ticket(Long ticketId, String title) {}",
        "fb.b.Ticket",
        "package fb.b; public record Ticket(Long ticketId, String title) {}",
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge static final Mapper<fb.a.Ticket, TicketView> FIRST =" +
          " Telescope.mapper(fb.a.Ticket.class, TicketView.class);\n" +
          "  @Bridge static final Mapper<fb.b.Ticket, TicketView> SECOND =" +
          " Telescope.mapper(fb.b.Ticket.class, TicketView.class);\n" +
          "}\n"
      )
    );
    assertFalse(twoPackages.success());
    assertTrue(
      twoPackages.hasError("fb.TicketToTicketViewBridge is claimed by two different type pairs"),
      twoPackages::errorMessages
    );
    assertTrue(twoPackages.hasError("In the rows of @Bridge field Mappers."), twoPackages::errorMessages);
    assertTrue(
      twoPackages.hasError("declare one of the two in a class of another package"),
      twoPackages::errorMessages
    );
    assertFalse(twoPackages.hasError("Declare an explicit @Bridge"), twoPackages::errorMessages);
    final var classForm = compile(
      sources(
        "fb.TicketView",
        view,
        "fb.Ticket",
        "package fb; public record Ticket(Long ticketId, String title) {}",
        "fb.TicketToTicketView",
        "package fb; @io.github.eschizoid.telescope.annotations.Bridge(TicketView.class)" +
          " public record TicketToTicketView(Long ticketId, String title) {}",
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge static final Mapper<Ticket, TicketView> TICKET = Telescope.mapper(Ticket.class, TicketView.class);\n" +
          "}\n"
      )
    );
    assertFalse(classForm.success());
    assertTrue(
      classForm.hasError("fb.TicketToTicketViewBridge is claimed by two different type pairs"),
      classForm::errorMessages
    );
  }

  /** The error {@code fragment} names, which must be reported in {@code file}. */
  private static void assertErrorIn(final ProcessorHarness.Compilation c, final String fragment, final String file) {
    final var error = c
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.ERROR && d.getMessage(null).contains(fragment))
      .findFirst()
      .orElseThrow(() -> new AssertionError(c.errorMessages()));
    assertTrue(error.getSource().getName().endsWith("/" + file), () -> error.getSource().getName());
  }

  @Test
  @DisplayName("a field's bridge name taken by a class's bridge for one of two targets is refused on the field")
  void aNameTakenByAClassWithTwoTargetsIsRefusedOnTheField() {
    final var c = compile(
      sources(
        "fb.X",
        "package fb; public record X(Long ticketId) {}",
        "fb.Y",
        "package fb; public record Y(Long ticketId) {}",
        "fb.Foo",
        "package fb; @io.github.eschizoid.telescope.annotations.Bridge(X.class)" +
          " @io.github.eschizoid.telescope.annotations.Bridge(Y.class) public record Foo(Long ticketId) {}",
        "fb.other.Foo",
        "package fb.other; public record Foo(Long ticketId) {}",
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge static final Mapper<fb.other.Foo, X> FOO = Telescope.mapper(fb.other.Foo.class, X.class);\n" +
          "}\n"
      )
    );
    assertFalse(c.success());
    assertErrorIn(c, "fb.FooToXBridge is claimed by two different type pairs", "Mappers.java");
    assertTrue(c.hasError("In the rows of @Bridge field Mappers.FOO"), c::errorMessages);
    assertTrue(c.hasError("declare one of the two in a class of another package"), c::errorMessages);
    assertFalse(c.hasError("Declare an explicit @Bridge"), c::errorMessages);
  }

  @Test
  @DisplayName("a field's bridge name taken by a bridge derived for a nested pair is refused on the field")
  void aNameTakenByANestedPairsBridgeIsRefusedOnTheField() {
    final var c = compile(
      sources(
        "fb.Line",
        "package fb; public record Line(Long n) {}",
        "fb.LineDto",
        "package fb; public record LineDto(Long n) {}",
        "fb.Order",
        "package fb; public record Order(Line line) {}",
        "fb.OrderDto",
        "package fb; public record OrderDto(LineDto line) {}",
        "fb.other.Line",
        "package fb.other; public record Line(Long n) {}",
        "fb.Orders",
        HEADER +
          "public final class Orders {\n" +
          "  @Bridge static final Mapper<Order, OrderDto> ORDER = Telescope.mapper(Order.class, OrderDto.class);\n" +
          "}\n",
        "fb.Lines",
        HEADER +
          "public final class Lines {\n" +
          "  @Bridge static final Mapper<fb.other.Line, LineDto> LINE =" +
          " Telescope.mapper(fb.other.Line.class, LineDto.class);\n" +
          "}\n"
      )
    );
    assertFalse(c.success());
    assertErrorIn(c, "fb.LineToLineDtoBridge is claimed by two different type pairs", "Lines.java");
    assertTrue(c.hasError("In the rows of @Bridge field Lines.LINE"), c::errorMessages);
    assertFalse(c.hasError("Declare an explicit @Bridge"), c::errorMessages);
  }

  @Test
  @DisplayName("two fields whose rows convert through each other's bridge are refused, naming the cycle")
  void fieldsThatConvertThroughEachOtherAreRefused() {
    final var c = compile(
      sources(
        "fb.InvDto",
        "package fb; public record InvDto(Long id, LineDto line) {}",
        "fb.LineDto",
        "package fb; public record LineDto(Long id, InvDto inv) {}",
        "fb.Inv",
        HEADER +
          "public record Inv(Long id, Line line) {\n" +
          "  @Bridge static final Mapper<Inv, InvDto> DTO =" +
          " Telescope.mapper(Inv.class, InvDto.class, via(Inv::line, InvDto::line, Line.DTO));\n" +
          "}\n",
        "fb.Line",
        HEADER +
          "public record Line(Long id, Inv inv) {\n" +
          "  @Bridge static final Mapper<Line, LineDto> DTO =" +
          " Telescope.mapper(Line.class, LineDto.class, via(Line::inv, LineDto::inv, Inv.DTO));\n" +
          "}\n"
      )
    );
    assertFalse(c.success());
    assertTrue(c.hasError("whose rows convert back through this field's own"), c::errorMessages);
    assertTrue(
      c.hasError("InvToInvDtoBridge -> LineToLineDtoBridge -> InvToInvDtoBridge") ||
        c.hasError("LineToLineDtoBridge -> InvToInvDtoBridge -> LineToLineDtoBridge"),
      c::errorMessages
    );
    assertFalse(c.hasError("Lombok"), c::errorMessages);
  }

  @Test
  @DisplayName("a field's mapper is the class generated for it, each direction one call into the bridge")
  void aFieldsMapperIsTheGeneratedClass() throws Exception {
    final var c = compile(sources());
    assertTrue(c.success(), c::errorMessages);
    final var provider = c.generated().get("fb.AddressToAddressDtoBridgeProvider");
    assertTrue(
      provider.contains("static final class FieldMapper extends io.github.eschizoid.telescope.conversion.Mapper"),
      provider
    );
    assertTrue(provider.contains("return AddressToAddressDtoBridge.BRIDGE_FN.forward(source);"), provider);
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var address = (Mapper<Object, Object>) field(loader, "fb.AddressMappers", "ADDRESS");
      assertEquals("fb.AddressToAddressDtoBridgeProvider$FieldMapper", address.getClass().getName());
      final var a = loader.loadClass("fb.Address").getConstructors()[0].newInstance("Oslo", "0150");
      final var dto = loader.loadClass("fb.AddressDto").getConstructors()[0].newInstance(null, "0151");
      assertEquals(a, address.backward(address.forward(a)));
      assertEquals("Address[city=Oslo, zip=0151]", address.patch(a, dto).toString());
      assertEquals(null, address.patch(null, dto));
      assertEquals(null, address.forward(null));
      assertEquals(OpticNode.Runs.GENERATED, address.explain().declared().orElseThrow().runs());
      assertTrue(address.trace(a).toString().contains("runs generated"), () -> address.trace(a).toString());
      final var hooked = address.afterForward(d -> d);
      assertEquals(address.forward(a), hooked.forward(a));
    }
  }

  @Test
  @DisplayName("a pair is declared once: a second field, or a class @Bridge, for it is refused")
  void aPairIsDeclaredOnce() {
    final var twoFields = compile(
      sources(
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge static final Mapper<Address, AddressDto> AGAIN = Telescope.mapper(Address.class, AddressDto.class);\n" +
          "}\n"
      )
    );
    assertTrue(twoFields.hasError("already declares fb.Address -> fb.AddressDto"), twoFields::errorMessages);
    final var classToo = compile(
      sources(
        "fb.Carrier",
        "package fb;\n" +
          "@io.github.eschizoid.telescope.annotations.Bridge(source = Address.class, target = AddressDto.class)\n" +
          "public final class Carrier {}\n"
      )
    );
    assertTrue(classToo.hasError("already declares fb.Address -> fb.AddressDto"), classToo::errorMessages);
  }

  @Test
  @DisplayName("the provider records the field's class, its rows and the target's properties")
  void theProviderRecordsTheDeclaration() {
    final var c = compile(sources());
    assertTrue(c.success(), c::errorMessages);
    final var provider = c.generated().get("fb.AddressToAddressDtoBridgeProvider");
    assertTrue(provider.contains("return AddressMappers.class;"), provider);
    assertTrue(provider.contains("public List<String> rowKinds() {\n    return List.of();"), provider);
    assertTrue(provider.contains("return List.of(\"city\", \"zip\");"), provider);
    assertTrue(
      c
        .resources()
        .get("META-INF/services/io.github.eschizoid.telescope.conversion.BridgeProvider")
        .contains("fb.AddressToAddressDtoBridgeProvider")
    );
  }

  @Test
  @DisplayName("each row's kind is recorded in order, which a stale bridge is refused by")
  void eachRowsKindIsRecorded() {
    final var c = compile(
      sources(
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n  @Bridge public static final ForwardMapper<User, UserDto> USER = " +
          USER_ROWS +
          "}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    assertTrue(
      c
        .generated()
        .get("fb.UserToUserDtoBridgeProvider")
        .contains(
          "List.of(\"SameTypedTo\", \"ForwardOnlyTransformTo\", \"TypedTransformTo\", \"TypedTransformTo\"," +
            " \"ForwardOnlyTransformTo\", \"Drop\", \"Constant\", \"Compute\")"
        ),
      c.generated().get("fb.UserToUserDtoBridgeProvider")
    );
  }

  @Test
  @DisplayName("on a compiler with no tree API, each field is warned on and left to the runtime path")
  void withoutTheTreeApiTheFieldIsLeftToTheRuntime() throws Exception {
    final var c = ProcessorHarness.compileFully(
      List.of(NoTrees.wrapping(new BridgeProcessor())),
      List.of(),
      sources(
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n  @Bridge public static final ForwardMapper<User, UserDto> USER = " +
          USER_ROWS +
          "  public static final ForwardMapper<Address, AddressDto> PLAIN =" +
          " Telescope.mapperForward(Address.class, AddressDto.class);\n" +
          "}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    assertFalse(c.generated().containsKey("fb.UserToUserDtoBridge"));
    assertEquals(
      "fb.User -> fb.UserDto\n",
      c.resources().get("META-INF/telescope/unread-bridge-fields/fb.Mappers"),
      c.resources()::toString
    );
    final var warnings = c
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
      .map(d -> d.getMessage(Locale.ROOT))
      .filter(m -> m.contains("this compiler has no javac tree API"))
      .sorted()
      .toList();
    assertEquals(2, warnings.size(), warnings::toString);
    assertTrue(warnings.get(0).startsWith("@Bridge field AddressMappers.ADDRESS:"), warnings::toString);
    assertTrue(warnings.get(1).startsWith("@Bridge field Mappers.USER:"), warnings::toString);
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var mapper = (ForwardMapper<Object, Object>) field(loader, "fb.Mappers", "USER");
      final var declared = mapper.explain().declared().orElseThrow();
      assertEquals(OpticNode.Runs.RUNTIME, declared.runs());
      assertTrue(declared.why().contains("was compiled without the javac tree API"), declared::why);
      @SuppressWarnings("unchecked")
      final var plain = (ForwardMapper<Object, Object>) field(loader, "fb.Mappers", "PLAIN");
      assertTrue(plain.explain().declared().isEmpty(), "a pair no field declares carries no node");
      assertEquals(
        "UserDto[userId=7, contact=ada@example.com, name=ada, nick=lovelace, kind=web, region=eu," +
          " handle=Handle[value=@ada], address=Address[city=Paris, zip=75001]]",
        mapper.forward(user(loader, "lovelace")).toString()
      );
    }
  }

  @Test
  @DisplayName("a field's bridge is its pair's own bridge, for a runtime mapper that meets the pair nested")
  void aFieldsBridgeIsThePairsOwnBridge() throws Exception {
    final var c = compile(
      sources(
        "fb.Place",
        "package fb; public record Place(String city) {}",
        "fb.PlaceDto",
        "package fb; public record PlaceDto(String town) {}",
        "fb.Holder",
        "package fb; public record Holder(Place place) {}",
        "fb.HolderDto",
        "package fb; public record HolderDto(PlaceDto place) {}",
        "fb.Places",
        HEADER +
          "public final class Places {\n" +
          "  @Bridge public static final Mapper<Place, PlaceDto> PLACE =" +
          " Telescope.mapper(Place.class, PlaceDto.class, to(Place::city, PlaceDto::town));\n" +
          "}\n",
        "fb.Holders",
        HEADER +
          "public final class Holders {\n" +
          "  public static Mapper<Holder, HolderDto> holder() {" +
          " return Telescope.mapper(Holder.class, HolderDto.class); }\n" +
          "}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var holder = (Mapper<Object, Object>) loader.loadClass("fb.Holders").getMethod("holder").invoke(null);
      final var place = loader.loadClass("fb.Place").getConstructors()[0].newInstance("Rome");
      final var value = loader.loadClass("fb.Holder").getConstructors()[0].newInstance(place);
      assertEquals("HolderDto[place=PlaceDto[town=Rome]]", holder.forward(value).toString());
    }
  }

  @Test
  @DisplayName("the verifier leaves a nested pair a field's bridge converts to that bridge")
  void theVerifierLeavesAFieldsPairToItsBridge() throws Exception {
    final var c = compile(
      sources(
        "fb.Code",
        "package fb; public record Code(String value) {}",
        "fb.CodeDto",
        "package fb; public record CodeDto(Integer value) {}",
        "fb.Coded",
        "package fb; public record Coded(Code code) {}",
        "fb.CodedDto",
        "package fb; public record CodedDto(CodeDto code) {}",
        "fb.Codes",
        HEADER +
          "public final class Codes {\n" +
          "  @Bridge public static final Mapper<Code, CodeDto> CODE = Telescope.mapper(Code.class, CodeDto.class," +
          " to(Code::value, CodeDto::value, Integer::valueOf, String::valueOf));\n" +
          "}\n",
        "fb.Codeds",
        HEADER +
          "public final class Codeds {\n" +
          "  public static Mapper<Coded, CodedDto> coded() { return Telescope.mapper(Coded.class, CodedDto.class); }\n" +
          "}\n"
      )
    );
    assertTrue(c.success(), () ->
      c
        .errors()
        .stream()
        .map(d -> d.getSource().getName() + ":" + d.getLineNumber() + " " + d.getMessage(Locale.ROOT))
        .toList()
        .toString()
    );
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var coded = (Mapper<Object, Object>) loader.loadClass("fb.Codeds").getMethod("coded").invoke(null);
      final var code = loader.loadClass("fb.Code").getConstructors()[0].newInstance("42");
      final var value = loader.loadClass("fb.Coded").getConstructors()[0].newInstance(code);
      assertEquals("CodedDto[code=CodeDto[value=42]]", coded.forward(value).toString());
    }
  }

  @Test
  @DisplayName("a navigator of the source hops through the bridge a field declares for its pair")
  void aNavigatorHopsThroughAFieldsBridge() throws Exception {
    final var c = ProcessorHarness.compileFully(
      List.<Processor>of(new FocusProcessor(), new BridgeProcessor(), new MapperVerifierProcessor()),
      List.of(),
      sources(
        "fb.Spot",
        "package fb; @io.github.eschizoid.telescope.annotations.Focus public record Spot(String city) {}",
        "fb.SpotDto",
        "package fb; @io.github.eschizoid.telescope.annotations.Focus public record SpotDto(String town) {}",
        "fb.Spots",
        HEADER +
          "public final class Spots {\n" +
          "  @Bridge public static final Mapper<Spot, SpotDto> SPOT =" +
          " Telescope.mapper(Spot.class, SpotDto.class, to(Spot::city, SpotDto::town));\n" +
          "}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    assertTrue(
      c.generated().get("fb.SpotTelescope").contains("path.then(fb.SpotToSpotDtoBridge.BRIDGE)"),
      c.generated().get("fb.SpotTelescope")
    );
    try (final var loader = load(c)) {
      final var navigator = loader.loadClass("fb.SpotTelescope").getMethod("of").invoke(null);
      final var hop = navigator.getClass().getMethod("asSpotDto").invoke(navigator);
      final var town = hop.getClass().getMethod("town").invoke(hop);
      final var spot = loader.loadClass("fb.Spot").getConstructors()[0].newInstance("Lima");
      assertEquals("Lima", town.getClass().getMethod("read", Object.class).invoke(town, spot));
    }
  }

  private static final String TRIM_FN =
    "package fb;\n" +
    "public final class TrimFn implements io.github.eschizoid.telescope.conversion.BridgeFn<String, String> {\n" +
    "  public static final TrimFn INSTANCE = new TrimFn();\n" +
    "  public String forward(final String s) { Conv.CALLERS.add(\"forward\"); return s == null ? null : s.trim(); }\n" +
    "  public String backward(final String s) { return s == null ? null : \"[\" + s + \"]\"; }\n" +
    "}\n";

  @Test
  @DisplayName("a two-way conversion is one argument, a BridgeFn class or a field holding one, on both paths")
  void aTwoWayConversionIsOneArgument() throws Exception {
    final var rows = new String[] {
      "to(Tag::label, TagDto::label, TrimFn.class)",
      "to(Tag::label, TagDto::label, TrimFn.INSTANCE)",
    };
    for (final var row : rows) {
      final var c = compile(
        sources(
          "fb.TrimFn",
          TRIM_FN,
          "fb.Tag",
          "package fb; public record Tag(String label) {}",
          "fb.TagDto",
          "package fb; public record TagDto(String label) {}",
          "fb.Trims",
          HEADER +
            "public final class Trims {\n" +
            "  @Bridge public static final Mapper<Tag, TagDto> CITY = Telescope.mapper(Tag.class," +
            " TagDto.class, " +
            row +
            ");\n" +
            "}\n",
          "fb.RuntimeTrims",
          HEADER +
            "public final class RuntimeTrims {\n" +
            "  public static Mapper<Tag, TagDto> city() { return Telescope.mapper(Tag.class," +
            " TagDto.class, " +
            row +
            "); }\n" +
            "}\n"
        )
      );
      assertTrue(c.success(), c::errorMessages);
      try (final var loader = load(c)) {
        @SuppressWarnings("unchecked")
        final var generated = (Mapper<Object, Object>) field(loader, "fb.Trims", "CITY");
        @SuppressWarnings("unchecked")
        final var runtime = (Mapper<Object, Object>) loader.loadClass("fb.RuntimeTrims").getMethod("city").invoke(null);
        final var address = loader.loadClass("fb.Tag").getConstructors()[0].newInstance(" Oslo ");
        assertEquals(OpticNode.Runs.GENERATED, generated.explain().declared().orElseThrow().runs(), row);
        assertEquals(runtime.forward(address), generated.forward(address), row);
        assertEquals("TagDto[label=Oslo]", generated.forward(address).toString(), row);
        final var back = generated.forward(address);
        assertEquals(runtime.backward(back), generated.backward(back), row);
        assertEquals("Tag[label=[Oslo]]", generated.backward(back).toString(), row);
      }
    }
    final var refused = compile(
      sources(
        "fb.TrimFn",
        TRIM_FN,
        "fb.Tag",
        "package fb; public record Tag(String label) {}",
        "fb.TagDto",
        "package fb; public record TagDto(String label) {}",
        "fb.Trims",
        HEADER +
          "public final class Trims {\n" +
          "  @Bridge public static final Mapper<Tag, TagDto> CITY = Telescope.mapper(Tag.class," +
          " TagDto.class, to(Tag::label, TagDto::label, new TrimFn()));\n" +
          "}\n"
      )
    );
    assertTrue(
      refused.hasError("pass its class (EmailFn.class), or a static final field holding it"),
      refused::errorMessages
    );
  }

  /** The line and column, from one, at which {@code fragment} first appears in {@code code}. */
  private static long[] position(final String code, final String fragment) {
    final var at = code.indexOf(fragment);
    final var before = code.substring(0, at);
    return new long[] { before.lines().count() + (before.endsWith("\n") ? 1 : 0), at - before.lastIndexOf('\n') };
  }

  @Test
  @DisplayName("a refusal is reported at the row it refuses, by line and column, naming the fix")
  void aRefusalIsReportedAtTheRow() {
    final var code =
      HEADER +
      "public final class Mappers {\n" +
      "  @Bridge static final ForwardMapper<User, UserDto> USER = Telescope.mapperForward(User.class, UserDto.class,\n" +
      "      to(User::id, UserDto::userId),\n" +
      "      toOneWay(User::name, UserDto::name, s -> s.trim()));\n" +
      "}\n";
    final var c = compile(sources("fb.Mappers", code));
    final var refusal = c.errors().getFirst();
    final var expected = position(code, "s -> s.trim()");
    assertEquals("/fb/Mappers.java", refusal.getSource().getName());
    assertEquals(expected[0], refusal.getLineNumber(), c::errorMessages);
    assertEquals(expected[1], refusal.getColumnNumber(), c::errorMessages);
    assertTrue(refusal.getMessage(Locale.ROOT).contains("such as String::trim"), c::errorMessages);
  }

  @Test
  @DisplayName("renaming an accessor breaks the row that names it at compile time, never mis-maps it")
  void renamingAnAccessorBreaksTheRow() {
    final var code =
      HEADER +
      "public final class Mappers {\n" +
      "  @Bridge static final ForwardMapper<Moved, UserDto> MOVED = Telescope.mapperForward(Moved.class, UserDto.class,\n" +
      "      to(Moved::email, UserDto::contact));\n" +
      "}\n";
    final var c = compile(sources("fb.Moved", "package fb; public record Moved(String mail) {}", "fb.Mappers", code));
    assertFalse(c.success(), "a row naming an accessor that is gone must not compile");
    assertFalse(c.generated().containsKey("fb.MovedToUserDtoBridge"), "nothing is generated from a broken row");
    final var expected = position(code, "Moved::email");
    assertTrue(
      c
        .errors()
        .stream()
        .anyMatch(d -> d.getLineNumber() == expected[0] && d.getColumnNumber() == expected[1]),
      c::errorMessages
    );
  }

  static List<String[]> fieldRefusals() {
    return List.of(
      new String[] {
        "  @Bridge static final Telescope<User, UserDto> USER = Telescope.map(User.class, UserDto.class);",
        "must be initialised by a Telescope.mapper(...) or Telescope.mapperForward(...)",
      },
      new String[] {
        "  @Bridge(UserDto.class) static final ForwardMapper<User, UserDto> USER =" +
        " Telescope.mapperForward(User.class, UserDto.class);",
        "@Bridge on a field takes no attributes",
      },
      new String[] {
        "  @Bridge static final ForwardMapper<User, UserDto> USER =" +
        " Telescope.mapperForward(User.class, TARGET);\n  static final Class<UserDto> TARGET = UserDto.class;",
        "must name its source and target classes as class literals",
      },
      new String[] {
        "  @Bridge static final Mapper<User, UserDto> USER = Telescope.mapper(User.class, UserDto.class," +
        " toOneWay(User::name, UserDto::name, Conv::trim));",
        "Telescope.mapper(...) cannot accept a Mapping.toOneWay(...) row",
      },
      new String[] {
        "  @Bridge static final Mapper<User, UserDto> USER = Telescope.mapper(User.class, UserDto.class," +
        " to(User::name, UserDto::name), to(User::name, UserDto::nick));",
        PairingMessages.fanOutBothWays("User", "UserDto", "name", List.of("name", "nick")),
      },
      new String[] {
        "  static final class Inner {\n    @Bridge static final ForwardMapper<User, UserDto> USER =" +
        " Telescope.mapperForward(User.class, UserDto.class);\n  }",
        "must be declared in a top-level class",
      },
      new String[] {
        "  public record In(String a) {}\n  public record Out(String a) {}\n" +
        "  @Bridge static final Mapper<In, Out> M = Telescope.mapper(In.class, Out.class);",
        "@Bridge source must be a top-level type",
      },
      new String[] {
        "  @Bridge static final Mapper<Address, Shape> M = Telescope.mapper(Address.class, Shape.class);",
        "@Bridge target Shape is abstract",
      },
      new String[] {
        "  @Bridge static final Mapper<Address, Plain> M = Telescope.mapper(Address.class, Plain.class);",
        "@Bridge target Plain is an interface with no permits",
      }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fieldRefusals")
  @DisplayName("a field the processor cannot read rows from is refused by name")
  void aFieldTheProcessorCannotReadIsRefused(final String member, final String message) {
    final var c = compile(
      sources(
        "fb.Shape",
        "package fb; public abstract class Shape { public String getA() { return null; } }",
        "fb.Plain",
        "package fb; public interface Plain { String a(); }",
        "fb.Mappers",
        HEADER + "public final class Mappers {\n" + member + "\n}\n"
      )
    );
    assertFalse(c.success(), "the field must be refused");
    assertTrue(c.hasError(message), c::errorMessages);
  }

  @Test
  @DisplayName("a default computed by a supplier converts the same on both paths")
  void aSuppliedDefaultAgrees() throws Exception {
    final var rows =
      "Telescope.mapperForward(User.class, UserDto.class, toOrElseGet(User::nick, UserDto::nick, Conv::region)," +
      " toOneWay(User::handle, UserDto::handle, Handle::new))";
    final var c = compile(
      sources(
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n  @Bridge public static final ForwardMapper<User, UserDto> USER = " +
          rows +
          ";\n}\n",
        "fb.RuntimeMappers",
        HEADER +
          "public final class RuntimeMappers {\n  public static final ForwardMapper<User, UserDto> USER = " +
          rows +
          ";\n}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var generated = (ForwardMapper<Object, Object>) field(loader, "fb.Mappers", "USER");
      @SuppressWarnings("unchecked")
      final var runtime = (ForwardMapper<Object, Object>) field(loader, "fb.RuntimeMappers", "USER");
      for (final var nick : new String[] { "x", null }) {
        final var user = user(loader, nick);
        assertEquals(runtime.forward(user), generated.forward(user));
      }
      assertEquals(OpticNode.Runs.GENERATED, generated.explain().declared().orElseThrow().runs());
    }
  }

  @Test
  @DisplayName("a refusal the bridge's planning makes is reported on the field's mapper call, in its rows' terms")
  void aPlanningRefusalIsReportedOnTheCall() {
    final var code =
      HEADER +
      "public final class Mappers {\n" +
      "  @Bridge static final Mapper<Address, Wider> WIDER = Telescope.mapper(Address.class, Wider.class);\n" +
      "}\n";
    final var c = compile(
      sources(
        "fb.Wider",
        "package fb; public record Wider(String city, String zip, String extra) {}",
        "fb.Mappers",
        code
      )
    );
    assertFalse(c.success());
    final var refusal = c
      .errors()
      .stream()
      .filter(d -> d.getMessage(Locale.ROOT).startsWith("In the rows of @Bridge field Mappers.WIDER: "))
      .findFirst()
      .orElseThrow(() -> new AssertionError(c.errorMessages()));
    final var expected = position(code, "Telescope.mapper(Address.class, Wider.class)");
    assertEquals("/fb/Mappers.java", refusal.getSource().getName());
    assertEquals(expected[0], refusal.getLineNumber(), c::errorMessages);
  }

  /**
   * Converters the rows of the pairs below name, each a static method a generated bridge can call.
   */
  private static final String NUM =
    "package fb;\n" +
    "public final class Num {\n" +
    "  public static String show(final Integer i) { return \"#\" + i; }\n" +
    "  public static Integer read(final String s) { return Integer.valueOf(s.substring(1)); }\n" +
    "  public static String up(final String s) { return s.toUpperCase(); }\n" +
    "  public static String down(final String s) { return s.toLowerCase(); }\n" +
    "  public static String made() { return \"made\"; }\n" +
    "}\n";

  /**
   * A pair whose rows read a source property, by a rename or a drop, that has a target property of
   * its own name: the components of {@code Src} and {@code Tgt}, the rows, the samples each side's
   * constructor takes, and what both paths owe forward, backward and laid over the source sample. A
   * forward-only pair owes no backward or patch.
   */
  record ReadSource(
    String name,
    String src,
    String tgt,
    boolean forwardOnly,
    String rows,
    List<Object> srcSample,
    List<Object> tgtSample,
    String forward,
    String backward,
    String patch
  ) {
    @Override
    public String toString() {
      return name;
    }
  }

  static List<ReadSource> readSources() {
    return List.of(
      new ReadSource(
        "a rename beside a constant on the renamed property's namesake",
        "String n",
        "String label, String n",
        false,
        "to(Src::n, Tgt::label), constant(Tgt::n, \"k\")",
        List.of("v"),
        List.of("lbl", "w"),
        "Tgt[label=v, n=k]",
        "Src[n=lbl]",
        "Src[n=lbl]"
      ),
      new ReadSource(
        "a rename beside a value computed into the renamed property's namesake",
        "String n",
        "String label, String n",
        false,
        "to(Src::n, Tgt::label), compute(Tgt::n, Num::made)",
        List.of("v"),
        List.of("lbl", "w"),
        "Tgt[label=v, n=made]",
        "Src[n=lbl]",
        "Src[n=lbl]"
      ),
      new ReadSource(
        "a rename through a conversion beside a constant on the namesake",
        "String n",
        "String label, String n",
        false,
        "to(Src::n, Tgt::label, Num::up, Num::down), constant(Tgt::n, \"k\")",
        List.of("v"),
        List.of("LBL", "w"),
        "Tgt[label=V, n=k]",
        "Src[n=lbl]",
        "Src[n=lbl]"
      ),
      new ReadSource(
        "a rename beside a constant on a namesake of another type",
        "Integer n",
        "String label, Long n",
        false,
        "to(Src::n, Tgt::label, Num::show, Num::read), constant(Tgt::n, 5L)",
        List.of(7),
        List.of("#3", 9L),
        "Tgt[label=#7, n=5]",
        "Src[n=3]",
        "Src[n=3]"
      ),
      new ReadSource(
        "a forward-only rename with no row for the namesake",
        "String n",
        "String label, String n",
        true,
        "to(Src::n, Tgt::label)",
        List.of("v"),
        List.of(),
        "Tgt[label=v, n=null]",
        null,
        null
      ),
      new ReadSource(
        "a forward-only rename with no row for a namesake of another type",
        "Integer n",
        "String label, Long n",
        true,
        "toOneWay(Src::n, Tgt::label, Num::show)",
        List.of(7),
        List.of(),
        "Tgt[label=#7, n=null]",
        null,
        null
      ),
      new ReadSource(
        "a drop beside a constant on the dropped property's namesake",
        "String id, String n",
        "String id, String n",
        false,
        "drop(Src::n), constant(Tgt::n, \"k\")",
        List.of("i", "v"),
        List.of("j", "w"),
        "Tgt[id=i, n=k]",
        "Src[id=j, n=null]",
        "Src[id=j, n=v]"
      ),
      new ReadSource(
        "a forward-only drop with no row for the namesake",
        "String id, String n",
        "String id, String n",
        true,
        "drop(Src::n)",
        List.of("i", "v"),
        List.of(),
        "Tgt[id=i, n=null]",
        null,
        null
      )
    );
  }

  /**
   * The pair {@code Src} to {@code Tgt} with {@code Num}, and {@code Mappers} holding its mapper.
   */
  private static JavaFileObject[] readSourcePair(final ReadSource pair, final boolean bridge) {
    final var type = pair.forwardOnly() ? "ForwardMapper" : "Mapper";
    return new JavaFileObject[] {
      ProcessorHarness.source("fb.Src", "package fb; public record Src(" + pair.src() + ") {}"),
      ProcessorHarness.source("fb.Tgt", "package fb; public record Tgt(" + pair.tgt() + ") {}"),
      ProcessorHarness.source("fb.Num", NUM),
      ProcessorHarness.source(
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n  " +
          (bridge ? "@Bridge " : "") +
          "public static final " +
          type +
          "<Src, Tgt> M = Telescope." +
          (pair.forwardOnly() ? "mapperForward" : "mapper") +
          "(Src.class, Tgt.class, " +
          pair.rows() +
          ");\n}\n"
      ),
    };
  }

  /**
   * What the mapper field of {@code c} gives for the pair's samples, forward, backward and patch.
   */
  private static List<String> readSourceResults(final ProcessorHarness.Compilation c, final ReadSource pair)
    throws Exception {
    assertTrue(c.success(), c::errorMessages);
    try (final var loader = load(c)) {
      final var src = loader.loadClass("fb.Src").getConstructors()[0].newInstance(pair.srcSample().toArray());
      final var mapper = field(loader, "fb.Mappers", "M");
      if (pair.forwardOnly()) {
        @SuppressWarnings("unchecked")
        final var forward = (ForwardMapper<Object, Object>) mapper;
        return Arrays.asList(String.valueOf(forward.forward(src)), null, null);
      }
      @SuppressWarnings("unchecked")
      final var both = (Mapper<Object, Object>) mapper;
      final var tgt = loader.loadClass("fb.Tgt").getConstructors()[0].newInstance(pair.tgtSample().toArray());
      return List.of(
        String.valueOf(both.forward(src)),
        String.valueOf(both.backward(tgt)),
        String.valueOf(both.patch(src, tgt))
      );
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("readSources")
  @DisplayName("a source property a rename or a drop reads is not paired with its namesake, on either path")
  void aReadSourcePropertyIsNotPairedByName(final ReadSource pair) throws Exception {
    final var generated = compile(readSourcePair(pair, true));
    final var runtime = ProcessorHarness.compileFully(List.of(), List.of(), readSourcePair(pair, false));
    final var owed = Arrays.asList(pair.forward(), pair.backward(), pair.patch());
    assertEquals(owed, readSourceResults(generated, pair), "generated");
    assertEquals(owed, readSourceResults(runtime, pair), "runtime");
  }

  static List<String[]> unwrittenNamesakes() {
    return List.of(
      new String[] { "a rename", "String n", "String label, String n", "to(Src::n, Tgt::label)" },
      new String[] { "a drop", "String id, String n", "String id, String n", "drop(Src::n)" }
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unwrittenNamesakes")
  @DisplayName("a strict pair refuses, on both paths, the namesake of a property a row reads when no row writes it")
  void aStrictPairRefusesAnUnwrittenNamesake(final String name, final String src, final String tgt, final String rows)
    throws Exception {
    final var pair = new ReadSource(name, src, tgt, false, rows, List.of(), List.of(), null, null, null);
    final var generated = compile(readSourcePair(pair, true));
    assertFalse(generated.success(), name);
    assertTrue(generated.hasError("must expose the same field names (a bijection)"), generated::errorMessages);
    final var refusal = PairingMessages.sameNameSourceRead("Src", "Tgt", "field", "field", "n");
    final var verified = compile(readSourcePair(pair, false));
    assertTrue(verified.hasError(refusal), verified::errorMessages);
    final var runtime = ProcessorHarness.compileFully(List.of(), List.of(), readSourcePair(pair, false));
    assertTrue(runtime.success(), runtime::errorMessages);
    try (final var loader = load(runtime)) {
      final var thrown = assertThrows(ExceptionInInitializerError.class, () -> field(loader, "fb.Mappers", "M"));
      assertEquals(refusal, thrown.getCause().getMessage());
    }
  }
}
