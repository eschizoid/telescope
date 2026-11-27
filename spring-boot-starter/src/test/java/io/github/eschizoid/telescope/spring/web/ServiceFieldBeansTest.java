package io.github.eschizoid.telescope.spring.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.eschizoid.telescope.codegen.BridgeProcessor;
import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.spring.TelescopeMapperRegistry;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;
import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.ResolvableType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pins the beans the starter registers for {@code @Bridge} fields whose rows call a service: one
 * per field, of the field's own type, named after it and bound to the context's bean of each
 * service; a service with no bean, or two with none primary, fails startup naming the field, the
 * row and the service; a {@code Mapper} field's bean joins the registry; and a mapped endpoint
 * converts through the bound bean. The model is compiled with the processor into a loader of its
 * own, so no other test's context sees its fields.
 */
class ServiceFieldBeansTest {

  private static final String SHOP = """
    package fx;

    import static io.github.eschizoid.telescope.Telescope.edits;
    import static io.github.eschizoid.telescope.Telescope.mapper;
    import static io.github.eschizoid.telescope.Telescope.mapperForward;
    import static io.github.eschizoid.telescope.mapping.Mapping.compute;
    import static io.github.eschizoid.telescope.mapping.Mapping.service;
    import static io.github.eschizoid.telescope.mapping.Mapping.toOrElseGet;

    import io.github.eschizoid.telescope.annotations.Bridge;
    import io.github.eschizoid.telescope.conversion.EditMapper;
    import io.github.eschizoid.telescope.conversion.ForwardMapper;
    import io.github.eschizoid.telescope.conversion.Mapper;

    public final class Shop {
      private Shop() {}

      @Bridge
      public static final EditMapper<LineChanges, Line> LINE = edits(
        LineChanges.class,
        Line.class,
        compute(Line::getPrice, service(Catalog::price))
      );

      @Bridge
      public static final ForwardMapper<Line, LineView> VIEW = mapperForward(
        Line.class,
        LineView.class,
        compute(LineView::label, service(Labels::label))
      );

      @Bridge
      public static final Mapper<Tag, TagDto> TAG = mapper(
        Tag.class,
        TagDto.class,
        toOrElseGet(Tag::name, TagDto::name, service(Labels::fallback))
      );
    }
    """;

  private static final List<JavaFileObject> MODEL = List.of(
    ProcessorHarness.source(
      "fx.Line",
      """
      package fx;
      public class Line {
        private String sku;
        private int price;
        public String getSku() { return sku; }
        public void setSku(final String v) { sku = v; }
        public int getPrice() { return price; }
        public void setPrice(final int v) { price = v; }
      }
      """
    ),
    ProcessorHarness.source("fx.LineChanges", "package fx; public record LineChanges(String sku) {}"),
    ProcessorHarness.source(
      "fx.LineView",
      "package fx; public record LineView(String sku, int price, String label) {}"
    ),
    ProcessorHarness.source("fx.Tag", "package fx; public record Tag(String name) {}"),
    ProcessorHarness.source("fx.TagDto", "package fx; public record TagDto(String name) {}"),
    ProcessorHarness.source(
      "fx.Catalog",
      """
      package fx;
      public class Catalog {
        public int price(final LineChanges line) { return line.sku().length() * 10; }
      }
      """
    ),
    ProcessorHarness.source(
      "fx.Labels",
      """
      package fx;
      public class Labels {
        public String label(final Line line) { return "L-" + line.getSku(); }
        public String fallback() { return "untagged"; }
      }
      """
    ),
    ProcessorHarness.source(
      "fx.LineApi",
      """
      package fx;

      import io.github.eschizoid.telescope.conversion.EditMapper;
      import io.github.eschizoid.telescope.spring.web.TelescopeGetMapping;
      import org.springframework.web.bind.annotation.PathVariable;
      import org.springframework.web.bind.annotation.RestController;

      @RestController
      public class LineApi {
        private final EditMapper<LineChanges, Line> lines;

        public LineApi(final EditMapper<LineChanges, Line> lines) {
          this.lines = lines;
        }

        @TelescopeGetMapping(value = "/lines/{sku}", to = LineView.class)
        public Line line(@PathVariable("sku") final String sku) {
          return lines.forward(new LineChanges(sku));
        }
      }
      """
    ),
    ProcessorHarness.source("fx.Shop", SHOP)
  );

  private static ClassLoader loader;

  @BeforeAll
  static void compile() throws IOException {
    final var compilation = ProcessorHarness.compileFully(
      List.<Processor>of(new BridgeProcessor()),
      List.of(),
      MODEL.toArray(JavaFileObject[]::new)
    );
    assertTrue(compilation.success(), () -> compilation.diagnostics().toString());
    final var dir = Files.createTempDirectory("service-field-beans");
    for (final var e : compilation.classes().entrySet()) {
      final var file = dir.resolve(e.getKey().replace('.', '/') + ".class");
      Files.createDirectories(file.getParent());
      Files.write(file, e.getValue());
    }
    for (final var e : compilation.resources().entrySet()) {
      final var file = dir.resolve(e.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, e.getValue());
    }
    loader = new URLClassLoader(new URL[] { dir.toUri().toURL() }, ServiceFieldBeansTest.class.getClassLoader());
  }

  private static Class<?> type(final String name) {
    try {
      return loader.loadClass("fx." + name);
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Object make(final String name) {
    try {
      return type(name).getConstructor().newInstance();
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static WebApplicationContextRunner runner(final String... services) {
    var runner = WebMvcTestSupport.runner().withClassLoader(loader);
    for (final var service : services) {
      final var name = service.contains(":") ? service.substring(0, service.indexOf(':')) : service;
      final var bean = service.contains(":") ? service.substring(service.indexOf(':') + 1) : service;
      runner = runner.withBean(name, (Class) type(bean), () -> make(bean));
    }
    return runner;
  }

  @Test
  @DisplayName(
    "each field calling a service is a bean of its own type, named after it and bound to the context's beans"
  )
  @SuppressWarnings("unchecked")
  void fieldBeansAreBound() {
    runner("catalog:Catalog", "labels:Labels").run(context -> {
      assertTrue(context.getStartupFailure() == null, () -> String.valueOf(context.getStartupFailure()));
      final var edits = (EditMapper<Object, Object>) context
        .getBeanProvider(ResolvableType.forClassWithGenerics(EditMapper.class, type("LineChanges"), type("Line")))
        .getObject();
      final var line = edits.forward(type("LineChanges").getConstructors()[0].newInstance("abc"));
      assertEquals(30, type("Line").getMethod("getPrice").invoke(line));
      assertNotNull(context.getBean("Shop.LINE"));
      assertNotNull(context.getBean("Shop.VIEW", ForwardMapper.class));
      final var tags = context.getBean(TelescopeMapperRegistry.class).get(type("Tag"), type("TagDto"));
      assertEquals(
        "TagDto[name=untagged]",
        String.valueOf(
          ((Mapper<Object, Object>) tags).forward(type("Tag").getConstructors()[0].newInstance((Object) null))
        )
      );
    });
  }

  @Test
  @DisplayName("a mapped endpoint converts through the bound bean of the field declaring its pair")
  void endpointUsesTheBoundBean() {
    runner("catalog:Catalog", "labels:Labels")
      .withUserConfiguration(type("LineApi"))
      .run(context ->
        MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext())
          .build()
          .perform(get("/lines/abcd"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.price").value(40))
          .andExpect(jsonPath("$.label").value("L-abcd"))
      );
  }

  @Test
  @DisplayName("a service with no bean fails startup naming the field, the row and the service")
  void missingServiceFailsStartup() {
    runner("labels:Labels").run(context -> {
      final var failure = context.getStartupFailure();
      assertNotNull(failure);
      assertTrue(
        causes(failure).contains(
          "The bean of the @Bridge field Shop.LINE needs a bean of fx.Catalog for compute(Line::getPrice," +
            " service(Catalog::price)), and the application declares none. Declare one."
        ),
        () -> causes(failure)
      );
    });
  }

  @Test
  @DisplayName("two beans of a service with none primary fail startup naming both")
  void ambiguousServiceFailsStartup() {
    runner("catalog:Catalog", "spare:Catalog", "labels:Labels").run(context -> {
      final var failure = context.getStartupFailure();
      assertNotNull(failure);
      assertTrue(
        causes(failure).contains(
          "The bean of the @Bridge field Shop.LINE needs one bean of fx.Catalog for compute(Line::getPrice," +
            " service(Catalog::price)), and the application declares 2, catalog and spare, with none primary."
        ),
        () -> causes(failure)
      );
    });
  }

  private static String causes(final Throwable failure) {
    final var out = new StringBuilder();
    for (Throwable at = failure; at != null; at = at.getCause()) out.append(at.getMessage()).append('\n');
    return out.toString();
  }
}
