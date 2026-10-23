package io.github.eschizoid.telescope.codegen.spike;

import io.github.eschizoid.telescope.codegen.BridgeProcessor;
import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpBaselineTest {

  @Test
  void annotationFormRenameAndTransform() {
    final var c = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      ProcessorHarness.source(
        "demo.Conv",
        "package demo; public final class Conv { public static String lower(String s) { return s.toLowerCase(); } }"
      ),
      ProcessorHarness.source(
        "demo.User",
        "package demo;\n" +
          "import io.github.eschizoid.telescope.annotations.*;\n" +
          "@Bridge(value = UserDto.class, renames = { @Rename(source = \"id\", target = \"userId\"), @Rename(source = \"email\", target = \"contact\") },\n" +
          "  transforms = @Transform(field = \"email\", using = Conv.class, method = \"lower\"), drops = \"secret\")\n" +
          "public record User(long id, String email, String name, String secret) {}\n"
      ),
      ProcessorHarness.source(
        "demo.UserDto",
        "package demo; public record UserDto(long userId, String contact, String name) {}"
      )
    );
    System.out.println("SUCCESS=" + c.success() + "\n" + c.errorMessages());
    c.generated().forEach((k, v) -> System.out.println("=== " + k + "\n" + v));
  }
}
