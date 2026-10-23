package io.github.eschizoid.telescope.codegen.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.BridgeProcessor;
import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class SpFieldBridgeTest {

  private static final String HEADER =
    "package spdemo;\n" +
    "import static io.github.eschizoid.telescope.mapping.Mapping.*;\n" +
    "import io.github.eschizoid.telescope.Telescope;\n" +
    "import io.github.eschizoid.telescope.annotations.Bridge;\n" +
    "import io.github.eschizoid.telescope.conversion.*;\n" +
    "import io.github.eschizoid.telescope.mapping.*;\n";

  private static final String ROWS =
    "Telescope.mapperForward(User.class, UserDto.class,\n" +
    "      to(User::id, UserDto::userId),\n" +
    "      toOneWay(User::name, UserDto::name, Conv::lower),\n" +
    "      via(User::address, UserDto::address, AddressMappers.ADDRESS),\n" +
    "      drop(User::secret),\n" +
    "      constant(UserDto::kind, \"web\"));\n";

  private static JavaFileObject[] model(final String... extra) {
    final var base = List.of(
      ProcessorHarness.source("spdemo.Address", "package spdemo; public record Address(String city, String zip) {}"),
      ProcessorHarness.source("spdemo.AddressDto", "package spdemo; public record AddressDto(String city, String zip) {}"),
      ProcessorHarness.source(
        "spdemo.User",
        "package spdemo; public record User(long id, String email, String name, String secret, Address address) {}"
      ),
      ProcessorHarness.source(
        "spdemo.UserDto",
        "package spdemo; public record UserDto(long userId, String email, String name, String kind, AddressDto address) {}"
      ),
      ProcessorHarness.source(
        "spdemo.Conv",
        "package spdemo;\n" +
          "public final class Conv {\n" +
          "  public static final java.util.List<String> CALLERS = new java.util.ArrayList<>();\n" +
          "  public static String lower(String s) {\n" +
          "    CALLERS.add(java.util.Arrays.stream(new Throwable().getStackTrace()).map(StackTraceElement::getClassName)" +
          ".filter(n -> n.endsWith(\"Bridge\") || n.contains(\"DeepMap\")).findFirst().orElse(\"?\"));\n" +
          "    return s.toLowerCase();\n" +
          "  }\n" +
          "}\n"
      ),
      ProcessorHarness.source(
        "spdemo.AddressMappers",
        HEADER +
          "public final class AddressMappers {\n" +
          "  @Bridge public static final Mapper<Address, AddressDto> ADDRESS = Telescope.mapper(Address.class, AddressDto.class);\n" +
          "}\n"
      )
    );
    final var all = new java.util.ArrayList<>(base);
    for (var i = 0; i < extra.length; i += 2) all.add(ProcessorHarness.source(extra[i], extra[i + 1]));
    return all.toArray(JavaFileObject[]::new);
  }

  @Test
  void fieldDeclaredBridgeIsGeneratedAndDispatched() throws Exception {
    final var c = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor(), new io.github.eschizoid.telescope.codegen.MapperVerifierProcessor()),
      List.of(),
      model(
        "spdemo.Mappers",
        HEADER + "public final class Mappers {\n  @Bridge public static final ForwardMapper<User, UserDto> USER = " + ROWS + "}\n",
        "spdemo.RuntimeMappers",
        HEADER + "public final class RuntimeMappers {\n  public static final ForwardMapper<User, UserDto> USER = " + ROWS + "}\n"
      )
    );
    System.out.println("SUCCESS=" + c.success() + "\n" + c.errorMessages());
    c.generated().forEach((k, v) -> System.out.println("=== " + k + "\n" + v));
    c.resources().forEach((k, v) -> System.out.println("=== resource " + k + "\n" + v));
    assertTrue(c.success(), c::errorMessages);

    final var dir = Files.createTempDirectory("sp-field-bridge");
    for (final var e : c.classes().entrySet()) {
      final var file = dir.resolve(e.getKey().replace('.', '/') + ".class");
      Files.createDirectories(file.getParent());
      Files.write(file, e.getValue());
    }
    for (final var e : c.resources().entrySet()) {
      final var file = dir.resolve(e.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, e.getValue());
    }
    try (final var loader = new URLClassLoader(new java.net.URL[] { dir.toUri().toURL() }, getClass().getClassLoader())) {
      final var addressCls = loader.loadClass("spdemo.Address");
      final var userCls = loader.loadClass("spdemo.User");
      final var address = addressCls.getConstructors()[0].newInstance("Paris", "75001");
      final var user = userCls.getConstructors()[0].newInstance(7L, "A@B.com", "ADA", "s3cr3t", address);
      @SuppressWarnings("unchecked")
      final var generated = (ForwardMapper<Object, Object>) loader.loadClass("spdemo.Mappers").getField("USER").get(null);
      @SuppressWarnings("unchecked")
      final var runtime = (ForwardMapper<Object, Object>) loader.loadClass("spdemo.RuntimeMappers").getField("USER").get(null);
      final var conv = loader.loadClass("spdemo.Conv");
      @SuppressWarnings("unchecked")
      final var callers = (List<String>) conv.getField("CALLERS").get(null);
      final var g = generated.forward(user);
      final var r = runtime.forward(user);
      System.out.println("GENERATED=" + g + "\nRUNTIME  =" + r + "\nCALLERS=" + callers);
      assertEquals(r, g);
      assertEquals("spdemo.MappersUserBridge", callers.get(0));
      assertTrue(!callers.get(1).endsWith("Bridge"), callers::toString);
      @SuppressWarnings("unchecked")
      final var address2 = (Mapper<Object, Object>) loader.loadClass("spdemo.AddressMappers").getField("ADDRESS").get(null);
      System.out.println("ADDRESS mapper backward=" + address2.backward(address2.forward(address)));
    }
  }

  @Test
  void rowsTheBridgeCannotExpressAreRefusedByName() {
    final String[][] cases = {
      { "lambda", "Telescope.mapperForward(User.class, UserDto.class, toOneWay(User::name, UserDto::name, s -> s.trim()));" },
      { "helper", "Telescope.mapperForward(User.class, UserDto.class, Rows.ID);" },
      { "nonliteral", "Telescope.mapperForward(User.class, UserDto.class, constant(UserDto::kind, String.valueOf(1)));" },
      { "when", "Telescope.mapperForward(User.class, UserDto.class, when(u -> true, drop(User::secret)));" },
      { "instance", "Telescope.mapperForward(User.class, UserDto.class, toOneWay(User::name, UserDto::name, String::toLowerCase));" },
      { "renameConvert", "Telescope.mapperForward(User.class, UserDto.class, toOneWay(User::email, UserDto::kind, Conv::lower));" },
      { "notInline", "build();\n  static ForwardMapper<User, UserDto> build() { return Telescope.mapperForward(User.class, UserDto.class); }" },
    };
    for (final var k : cases) {
      final var c = ProcessorHarness.compileFully(
        List.of(new BridgeProcessor()),
        List.of(),
        model(
          "spdemo.Rows",
          HEADER + "final class Rows { static final Mapping<User, UserDto> ID = to(User::id, UserDto::userId); }\n",
          "spdemo.Mappers",
          HEADER + "public final class Mappers {\n  @Bridge static final ForwardMapper<User, UserDto> USER = " + k[1] + "\n}\n"
        )
      );
      System.out.println("REFUSAL " + k[0] + ": success=" + c.success() + " :: " + c.errors().stream().map(d -> d.getMessage(java.util.Locale.ROOT)).toList());
    }
  }
}
