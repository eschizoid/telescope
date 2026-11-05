package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.ForwardMapper;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The README's opening example, compiled as written: a record declaring a {@code @Bridge} mapper
 * field with a renamed field, a field read through a nested object, a field computed from the whole
 * source and a dropped field. The processor must compile every row, and the generated mapper must
 * give what the runtime mapper built from the same rows gives.
 */
class ReadmeHeroTest {

  private static final String INVOICE = """
    package rh;

    import static io.github.eschizoid.telescope.Telescope.mapperForward;
    import static io.github.eschizoid.telescope.Telescope.path;
    import static io.github.eschizoid.telescope.mapping.Mapping.compute;
    import static io.github.eschizoid.telescope.mapping.Mapping.drop;
    import static io.github.eschizoid.telescope.mapping.Mapping.to;

    import io.github.eschizoid.telescope.annotations.Bridge;
    import io.github.eschizoid.telescope.conversion.ForwardMapper;

    public record Invoice(String id, Customer customer, long totalCents, String internalNote) {
      @Bridge
      static final ForwardMapper<Invoice, InvoiceDto> DTO = mapperForward(
        Invoice.class,
        InvoiceDto.class,
        // a renamed field
        to(Invoice::id, InvoiceDto::number),
        // a field read through a nested object
        to(path(Invoice::customer, Customer::email), InvoiceDto::billTo),
        // a field built from the whole invoice
        compute(InvoiceDto::total, Money::format),
        // a field left out on purpose
        drop(Invoice::internalNote)
      );

      public InvoiceDto toDto() {
        return DTO.forward(this);
      }

      public static ForwardMapper<Invoice, InvoiceDto> declared() {
        return DTO;
      }
    }
    """;

  private static final List<JavaFileObject> MODEL = List.of(
    ProcessorHarness.source("rh.Invoice", INVOICE),
    ProcessorHarness.source("rh.Customer", "package rh; public record Customer(String name, String email) {}"),
    ProcessorHarness.source(
      "rh.InvoiceDto",
      "package rh; public record InvoiceDto(String number, String billTo, String total) {}"
    ),
    ProcessorHarness.source(
      "rh.Money",
      """
      package rh;
      public final class Money {
        private Money() {}
        public static String format(final Invoice invoice) {
          return String.format("$%d.%02d", invoice.totalCents() / 100, invoice.totalCents() % 100);
        }
      }
      """
    )
  );

  @Test
  @DisplayName("the README's @Bridge field compiles every row, and its generated mapper gives what the runtime gives")
  void heroCompilesAndAgrees() throws Exception {
    final var generated = ProcessorHarness.compileFully(
      List.<Processor>of(new BridgeProcessor(), new MapperVerifierProcessor()),
      List.of(),
      MODEL.toArray(JavaFileObject[]::new)
    );
    assertTrue(generated.success(), () -> generated.diagnostics().toString());
    assertTrue(
      generated.diagnostics().stream().noneMatch((d) -> d.getKind() == Diagnostic.Kind.WARNING),
      () -> generated.diagnostics().toString()
    );
    final var bridge = generated.generated().get("rh.InvoiceToInvoiceDtoBridge");
    assertTrue(bridge != null && bridge.contains("Money.format("), () -> String.valueOf(generated.generated().keySet()));

    final var runtime = ProcessorHarness.compileFully(List.of(), List.of(), MODEL.toArray(JavaFileObject[]::new));
    assertTrue(runtime.success(), () -> runtime.diagnostics().toString());

    try (final var onGenerated = load(generated); final var onRuntime = load(runtime)) {
      for (final var customer : new Object[][] { { "Ada", "ada@example.com" }, null }) {
        final Object viaBridge = forward(onGenerated, customer);
        final Object viaRuntime = forward(onRuntime, customer);
        assertEquals(viaRuntime.toString(), viaBridge.toString());
        final var dto = (Record) viaBridge;
        assertEquals("$12.34", dto.getClass().getMethod("total").invoke(dto));
        if (customer == null) assertNull(dto.getClass().getMethod("billTo").invoke(dto));
      }
      final var invoice = invoice(onGenerated, new Object[] { "Ada", "ada@example.com" });
      assertEquals(
        forward(onGenerated, new Object[] { "Ada", "ada@example.com" }).toString(),
        invoice.getClass().getMethod("toDto").invoke(invoice).toString()
      );
      assertTrue(declared(onGenerated).explain().toString().contains("InvoiceToInvoiceDtoBridge"));
    }
  }

  private static Object forward(final ClassLoader loader, final Object[] customer) throws Exception {
    return declared(loader).forward(invoice(loader, customer));
  }

  @SuppressWarnings("unchecked")
  private static ForwardMapper<Object, Object> declared(final ClassLoader loader) throws Exception {
    return (ForwardMapper<Object, Object>) loader.loadClass("rh.Invoice").getMethod("declared").invoke(null);
  }

  private static Object invoice(final ClassLoader loader, final Object[] customer) throws Exception {
    final Object owner = customer == null
      ? null
      : loader.loadClass("rh.Customer").getConstructors()[0].newInstance(customer[0], customer[1]);
    return loader
      .loadClass("rh.Invoice")
      .getConstructors()[0]
      .newInstance("INV-1", owner, 1234L, "internal");
  }

  /** A loader over the compiled classes and generated resources, as a class path holds them. */
  private static URLClassLoader load(final ProcessorHarness.Compilation compilation) throws IOException {
    final var dir = Files.createTempDirectory("readme-hero");
    for (final var e : compilation.classes().entrySet()) {
      write(dir.resolve(e.getKey().replace('.', '/') + ".class"), e.getValue());
    }
    for (final var e : compilation.resources().entrySet()) write(dir.resolve(e.getKey()), e.getValue().getBytes());
    return new URLClassLoader(new URL[] { dir.toUri().toURL() }, ReadmeHeroTest.class.getClassLoader());
  }

  private static void write(final Path file, final byte[] bytes) throws IOException {
    Files.createDirectories(file.getParent());
    Files.write(file, bytes);
  }
}
