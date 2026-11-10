package io.github.telescopeorganization.telescope;

import static io.github.telescopeorganization.telescope.mapping.Mapping.compute;
import static io.github.telescopeorganization.telescope.mapping.Mapping.constant;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;
import static io.github.telescopeorganization.telescope.mapping.Mapping.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.telescopeorganization.telescope.internal.pairing.PairingMessages;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mapper that converts both ways refuses, at the pair it is built for, a property nothing pairs
 * with, whatever other rows the pair has. A row writing a target property through a path, a
 * constant or a compute fills it, and a row reading a source property through a path reads it.
 */
class StrictPairingBesideRowsTest {

  record Lone(String a) {}

  record Src(String a, String x) {}

  record Tgt(String a, String b, String c) {}

  record Narrow(String a, String c) {}

  record Wide(String a, String x, String y) {}

  record Inner(String a) {}

  record InnerDto(String a, String extra) {}

  record Outer(Inner in) {}

  record OuterDto(InnerDto in, String note) {}

  @Test
  @DisplayName("a target property beside a constant row is refused by name")
  void targetBesideAConstant() {
    final var thrown = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(Lone.class, Tgt.class, constant(Tgt::c, "k"))
    );
    assertEquals(PairingMessages.noSameNameSource("Lone", "Tgt", "field", "field", "b"), thrown.getMessage());
  }

  @Test
  @DisplayName("a target property beside a row writing through a target path is refused by name")
  void targetBesideATargetPath() {
    final var thrown = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(Src.class, Tgt.class, to(Src::x, Telescope.of(Tgt.class).field(Tgt::c)))
    );
    assertEquals(PairingMessages.noSameNameSource("Src", "Tgt", "field", "field", "b"), thrown.getMessage());
  }

  @Test
  @DisplayName("a source property beside a row reading through a source path is refused by name")
  void sourceBesideASourcePath() {
    final var thrown = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(Wide.class, Narrow.class, to(Telescope.of(Wide.class).field(Wide::x), Narrow::c))
    );
    assertEquals(PairingMessages.noSameNameTarget("Wide", "Narrow", "field", "field", "y"), thrown.getMessage());
  }

  @Test
  @DisplayName("a mapper builder refuses the same property beside a compute row")
  void builderBesideACompute() {
    final var thrown = assertThrows(IllegalStateException.class, () ->
      Telescope.mapperBuilder(Lone.class, Tgt.class).add(compute(Tgt::c, () -> "k")).build()
    );
    assertEquals(PairingMessages.noSameNameSource("Lone", "Tgt", "field", "field", "b"), thrown.getMessage());
  }

  @Test
  @DisplayName("properties a compute row fills and path rows read and write pair the mapper")
  void filledAndReadPropertiesPair() {
    final var mapper = Telescope.mapper(
      Wide.class,
      Tgt.class,
      compute(Tgt::c, () -> "computed"),
      when(w -> true, to(Telescope.of(Wide.class).field(Wide::y), Tgt::c)),
      to(Telescope.of(Wide.class).field(Wide::x), Tgt::b)
    );
    assertEquals("x", mapper.forward(new Wide("a", "x", "y")).b());
  }

  @Test
  @DisplayName("a mapper converting forward only leaves an unpaired target at its default and a source unread")
  void forwardOnlyStaysLenient() {
    final var mapper = Telescope.mapperForward(Wide.class, Tgt.class, constant(Tgt::c, "k"));
    assertEquals(new Tgt("a", null, "k"), mapper.forward(new Wide("a", "x", "y")));
  }

  @Test
  @DisplayName("a target path whose first property is not known leaves the target's properties unrefused")
  void unknownTargetPathStart() {
    final Function<Tgt, String> getter = Tgt::c;
    final var path = Telescope.lens(getter, (Tgt t, String v) -> new Tgt(t.a(), t.b(), v));
    final var mapper = Telescope.mapper(Src.class, Tgt.class, to(Src::x, path));
    assertEquals(new Tgt("a", null, "x"), mapper.forward(new Src("a", "x")));
  }

  @Test
  @DisplayName("a source path whose first property is not known leaves the source's properties unrefused")
  void unknownSourcePathStart() {
    final Function<Wide, String> getter = Wide::y;
    final var path = Telescope.lens(getter, (Wide w, String v) -> new Wide(w.a(), w.x(), v));
    final var mapper = Telescope.mapper(Wide.class, Narrow.class, to(path, Narrow::c));
    assertEquals(new Narrow("a", "y"), mapper.forward(new Wide("a", "x", "y")));
  }

  @Test
  @DisplayName("a nested pair no row is keyed to leaves its unpaired target at its default")
  void nestedPairStaysLenient() {
    final var mapper = Telescope.mapper(Outer.class, OuterDto.class, constant(OuterDto::note, "k"));
    final var out = mapper.forward(new Outer(new Inner("a")));
    assertEquals("a", out.in().a());
    assertNull(out.in().extra());
  }
}
