package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The conversions the runtime mapper makes between two properties of one name without a row: text
 * and numbers, enums and booleans, numbers widened forward, and lists and sets.
 */
class BuiltInConversionTest {

  enum Status {
    OPEN,
    CLOSED,
  }

  record Order(String id, String total, String status, String paid, List<String> tags) {}

  record OrderDto(long id, BigDecimal total, Status status, Boolean paid, Set<Long> tags) {}

  @Nested
  @DisplayName("text and values, both ways")
  class TextAndValues {

    @Test
    @DisplayName("a String parses into a number, an enum and a boolean, and each converts back as text")
    void stringsConvertBothWays() {
      final var mapper = Telescope.mapper(Order.class, OrderDto.class);
      final var dto = mapper.forward(new Order("42", "12.50", "CLOSED", "TRUE", List.of("3", "1", "3")));
      assertEquals(
        new OrderDto(42L, new BigDecimal("12.50"), Status.CLOSED, true, new LinkedHashSet<>(List.of(3L, 1L))),
        dto
      );
      assertEquals(new Order("42", "12.50", "CLOSED", "true", List.of("3", "1")), mapper.backward(dto));
    }

    @Test
    @DisplayName("a null String writes a primitive's default and null anywhere else")
    void nullsConvert() {
      final var mapper = Telescope.mapper(Order.class, OrderDto.class);
      assertEquals(new OrderDto(0L, null, null, null, null), mapper.forward(new Order(null, null, null, null, null)));
    }

    @Test
    @DisplayName("a String that is no number fails naming the property and the value")
    void anUnparsableNumberIsNamed() {
      final var mapper = Telescope.mapper(Order.class, OrderDto.class);
      final var failure = assertThrows(IllegalArgumentException.class, () ->
        mapper.forward(new Order("1", "12x", "OPEN", "true", List.of()))
      );
      assertEquals("'total' got \"12x\", not a BigDecimal", failure.getMessage());
      assertInstanceOf(NumberFormatException.class, failure.getCause());
    }

    @Test
    @DisplayName("an element that is no number fails naming the property that holds it")
    void anUnparsableElementIsNamed() {
      final var mapper = Telescope.mapper(Order.class, OrderDto.class);
      final var failure = assertThrows(IllegalArgumentException.class, () ->
        mapper.forward(new Order("1", "1", "OPEN", "true", List.of("x")))
      );
      assertEquals("'tags' got \"x\", not a Long", failure.getMessage());
    }

    @Test
    @DisplayName("a String that names no constant fails naming the enum")
    void anUnknownConstantIsNamed() {
      final var mapper = Telescope.mapper(Order.class, OrderDto.class);
      final var failure = assertThrows(IllegalArgumentException.class, () ->
        mapper.forward(new Order("1", "1", "open", "true", List.of()))
      );
      assertEquals("'status' got \"open\", not a constant of BuiltInConversionTest.Status", failure.getMessage());
    }

    @Test
    @DisplayName("a boolean is true or false in any case and nothing else")
    void aBooleanIsStrict() {
      final var mapper = Telescope.mapper(Order.class, OrderDto.class);
      assertEquals(false, mapper.forward(new Order("1", "1", "OPEN", "False", List.of())).paid());
      final var failure = assertThrows(IllegalArgumentException.class, () ->
        mapper.forward(new Order("1", "1", "OPEN", "yes", List.of()))
      );
      assertEquals("'paid' got \"yes\", not a Boolean", failure.getMessage());
    }

    record Counts(String small, String big, String ratio) {}

    record CountDtos(byte small, BigInteger big, double ratio) {}

    @Test
    @DisplayName("a String out of a primitive's range fails, and the value names the primitive")
    void outOfRangeIsNamed() {
      final var mapper = Telescope.mapper(Counts.class, CountDtos.class);
      assertEquals(
        new CountDtos((byte) 7, new BigInteger("123456789012345678901234567890"), 0.5),
        mapper.forward(new Counts("7", "123456789012345678901234567890", "0.5"))
      );
      final var failure = assertThrows(IllegalArgumentException.class, () ->
        mapper.forward(new Counts("300", "1", "1"))
      );
      assertEquals("'small' got \"300\", not a byte", failure.getMessage());
      assertEquals(
        new Counts("-3", "9", "0.25"),
        mapper.backward(new CountDtos((byte) -3, BigInteger.valueOf(9), 0.25))
      );
    }

    record Renamed(String code) {}

    record RenamedDto(int number) {}

    @Test
    @DisplayName("a plain to row between two names converts as a same-named pair does")
    void aPlainRowConverts() {
      final var mapper = Telescope.mapper(Renamed.class, RenamedDto.class, to(Renamed::code, RenamedDto::number));
      assertEquals(new RenamedDto(5), mapper.forward(new Renamed("5")));
      assertEquals(new Renamed("5"), mapper.backward(new RenamedDto(5)));
    }

    record Line(String qty) {}

    static final class LineBean {

      private long qty;

      public long getQty() {
        return qty;
      }

      public void setQty(final long qty) {
        this.qty = qty;
      }
    }

    @Test
    @DisplayName("into writes the converted value into an existing target")
    void intoConverts() {
      final var mapper = Telescope.mapper(Line.class, LineBean.class);
      final var target = new LineBean();
      assertEquals(target, mapper.into(target, new Line("12")));
      assertEquals(12L, target.getQty());
    }

    record Maybe(Optional<String> count) {}

    record MaybeDto(Optional<Integer> count) {}

    @Test
    @DisplayName("an Optional's value converts as a property's does")
    void anOptionalConverts() {
      final var mapper = Telescope.mapper(Maybe.class, MaybeDto.class);
      assertEquals(new MaybeDto(Optional.of(4)), mapper.forward(new Maybe(Optional.of("4"))));
      assertEquals(new Maybe(Optional.empty()), mapper.backward(new MaybeDto(Optional.empty())));
    }
  }

  @Nested
  @DisplayName("widening, forward only")
  class Widening {

    record Narrow(int count, Integer boxed, long big, char letter, BigInteger huge) {}

    record Wide(long count, double boxed, BigDecimal big, int letter, BigDecimal huge) {}

    @Test
    @DisplayName("a forward-only mapper widens each number to the wider type")
    void forwardWidens() {
      final var mapper = Telescope.mapperForward(Narrow.class, Wide.class);
      assertEquals(
        new Wide(3L, 4.0, BigDecimal.valueOf(5L), 'a', new BigDecimal("12345678901234567890")),
        mapper.forward(new Narrow(3, 4, 5L, 'a', new BigInteger("12345678901234567890")))
      );
    }

    @Test
    @DisplayName("a null wrapper widens to a primitive's default")
    void aNullWidensToTheDefault() {
      final var mapper = Telescope.mapperForward(Narrow.class, Wide.class);
      assertEquals(0.0, mapper.forward(new Narrow(0, null, 0L, 'a', null)).boxed());
    }

    record Count(int n) {}

    record CountDto(long n) {}

    @Test
    @DisplayName("a two-way mapper refuses a widening pair, naming the row to write")
    void twoWayRefusesWidening() {
      final var failure = assertThrows(RuntimeException.class, () -> Telescope.mapper(Count.class, CountDto.class));
      assertTrue(
        failure.getMessage().contains("'n' widens int to long, which converts forward only"),
        failure::getMessage
      );
      assertTrue(failure.getMessage().contains("to(source, target, forward, backward)"), failure::getMessage);
    }

    @Test
    @DisplayName("narrowing is refused on either kind of mapper, naming the row to write")
    void narrowingIsRefused() {
      for (final var build : List.<Runnable>of(
        () -> Telescope.mapperForward(CountDto.class, Count.class),
        () -> Telescope.mapper(CountDto.class, Count.class)
      )) {
        final var failure = assertThrows(RuntimeException.class, build::run);
        assertTrue(failure.getMessage().contains("'n' would narrow long to int"), failure::getMessage);
      }
    }

    record Ratio(double r) {}

    record RatioDto(BigDecimal r) {}

    @Test
    @DisplayName("a double does not widen to a BigDecimal, which holds no NaN or infinity")
    void aDoubleIsNoBigDecimal() {
      final var failure = assertThrows(RuntimeException.class, () ->
        Telescope.mapperForward(Ratio.class, RatioDto.class)
      );
      assertTrue(failure.getMessage().contains("'r' would narrow double to java.math.BigDecimal"), failure::getMessage);
    }
  }

  @Nested
  @DisplayName("refused without a row")
  class Refused {

    record Stamp(String at) {}

    record StampDto(Instant at) {}

    @Test
    @DisplayName("text and a date or time need a format, which the refusal says to give in a row")
    void datesNeedAFormat() {
      final var failure = assertThrows(RuntimeException.class, () -> Telescope.mapper(Stamp.class, StampDto.class));
      assertTrue(failure.getMessage().contains("only in a format"), failure::getMessage);
      assertTrue(failure.getMessage().contains("to(source, target, forward, backward)"), failure::getMessage);
    }

    record Letter(String c) {}

    record LetterDto(char c) {}

    @Test
    @DisplayName("a String and a char stay apart")
    void aCharIsNotText() {
      final var failure = assertThrows(RuntimeException.class, () -> Telescope.mapper(Letter.class, LetterDto.class));
      assertTrue(failure.getMessage().contains("incompatible source/target shapes"), failure::getMessage);
    }
  }

  @Nested
  @DisplayName("lists and sets")
  class ListsAndSets {

    record Tags(List<String> tags) {}

    record TagSet(Set<String> tags) {}

    @Test
    @DisplayName("a list into a set drops duplicates, and a set into a list keeps its iteration order")
    void aListAndASetConvert() {
      final var mapper = Telescope.mapper(Tags.class, TagSet.class);
      final var set = mapper.forward(new Tags(Arrays.asList("b", "a", "b")));
      assertEquals(List.of("b", "a"), List.copyOf(set.tags()));
      assertInstanceOf(LinkedHashSet.class, set.tags());
      assertEquals(new Tags(List.of("b", "a")), mapper.backward(set));
    }

    record Sorted(TreeSet<String> tags) {}

    @Test
    @DisplayName("a sorted set built from a list orders its elements")
    void aSortedSetOrders() {
      final var mapper = Telescope.mapper(Tags.class, Sorted.class);
      assertEquals(List.of("a", "b"), List.copyOf(mapper.forward(new Tags(List.of("b", "a", "b"))).tags()));
      assertEquals(new Tags(List.of("a", "b")), mapper.backward(new Sorted(new TreeSet<>(Set.of("b", "a")))));
    }
  }
}
