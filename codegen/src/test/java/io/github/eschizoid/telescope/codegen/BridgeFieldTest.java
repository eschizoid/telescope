package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    assertTrue(c.generated().containsKey("fb.MappersUserBridge"), c.generated()::toString);
    assertTrue(c.generated().containsKey("fb.MappersViewBridge"), c.generated()::toString);
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
          generatedCallers.stream().allMatch(n -> n.startsWith("fb.MappersUserBridge")),
          generatedCallers::toString
        );
        assertFalse(runtimeCallers.isEmpty());
        assertTrue(
          runtimeCallers.stream().noneMatch(n -> n.startsWith("fb.MappersUserBridge")),
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
        "to(Telescope.of(User.class).field(User::address).field(Address::city), UserDto::kind)",
        "a telescope path or another expression has no generated form yet",
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
          "  static final Mapper<Address, AddressDto> RUNTIME =" +
          " Telescope.mapper(Address.class, AddressDto.class);\n" +
          "}\n",
        "fb.Admin",
        "package fb; final class Admin { static boolean is(User u) { return false; } }",
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge static final ForwardMapper<User, UserDto> USER =" +
          " Telescope.mapperForward(User.class, UserDto.class, " +
          rows.replace("User::isAdmin", "Admin::is") +
          ");\n  private static String lower(final String s) { return s; }\n}\n"
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

  @Test
  @DisplayName("another mapper of the pair in the declaring class is refused, since it would be handed the bridge")
  void anotherMapperOfThePairInTheClassIsRefused() {
    final var c = compile(
      sources(
        "fb.Mappers",
        HEADER +
          "public final class Mappers {\n" +
          "  @Bridge static final ForwardMapper<User, UserDto> USER = Telescope.mapperForward(User.class, UserDto.class," +
          " drop(User::secret));\n" +
          "  static ForwardMapper<User, UserDto> other() {" +
          " return Telescope.mapperForward(User.class, UserDto.class, to(User::id, UserDto::userId)); }\n" +
          "}\n"
      )
    );
    assertTrue(c.hasError("build this one in another class"), c::errorMessages);
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
    final var provider = c.generated().get("fb.AddressMappersAddressBridgeProvider");
    assertTrue(provider.contains("return AddressMappers.class;"), provider);
    assertTrue(provider.contains("public List<String> rowKinds() {\n    return List.of();"), provider);
    assertTrue(provider.contains("return List.of(\"city\", \"zip\");"), provider);
    assertTrue(
      c
        .resources()
        .get("META-INF/services/io.github.eschizoid.telescope.conversion.BridgeProvider")
        .contains("fb.AddressMappersAddressBridgeProvider")
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
        .get("fb.MappersUserBridgeProvider")
        .contains(
          "List.of(\"SameTypedTo\", \"ForwardOnlyTransformTo\", \"TypedTransformTo\", \"TypedTransformTo\"," +
            " \"ForwardOnlyTransformTo\", \"Drop\", \"Constant\", \"Compute\")"
        ),
      c.generated().get("fb.MappersUserBridgeProvider")
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
          "}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    assertFalse(c.generated().containsKey("fb.MappersUserBridge"));
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
      c.generated().get("fb.SpotTelescope").contains("path.then(fb.SpotsSpotBridge.BRIDGE)"),
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
    assertFalse(c.generated().containsKey("fb.MappersMovedBridge"), "nothing is generated from a broken row");
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
        "can be written into only one target",
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
}
