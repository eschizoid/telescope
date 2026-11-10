package io.github.telescopeorganization.telescope.codegen;

import static io.github.telescopeorganization.telescope.codegen.ProcessorHarness.compileFully;
import static io.github.telescopeorganization.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.telescopeorganization.telescope.internal.pairing.PairingMessages;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code @Bridge} field's {@code writeBeans} default is refused in the runtime's words only where
 * the class has no member the strategy builds it with. A member that is there but out of the
 * bridge's package's reach, and a strategy a {@code writeBean} row names for the class, are refused
 * in the bridge's own words, since the runtime builds the first and refuses the second when the
 * hint is built.
 */
class DefaultStrategyReachTest {

  private static final String RECORD_SOURCE = "package dr;\npublic record %sSrc(String value) {}\n";

  /** A bean whose only constructor takes its property, and is private. */
  private static final String PRIVATE_CONSTRUCTOR =
    "package dr;\npublic class %1$sTgt {\n  private final String value;\n" +
    "  private %1$sTgt(final String value) { this.value = value; }\n" +
    "  public String getValue() { return value; }\n}\n";

  /** A bean built only by its builder, through a private constructor taking its property. */
  private static final String BUILDER_ONLY =
    "package dr;\npublic class %1$sTgt {\n  private final String value;\n" +
    "  private %1$sTgt(final String value) { this.value = value; }\n" +
    "  public String getValue() { return value; }\n" +
    "  public static Builder builder() { return new Builder(); }\n" +
    "  public static final class Builder {\n    private String value;\n" +
    "    public Builder value(final String v) { value = v; return this; }\n" +
    "    public %1$sTgt build() { return new %1$sTgt(value); }\n  }\n}\n";

  private static final String STRATEGY = "io.github.telescopeorganization.telescope.mapping.WriteHint.WriteStrategy.";

  /** A carrier whose {@code @Bridge} field converts {@code <prefix>Src} to {@code <prefix>Tgt}. */
  private static String carrier(final String prefix, final String hints) {
    return (
      "package dr;\npublic class " +
      prefix +
      "Carrier {\n  @io.github.telescopeorganization.telescope.annotations.Bridge\n" +
      "  public static final io.github.telescopeorganization.telescope.conversion.Mapper<" +
      prefix +
      "Src, " +
      prefix +
      "Tgt> MAPPER = io.github.telescopeorganization.telescope.Telescope.mapper(" +
      prefix +
      "Src.class, " +
      prefix +
      "Tgt.class, " +
      hints +
      ");\n}\n"
    );
  }

  private static ProcessorHarness.Compilation bridged(final String prefix, final String target, final String hints) {
    return compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      source("dr." + prefix + "Src", RECORD_SOURCE.formatted(prefix)),
      source("dr." + prefix + "Tgt", target.formatted(prefix)),
      source("dr." + prefix + "Carrier", carrier(prefix, hints))
    );
  }

  @Test
  @DisplayName(
    "a CONSTRUCTOR default on a class whose constructor is out of the bridge's reach keeps the bridge's words"
  )
  void anUnreachableConstructorIsTheBridgesLimit() {
    final var compiled = bridged(
      "Dc",
      PRIVATE_CONSTRUCTOR,
      "io.github.telescopeorganization.telescope.mapping.WriteHint.writeBeans(" + STRATEGY + "CONSTRUCTOR)"
    );
    assertFalse(compiled.success(), "a private constructor compiled into the bridge");
    assertTrue(compiled.hasError("no constructor the bridge's package can call"), compiled::errorMessages);
    assertFalse(
      compiled.hasError(PairingMessages.beanUnbuiltBy("DcSrc", "DcTgt", "DcTgt", "CONSTRUCTOR")),
      compiled::errorMessages
    );
  }

  @Test
  @DisplayName("a writeBean row keyed to the class decides its strategy over the writeBeans default")
  void aKeyedHintIsRefusedInTheBridgesWords() {
    final var compiled = bridged(
      "Dk",
      BUILDER_ONLY,
      "io.github.telescopeorganization.telescope.mapping.WriteHint.writeBeans(" +
        STRATEGY +
        "BUILDER), io.github.telescopeorganization.telescope.mapping.WriteHint.writeBean(DkTgt.class, " +
        STRATEGY +
        "SETTERS)"
    );
    assertFalse(compiled.success(), "a SETTERS hint on a class with no no-argument constructor compiled");
    assertTrue(compiled.hasError("writeStrategy = SETTERS on"), compiled::errorMessages);
    assertFalse(compiled.hasError("which the mapper's writeBeans default names"), compiled::errorMessages);
  }

  @Test
  @DisplayName("a writeBean row with no writeBeans default is refused in the bridge's words")
  void aKeyedHintWithoutADefault() {
    final var compiled = bridged(
      "Dn",
      BUILDER_ONLY,
      "io.github.telescopeorganization.telescope.mapping.WriteHint.writeBean(DnTgt.class, " + STRATEGY + "SETTERS)"
    );
    assertFalse(compiled.success(), "a SETTERS hint on a class with no no-argument constructor compiled");
    assertTrue(compiled.hasError("writeStrategy = SETTERS on"), compiled::errorMessages);
    assertFalse(compiled.hasError("which the mapper's writeBeans default names"), compiled::errorMessages);
  }
}
