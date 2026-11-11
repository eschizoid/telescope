package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code <Type>Mappings} interface the processor generates for each type a {@code @Bridge}
 * field converts: a type naming it in its {@code implements} clause converts itself with {@code
 * value.toTarget()}, which calls the field's {@code forward}, and a two-way field's target converts
 * back with {@code toSource()}, which calls its {@code backward}.
 */
class MappingsInterfaceTest {

  private static final String HEADER =
    "import static io.github.eschizoid.telescope.mapping.Mapping.*;\n" +
    "import io.github.eschizoid.telescope.Telescope;\n" +
    "import io.github.eschizoid.telescope.annotations.Bridge;\n" +
    "import io.github.eschizoid.telescope.conversion.*;\n";

  private static final String INVOICE =
    "package mi;\n" +
    HEADER +
    "public record Invoice(Long id, String customer) implements InvoiceMappings {\n" +
    "  @Bridge static final Mapper<Invoice, InvoiceDto> DTO =\n" +
    "    Telescope.mapper(Invoice.class, InvoiceDto.class, to(Invoice::id, InvoiceDto::invoiceId));\n" +
    "}\n";

  private static final String INVOICE_DTO =
    "package mi; public record InvoiceDto(Long invoiceId, String customer) implements InvoiceDtoMappings {}";

  @TempDir
  Path dir;

  private static JavaFileObject[] sources(final String... pairs) {
    final var all = new ArrayList<JavaFileObject>();
    for (var i = 0; i < pairs.length; i += 2) all.add(ProcessorHarness.source(pairs[i], pairs[i + 1]));
    return all.toArray(JavaFileObject[]::new);
  }

  private static List<Processor> processors() {
    return List.of(new BridgeProcessor(), new MapperVerifierProcessor());
  }

  private static ProcessorHarness.Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(processors(), List.of(), sources);
  }

  /** A loader over the compiled classes and generated resources, as a class path holds them. */
  private URLClassLoader load(final ProcessorHarness.Compilation c) throws IOException {
    final var root = Files.createTempDirectory(dir, "classes");
    for (final var e : c.classes().entrySet()) {
      write(root.resolve(e.getKey().replace('.', '/') + ".class"), e.getValue());
    }
    for (final var e : c.resources().entrySet()) write(root.resolve(e.getKey()), e.getValue().getBytes());
    return new URLClassLoader(new URL[] { root.toUri().toURL() }, MappingsInterfaceTest.class.getClassLoader());
  }

  private static void write(final Path file, final byte[] bytes) throws IOException {
    Files.createDirectories(file.getParent());
    Files.write(file, bytes);
  }

  private static Object staticField(final ClassLoader loader, final String owner, final String name) throws Exception {
    final var field = loader.loadClass(owner).getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
  }

  private static Object call(final Object on, final String method) throws Exception {
    final var m = on.getClass().getMethod(method);
    m.setAccessible(true);
    return m.invoke(on);
  }

  private static Object construct(final ClassLoader loader, final String type, final Object... args) throws Exception {
    return loader.loadClass(type).getConstructors()[0].newInstance(args);
  }

  private static String errorOn(final ProcessorHarness.Compilation c, final String fragment) {
    return c
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.ERROR && d.getMessage(null).contains(fragment))
      .map(d -> d.getMessage(null))
      .findFirst()
      .orElseThrow(() -> new AssertionError(c.errorMessages()));
  }

  private static void assertNoWarnings(final ProcessorHarness.Compilation c) {
    assertTrue(
      c
        .diagnostics()
        .stream()
        .noneMatch(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING),
      c.diagnostics()::toString
    );
  }

  @Test
  @DisplayName("a two-way field writes a method on each side's interface, calling forward and backward")
  void aTwoWayFieldWritesBothInterfaces() {
    final var c = ProcessorHarness.compile(processors(), sources("mi.Invoice", INVOICE, "mi.InvoiceDto", INVOICE_DTO));
    assertTrue(c.success(), c::errorMessages);
    final var forward = c.generated().get("mi.InvoiceMappings");
    assertTrue(forward.contains("public interface InvoiceMappings {"), forward);
    assertTrue(
      forward.contains(
        "  default mi.InvoiceDto toInvoiceDto() {\n    return mi.Invoice.DTO.forward((mi.Invoice) (Object) this);\n  }"
      ),
      forward
    );
    final var backward = c.generated().get("mi.InvoiceDtoMappings");
    assertTrue(
      backward.contains(
        "  default mi.Invoice toInvoice() {\n    return mi.Invoice.DTO.backward((mi.InvoiceDto) (Object) this);\n  }"
      ),
      backward
    );
    assertFalse(forward.contains("@SuppressWarnings"), forward);
  }

  @Test
  @DisplayName("a record implementing its generated interface compiles with it, and toX() gives what the field gives")
  void theCallFormGivesWhatTheFieldGives() throws Exception {
    final var c = compile(sources("mi.Invoice", INVOICE, "mi.InvoiceDto", INVOICE_DTO));
    assertTrue(c.success(), c::errorMessages);
    assertNoWarnings(c);
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var field = (Mapper<Object, Object>) staticField(loader, "mi.Invoice", "DTO");
      final var invoice = construct(loader, "mi.Invoice", 7L, "Ada");
      final var dto = call(invoice, "toInvoiceDto");
      assertEquals(field.forward(invoice), dto);
      assertEquals("InvoiceDto[invoiceId=7, customer=Ada]", dto.toString());
      assertEquals(field.backward(dto), call(dto, "toInvoice"));
      assertEquals(invoice, call(dto, "toInvoice"));
    }
  }

  @Test
  @DisplayName("without the processor a type implementing the interface fails on the missing interface")
  void withoutTheProcessorTheInterfaceIsMissing() {
    final var c = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      sources("mi.Invoice", INVOICE, "mi.InvoiceDto", INVOICE_DTO)
    );
    assertFalse(c.success());
    errorOn(c, "InvoiceMappings");
  }

  @Test
  @DisplayName("a type need not implement its interface: the interface compiles beside a record that does not")
  void implementingIsOptional() throws Exception {
    final var c = compile(
      sources(
        "mi.Invoice",
        INVOICE.replace(" implements InvoiceMappings", ""),
        "mi.InvoiceDto",
        INVOICE_DTO.replace(" implements InvoiceDtoMappings", "")
      )
    );
    assertTrue(c.success(), c::errorMessages);
    assertNoWarnings(c);
    assertTrue(c.generated().containsKey("mi.InvoiceMappings"), () -> c.generated().keySet().toString());
  }

  @Test
  @DisplayName("a ForwardMapper field writes the source's method and no reverse interface")
  void aForwardMapperWritesNoReverse() throws Exception {
    final var c = compile(
      sources(
        "mi.Invoice",
        INVOICE.replace("Mapper<Invoice", "ForwardMapper<Invoice").replace(
          "Telescope.mapper(",
          "Telescope.mapperForward("
        ),
        "mi.InvoiceDto",
        INVOICE_DTO.replace(" implements InvoiceDtoMappings", "")
      )
    );
    assertTrue(c.success(), c::errorMessages);
    assertFalse(c.generated().containsKey("mi.InvoiceDtoMappings"), () -> c.generated().keySet().toString());
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var field = (ForwardMapper<Object, Object>) staticField(loader, "mi.Invoice", "DTO");
      final var invoice = construct(loader, "mi.Invoice", 7L, "Ada");
      assertEquals(field.forward(invoice), call(invoice, "toInvoiceDto"));
    }
  }

  @Test
  @DisplayName("a generic source gets a plain interface whose method casts to the field's raw source type")
  void aGenericSourceGetsAPlainInterface() throws Exception {
    final var box =
      "package mi;\n" +
      HEADER +
      "public record Box<T>(String label, T value) implements BoxMappings {\n" +
      "  @Bridge @SuppressWarnings(\"rawtypes\") static final ForwardMapper<Box, BoxDto> DTO =\n" +
      "    Telescope.mapperForward(Box.class, BoxDto.class);\n" +
      "}\n";
    final var c = compile(sources("mi.Box", box, "mi.BoxDto", "package mi; public record BoxDto(String label) {}"));
    assertTrue(c.success(), c::errorMessages);
    assertNoWarnings(c);
    final var text = c.generated().get("mi.BoxMappings");
    assertTrue(text.contains("@SuppressWarnings(\"rawtypes\")\npublic interface BoxMappings {"), text);
    assertTrue(text.contains("return mi.Box.DTO.forward((mi.Box) (Object) this);"), text);
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var field = (ForwardMapper<Object, Object>) staticField(loader, "mi.Box", "DTO");
      final var value = construct(loader, "mi.Box", "b", 3);
      assertEquals(field.forward(value), call(value, "toBoxDto"));
    }
  }

  @Test
  @DisplayName("a field on a public carrier in another package writes the method in the source's package")
  void aCarrierInAnotherPackage() throws Exception {
    final var carrier =
      "package mi.maps;\n" +
      HEADER +
      "import mi.Invoice;\n" +
      "import mi.InvoiceDto;\n" +
      "public final class Mappers {\n" +
      "  @Bridge public static final Mapper<Invoice, InvoiceDto> DTO =\n" +
      "    Telescope.mapper(Invoice.class, InvoiceDto.class, to(Invoice::id, InvoiceDto::invoiceId));\n" +
      "}\n";
    final var invoice = "package mi; public record Invoice(Long id, String customer) implements InvoiceMappings {}";
    final var c = compile(sources("mi.maps.Mappers", carrier, "mi.Invoice", invoice, "mi.InvoiceDto", INVOICE_DTO));
    assertTrue(c.success(), c::errorMessages);
    final var text = c.generated().get("mi.InvoiceMappings");
    assertTrue(text.contains("return mi.maps.Mappers.DTO.forward((mi.Invoice) (Object) this);"), text);
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var field = (Mapper<Object, Object>) staticField(loader, "mi.maps.Mappers", "DTO");
      final var value = construct(loader, "mi.Invoice", 1L, "Bo");
      final var dto = call(value, "toInvoiceDto");
      assertEquals(field.forward(value), dto);
      assertEquals(field.backward(dto), call(dto, "toInvoice"));
    }
  }

  private static final String HIDDEN_CARRIER =
    "package mi.maps;\n" +
    HEADER +
    "import mi.Invoice;\n" +
    "import mi.InvoiceDto;\n" +
    "public final class Mappers {\n" +
    "  @Bridge static final Mapper<Invoice, InvoiceDto> DTO =\n" +
    "    Telescope.mapper(Invoice.class, InvoiceDto.class, to(Invoice::id, InvoiceDto::invoiceId));\n" +
    "}\n";

  @Test
  @DisplayName("a package-private field in another package adds no method to a type that does not implement it")
  void anUnreachableFieldAddsNoMethod() {
    final var c = compile(
      sources(
        "mi.maps.Mappers",
        HIDDEN_CARRIER,
        "mi.Invoice",
        "package mi; public record Invoice(Long id, String customer) {}",
        "mi.InvoiceDto",
        "package mi; public record InvoiceDto(Long invoiceId, String customer) {}"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    final var text = c.generated().get("mi.InvoiceMappings");
    assertFalse(text.contains("toInvoiceDto"), text);
  }

  @Test
  @DisplayName("a package-private field in another package is refused by name for a type implementing the interface")
  void anUnreachableFieldIsRefusedWhenImplemented() {
    final var c = compile(
      sources(
        "mi.maps.Mappers",
        HIDDEN_CARRIER,
        "mi.Invoice",
        "package mi; public record Invoice(Long id, String customer) implements InvoiceMappings {}",
        "mi.InvoiceDto",
        "package mi; public record InvoiceDto(Long invoiceId, String customer) {}"
      )
    );
    assertFalse(c.success());
    final var message = errorOn(c, "implements the generated InvoiceMappings");
    assertTrue(message.contains("the field Mappers.DTO, which is not public,"), message);
    assertTrue(message.contains("from package mi"), message);
    assertTrue(message.contains("toInvoiceDto()"), message);
  }

  @Test
  @DisplayName("a private field on the source is refused by name for a type implementing the interface")
  void aPrivateFieldIsRefusedWhenImplemented() {
    final var c = compile(
      sources("mi.Invoice", INVOICE.replace("@Bridge static", "@Bridge private static"), "mi.InvoiceDto", INVOICE_DTO)
    );
    assertFalse(c.success());
    final var message = errorOn(c, "implements the generated InvoiceMappings");
    assertTrue(message.contains("the field Invoice.DTO, which is private,"), message);
  }

  @Test
  @DisplayName("a source declaring toX() itself keeps its own method, and the interface leaves it out")
  void aDeclaredMethodWins() throws Exception {
    final var own = INVOICE.replace(
      "}\n",
      "  public InvoiceDto toInvoiceDto() { return new InvoiceDto(-1L, \"own\"); }\n}\n"
    ).replaceFirst("\\}\n  public", "}\n  public");
    final var c = compile(sources("mi.Invoice", own, "mi.InvoiceDto", INVOICE_DTO));
    assertTrue(c.success(), c::errorMessages);
    assertFalse(c.generated().get("mi.InvoiceMappings").contains("toInvoiceDto"));
    try (final var loader = load(c)) {
      final var invoice = construct(loader, "mi.Invoice", 7L, "Ada");
      assertEquals("InvoiceDto[invoiceId=-1, customer=own]", call(invoice, "toInvoiceDto").toString());
    }
  }

  private static final String TWO_DTOS =
    "package mi;\n" +
    HEADER +
    "public record Invoice(Long id, String customer)@@implements {\n" +
    "  @Bridge static final ForwardMapper<Invoice, mi.a.InvoiceDto> A =\n" +
    "    Telescope.mapperForward(Invoice.class, mi.a.InvoiceDto.class);\n" +
    "@@" +
    "}\n";

  /**
   * A second field from {@code mi.Invoice}, in another package, so the two bridges' names differ.
   */
  private static final String OTHER_DTO =
    "package mi.other;\n" +
    HEADER +
    "import mi.Invoice;\n" +
    "public final class Mappers {\n" +
    "  @Bridge public static final ForwardMapper<Invoice, mi.b.InvoiceDto> B =\n" +
    "    Telescope.mapperForward(Invoice.class, mi.b.InvoiceDto.class);\n" +
    "}\n";

  private static JavaFileObject[] twoDtos(final boolean implemented, final String extra) {
    return sources(
      "mi.Invoice",
      TWO_DTOS.replace("@@implements", implemented ? " implements InvoiceMappings" : "").replace("@@", extra),
      "mi.other.Mappers",
      OTHER_DTO,
      "mi.a.InvoiceDto",
      "package mi.a; public record InvoiceDto(Long id, String customer) {}",
      "mi.b.InvoiceDto",
      "package mi.b; public record InvoiceDto(Long id) {}"
    );
  }

  @Test
  @DisplayName("two fields to targets of one simple name are refused on the second, naming both fields")
  void twoTargetsOfOneSimpleNameAreRefused() {
    final var c = compile(twoDtos(true, ""));
    assertFalse(c.success());
    final var message = errorOn(c, "a second method toInvoiceDto()");
    assertTrue(
      message.startsWith("Invoice implements the generated InvoiceMappings, and @Bridge field Mappers.B would give"),
      message
    );
    assertTrue(message.contains("which @Bridge field Invoice.A already gives it"), message);
    assertTrue(message.contains("returning mi.a.InvoiceDto where this one returns mi.b.InvoiceDto"), message);
    assertTrue(message.contains("Declare toInvoiceDto() on Invoice yourself"), message);
  }

  @Test
  @DisplayName("two targets of one simple name leave the name out of a type that does not implement the interface")
  void twoTargetsOfOneSimpleNameAreLeftOutUnlessImplemented() {
    final var c = compile(twoDtos(false, ""));
    assertTrue(c.success(), c::errorMessages);
    assertFalse(c.generated().get("mi.InvoiceMappings").contains("toInvoiceDto"));
  }

  @Test
  @DisplayName("declaring the shared name on the source settles two targets of one simple name")
  void declaringTheSharedNameSettlesTheClash() {
    final var c = compile(twoDtos(true, "  public mi.a.InvoiceDto toInvoiceDto() { return A.forward(this); }\n"));
    assertTrue(c.success(), c::errorMessages);
    assertFalse(c.generated().get("mi.InvoiceMappings").contains("toInvoiceDto"));
  }

  @Test
  @DisplayName("a pair and its reverse, each declared by a two-way field, are refused on the second field")
  void aPairAndItsReverseAreRefused() {
    final var c = pairAndReverse(" implements InvoiceDtoMappings");
    assertFalse(c.success());
    final var message = errorOn(c, "@Bridge field Mappers.FROM_DTO would give InvoiceDtoMappings a second method");
    assertTrue(message.startsWith("InvoiceDto implements the generated InvoiceDtoMappings"), message);
    assertTrue(
      message.contains("which @Bridge field Mappers.TO_DTO already gives it, both returning mi.Invoice"),
      message
    );
  }

  @Test
  @DisplayName("a pair and its reverse compile for types that do not implement their interfaces, which leave both out")
  void aPairAndItsReverseCompileUnlessImplemented() {
    final var c = pairAndReverse("");
    assertTrue(c.success(), c::errorMessages);
    assertFalse(c.generated().get("mi.InvoiceMappings").contains("toInvoiceDto"));
    assertFalse(c.generated().get("mi.InvoiceDtoMappings").contains("toInvoice"));
  }

  private static ProcessorHarness.Compilation pairAndReverse(final String dtoImplements) {
    return compile(
      sources(
        "mi.Mappers",
        "package mi;\n" +
          HEADER +
          "public final class Mappers {\n" +
          "  @Bridge static final Mapper<Invoice, InvoiceDto> TO_DTO =\n" +
          "    Telescope.mapper(Invoice.class, InvoiceDto.class, to(Invoice::id, InvoiceDto::invoiceId));\n" +
          "  @Bridge static final Mapper<InvoiceDto, Invoice> FROM_DTO =\n" +
          "    Telescope.mapper(InvoiceDto.class, Invoice.class, to(InvoiceDto::invoiceId, Invoice::id));\n" +
          "}\n",
        "mi.Invoice",
        "package mi; public record Invoice(Long id, String customer) {}",
        "mi.InvoiceDto",
        "package mi; public record InvoiceDto(Long invoiceId, String customer)" + dtoImplements + " {}"
      )
    );
  }

  @Test
  @DisplayName("a sealed source extends its interface, and each case converts through the field")
  void aSealedSourceExtendsItsInterface() throws Exception {
    final var shapes =
      "package mi;\n" +
      HEADER +
      "public sealed interface Shape extends ShapeMappings permits Circle, Square {\n" +
      "  @Bridge Mapper<Shape, ShapeDto> DTO = Telescope.mapper(Shape.class, ShapeDto.class);\n" +
      "  @Bridge Mapper<Circle, CircleDto> CIRCLE = Telescope.mapper(Circle.class, CircleDto.class);\n" +
      "  @Bridge Mapper<Square, SquareDto> SQUARE = Telescope.mapper(Square.class, SquareDto.class);\n" +
      "}\n";
    final var c = compile(
      sources(
        "mi.Shape",
        shapes,
        "mi.Circle",
        "package mi; public record Circle(String name) implements Shape {}",
        "mi.Square",
        "package mi; public record Square(String name) implements Shape {}",
        "mi.ShapeDto",
        "package mi; public sealed interface ShapeDto extends ShapeDtoMappings permits CircleDto, SquareDto {}",
        "mi.CircleDto",
        "package mi; public record CircleDto(String name) implements ShapeDto {}",
        "mi.SquareDto",
        "package mi; public record SquareDto(String name) implements ShapeDto {}"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    assertNoWarnings(c);
    try (final var loader = load(c)) {
      @SuppressWarnings("unchecked")
      final var field = (Mapper<Object, Object>) staticField(loader, "mi.Shape", "DTO");
      for (final var type : List.of("mi.Circle", "mi.Square")) {
        final var shape = construct(loader, type, "s");
        final var dto = call(shape, "toShapeDto");
        assertEquals(field.forward(shape), dto);
        assertEquals(type + "Dto", dto.getClass().getName());
        assertEquals(shape, call(dto, "toShape"));
      }
    }
  }

  @Test
  @DisplayName("a source read from a class, not compiled here, gets no interface")
  void aSourceFromAClassGetsNoInterface() throws Exception {
    final var library = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      sources(
        "lib.Invoice",
        "package lib; public record Invoice(Long id, String customer) {}",
        "lib.InvoiceDto",
        "package lib; public record InvoiceDto(Long id, String customer) {}"
      )
    );
    assertTrue(library.success(), library::errorMessages);
    final var root = Files.createTempDirectory(dir, "lib");
    for (final var e : library.classes().entrySet()) {
      write(root.resolve(e.getKey().replace('.', '/') + ".class"), e.getValue());
    }
    final var c = ProcessorHarness.compileFully(
      processors(),
      List.of("-classpath", System.getProperty("java.class.path") + File.pathSeparator + root),
      sources(
        "app.Mappers",
        "package app;\n" +
          HEADER +
          "public final class Mappers {\n" +
          "  @Bridge public static final Mapper<lib.Invoice, lib.InvoiceDto> DTO =\n" +
          "    Telescope.mapper(lib.Invoice.class, lib.InvoiceDto.class);\n" +
          "}\n"
      )
    );
    assertTrue(c.success(), c::errorMessages);
    assertNull(c.generated().get("lib.InvoiceMappings"), () -> c.generated().keySet().toString());
    assertNull(c.generated().get("lib.InvoiceDtoMappings"), () -> c.generated().keySet().toString());
  }

  /**
   * Writes, in the first round, a class declaring a {@code @Bridge} field from {@code mi.Invoice}.
   */
  @SupportedAnnotationTypes("*")
  private static final class LateField extends AbstractProcessor {

    private boolean done;

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
      if (done) return false;
      done = true;
      try (final var out = processingEnv.getFiler().createSourceFile("mi.Late").openWriter()) {
        out.write(
          "package mi;\n" +
            HEADER +
            "public final class Late {\n" +
            "  @Bridge static final ForwardMapper<Invoice, LateDto> LATE =\n" +
            "    Telescope.mapperForward(Invoice.class, LateDto.class);\n" +
            "}\n"
        );
      } catch (final IOException e) {
        throw new IllegalStateException(e);
      }
      return false;
    }
  }

  @Test
  @DisplayName("a field a processor writes after the source's interface was written is refused by name")
  void aFieldWrittenLaterIsRefused() {
    final var c = ProcessorHarness.compileFully(
      List.of(new LateField(), new BridgeProcessor()),
      List.of(),
      sources(
        "mi.Invoice",
        INVOICE.replace(" implements InvoiceMappings", ""),
        "mi.InvoiceDto",
        INVOICE_DTO.replace(" implements InvoiceDtoMappings", ""),
        "mi.LateDto",
        "package mi; public record LateDto(Long id) {}"
      )
    );
    assertFalse(c.success());
    final var message = errorOn(c, "@Bridge field Late.LATE is declared in a source written during annotation");
    assertTrue(message.contains("after InvoiceMappings was generated"), message);
    assertTrue(message.contains("no method toLateDto()"), message);
  }

  /** Writes, in the first round, the target class {@code mi.InvoiceDto}. */
  @SupportedAnnotationTypes("*")
  private static final class LateTarget extends AbstractProcessor {

    private boolean done;

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
      if (done) return false;
      done = true;
      try (final var out = processingEnv.getFiler().createSourceFile("mi.InvoiceDto").openWriter()) {
        out.write(INVOICE_DTO);
      } catch (final IOException e) {
        throw new IllegalStateException(e);
      }
      return false;
    }
  }

  @Test
  @DisplayName("a field whose target a processor writes waits a round, and its interface still resolves")
  void aTargetWrittenByAProcessorWaitsARound() throws Exception {
    final var c = ProcessorHarness.compileFully(
      List.of(new LateTarget(), new BridgeProcessor()),
      List.of(),
      sources("mi.Invoice", INVOICE)
    );
    assertTrue(c.success(), c::errorMessages);
    assertTrue(c.generated().containsKey("mi.InvoiceDtoMappings"), () -> c.generated().keySet().toString());
    try (final var loader = load(c)) {
      final var invoice = construct(loader, "mi.Invoice", 7L, "Ada");
      assertEquals(invoice, call(call(invoice, "toInvoiceDto"), "toInvoice"));
    }
  }
}
