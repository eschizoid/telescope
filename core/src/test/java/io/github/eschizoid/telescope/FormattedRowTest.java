package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.toFormatted;
import static io.github.eschizoid.telescope.mapping.Mapping.toFormattedNumber;
import static io.github.eschizoid.telescope.mapping.Mapping.toFormattedNumberOneWay;
import static io.github.eschizoid.telescope.mapping.Mapping.toFormattedOneWay;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The rows that write a date, a time or a number as text in a pattern and read it back. */
class FormattedRowTest {

  record Order(LocalDate shipDate, BigDecimal total, int quantity, double weight) {}

  record OrderDto(String shipDate, String total, String quantity, String weight) {}

  record Event(Instant at, LocalTime opens) {}

  record EventDto(String at, String opens) {}

  /** A bean target, whose properties are named without the getter's prefix. */
  public static final class Label {

    private String day;

    public Label() {}

    public String getDay() {
      return day;
    }

    public void setDay(final String day) {
      this.day = day;
    }
  }

  record Shipment(LocalDate day) {}

  record Priced(BigDecimal total) {}

  record PricedDto(String total) {}

  private static final Priced PRICED = new Priced(new BigDecimal("1234.5"));

  private static final Order ORDER = new Order(LocalDate.of(2026, 12, 31), new BigDecimal("1234.5"), 3, 2.25);

  private static Mapper<Order, OrderDto> orderMapper() {
    return Telescope.mapper(
      Order.class,
      OrderDto.class,
      toFormatted(Order::shipDate, OrderDto::shipDate, "dd.MM.yyyy"),
      toFormattedNumber(Order::total, OrderDto::total, "$#,##0.00", Locale.US),
      toFormattedNumber(Order::quantity, OrderDto::quantity, "#,##0", Locale.US),
      toFormattedNumber(Order::weight, OrderDto::weight, "0.0##", Locale.US)
    );
  }

  @Nested
  @DisplayName("both ways")
  class BothWays {

    @Test
    @DisplayName("forward formats each value in its pattern, and backward and patch parse the text back")
    void formatsAndParses() {
      final var mapper = orderMapper();
      final var dto = mapper.forward(ORDER);
      assertEquals(new OrderDto("31.12.2026", "$1,234.50", "3", "2.25"), dto);
      assertEquals(new Order(LocalDate.of(2026, 12, 31), new BigDecimal("1234.50"), 3, 2.25), mapper.backward(dto));
      final var patched = mapper.patch(ORDER, new OrderDto("01.01.2027", null, "1,200", null));
      assertEquals(new Order(LocalDate.of(2027, 1, 1), new BigDecimal("1234.5"), 1200, 2.25), patched);
    }

    @Test
    @DisplayName("a null value converts to null, and null text into a primitive writes its default")
    void nullsConvert() {
      final var mapper = orderMapper();
      assertEquals(new OrderDto(null, null, "0", "0.0"), mapper.forward(new Order(null, null, 0, 0)));
      assertEquals(new Order(null, null, 0, 0), mapper.backward(new OrderDto(null, null, null, null)));
    }

    @Test
    @DisplayName("an Instant is written and read in the zone the row is given")
    void anInstantTakesItsZone() {
      final var mapper = Telescope.mapper(
        Event.class,
        EventDto.class,
        toFormatted(Event::at, EventDto::at, "yyyy-MM-dd HH:mm", ZoneOffset.ofHours(2)),
        toFormatted(Event::opens, EventDto::opens, "HH:mm")
      );
      final var event = new Event(Instant.parse("2026-12-31T22:30:00Z"), LocalTime.of(9, 15));
      final var dto = mapper.forward(event);
      assertEquals(new EventDto("2027-01-01 00:30", "09:15"), dto);
      assertEquals(event, mapper.backward(dto));
    }

    @Test
    @DisplayName("without a locale, numbers take the symbols of the default format locale")
    void theDefaultLocaleGivesTheSymbols() {
      final var mapper = Telescope.mapper(
        Priced.class,
        PricedDto.class,
        toFormattedNumber(Priced::total, PricedDto::total, "#,##0.00")
      );
      final var expected = new DecimalFormat("#,##0.00").format(new BigDecimal("1234.5"));
      assertEquals(expected, mapper.forward(PRICED).total());
      final var german = Telescope.mapper(
        Priced.class,
        PricedDto.class,
        toFormattedNumber(Priced::total, PricedDto::total, "#,##0.00", Locale.GERMANY)
      );
      assertEquals("1.234,50", german.forward(PRICED).total());
      assertEquals(new BigDecimal("1234.50"), german.backward(new PricedDto("1.234,50")).total());
    }

    @Test
    @DisplayName("a bean target is named by its property in a failure")
    void aBeanPropertyIsNamedWithoutItsPrefix() {
      final var mapper = Telescope.mapper(
        Shipment.class,
        Label.class,
        toFormatted(Shipment::day, Label::getDay, "dd.MM.yyyy")
      );
      final var label = new Label();
      label.setDay("31-12-2026");
      final var failure = assertThrows(IllegalArgumentException.class, () -> mapper.backward(label));
      assertEquals(
        PairingMessages.notAValue("day", "31-12-2026", "a LocalDate in the format \"dd.MM.yyyy\""),
        failure.getMessage()
      );
    }

    @Test
    @DisplayName("concurrent calls through one number row each format and parse their own value")
    void aNumberRowIsSafeAcrossThreads() throws Exception {
      final var mapper = orderMapper();
      final var seen = ConcurrentHashMap.<String>newKeySet();
      try (final var pool = Executors.newFixedThreadPool(8)) {
        final var calls = new ArrayList<Future<?>>();
        for (var t = 0; t < 8; t++) {
          final var offset = t * 100_000;
          calls.add(
            pool.submit(() -> {
              for (var i = 0; i < 20_000; i++) {
                final var value = BigDecimal.valueOf(offset + i);
                final var dto = mapper.forward(new Order(null, value, offset + i, 0));
                assertEquals(value.setScale(2), mapper.backward(dto).total());
                assertEquals(offset + i, mapper.backward(dto).quantity());
                seen.add(dto.total());
              }
            })
          );
        }
        for (final var call : calls) call.get();
      }
      assertEquals(160_000, seen.size());
    }
  }

  record Counts(byte small, Short mid, float ratio, BigInteger big) {}

  record CountsDto(String small, String mid, String ratio, String big) {}

  @Nested
  @DisplayName("each number class")
  class NumberClasses {

    private Mapper<Counts, CountsDto> mapper() {
      return Telescope.mapper(
        Counts.class,
        CountsDto.class,
        toFormattedNumber(Counts::small, CountsDto::small, "0", Locale.US),
        toFormattedNumber(Counts::mid, CountsDto::mid, "#,##0", Locale.US),
        toFormattedNumber(Counts::ratio, CountsDto::ratio, "0.0#", Locale.US),
        toFormattedNumber(Counts::big, CountsDto::big, "#,##0", Locale.US)
      );
    }

    @Test
    @DisplayName("a byte, a Short, a float and a BigInteger each format and read back as their own class")
    void eachClassReadsBack() {
      final var counts = new Counts((byte) -7, (short) 1200, 0.25f, new BigInteger("12345678901234567890"));
      final var dto = mapper().forward(counts);
      assertEquals(new CountsDto("-7", "1,200", "0.25", "12,345,678,901,234,567,890"), dto);
      assertEquals(counts, mapper().backward(dto));
    }

    @Test
    @DisplayName("a value past a byte's range does not read back into one")
    void aByteOverflowFails() {
      final var failure = assertThrows(IllegalArgumentException.class, () ->
        mapper().backward(new CountsDto("300", null, null, null))
      );
      assertEquals("'small' got \"300\", not a byte in the format \"0\"", failure.getMessage());
      assertInstanceOf(ArithmeticException.class, failure.getCause());
    }
  }

  @Nested
  @DisplayName("text that does not read back")
  class ParseFailures {

    @Test
    @DisplayName("a date in another format fails naming the property, the text and the format")
    void aDateInAnotherFormat() {
      final var failure = assertThrows(IllegalArgumentException.class, () ->
        orderMapper().backward(new OrderDto("2026/12/31", null, null, null))
      );
      assertEquals("'shipDate' got \"2026/12/31\", not a LocalDate in the format \"dd.MM.yyyy\"", failure.getMessage());
      assertInstanceOf(DateTimeException.class, failure.getCause());
    }

    @Test
    @DisplayName("a number with text after it fails, rather than reading the number before it")
    void trailingTextFails() {
      final var failure = assertThrows(IllegalArgumentException.class, () ->
        orderMapper().backward(new OrderDto(null, "$12.50 off", null, null))
      );
      assertEquals("'total' got \"$12.50 off\", not a BigDecimal in the format \"$#,##0.00\"", failure.getMessage());
    }

    @Test
    @DisplayName("a fraction or an overflow does not read back into an int")
    void anIntTakesOnlyAnExactValue() {
      final var mapper = orderMapper();
      for (final var text : List.of("1.5", "3,000,000,000")) {
        final var failure = assertThrows(IllegalArgumentException.class, () ->
          mapper.backward(new OrderDto(null, null, text, null))
        );
        assertEquals("'quantity' got \"" + text + "\", not an int in the format \"#,##0\"", failure.getMessage());
      }
    }
  }

  @Nested
  @DisplayName("refused when the row is built")
  class Refusals {

    @Test
    @DisplayName("a pattern the JDK does not compile is refused, naming the row and the property")
    void anInvalidPattern() {
      final var date = assertThrows(IllegalArgumentException.class, () ->
        toFormatted(Order::shipDate, OrderDto::shipDate, "dd.MM.yyyy{")
      );
      assertEquals(
        "Mapping.toFormatted(...) on 'shipDate': \"dd.MM.yyyy{\" is not a date and time pattern:" +
          " Pattern includes reserved character: '{'.",
        date.getMessage()
      );
      final var number = assertThrows(IllegalArgumentException.class, () ->
        toFormattedNumber(Order::total, OrderDto::total, "#.##.0")
      );
      assertTrue(
        number.getMessage().startsWith("Mapping.toFormattedNumber(...) on 'total': \"#.##.0\" is not a number pattern"),
        number::getMessage
      );
    }

    @Test
    @DisplayName("a pattern naming a field the class lacks, or one that does not read back, is refused")
    void aPatternThatDoesNotFitTheClass() {
      final var missing = assertThrows(IllegalArgumentException.class, () ->
        toFormatted(Order::shipDate, OrderDto::shipDate, "dd.MM.yyyy HH:mm")
      );
      assertTrue(missing.getMessage().contains("names a field a LocalDate does not have"), missing::getMessage);
      final var lossy = assertThrows(IllegalArgumentException.class, () ->
        toFormatted(Order::shipDate, OrderDto::shipDate, "MM/yyyy")
      );
      assertTrue(lossy.getMessage().contains("does not read back as a LocalDate"), lossy::getMessage);
      final var century = assertThrows(IllegalArgumentException.class, () ->
        toFormatted(Order::shipDate, OrderDto::shipDate, "dd.MM.yy")
      );
      assertTrue(
        century.getMessage().contains("writes 1899-12-31 as \"31.12.99\", which reads back as 2099-12-31"),
        century::getMessage
      );
      // Forward only, the same pattern is taken.
      final var month = Telescope.mapperForward(
        Order.class,
        OrderDto.class,
        toFormattedOneWay(Order::shipDate, OrderDto::shipDate, "MM/yyyy")
      );
      assertEquals("12/2026", month.forward(ORDER).shipDate());
    }

    @Test
    @DisplayName("an Instant without a zone is refused, naming the overload that takes one")
    void anInstantNeedsAZone() {
      final var failure = assertThrows(IllegalArgumentException.class, () ->
        toFormatted(Event::at, EventDto::at, "yyyy-MM-dd")
      );
      assertEquals(PairingMessages.formatNeedsZone("toFormatted", "at", "yyyy-MM-dd"), failure.getMessage());
    }

    @Test
    @DisplayName("a forward-only row is refused by a mapper that converts both ways, and taken forward only")
    void aOneWayRowNeedsAForwardMapper() {
      final var failure = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(
          Priced.class,
          PricedDto.class,
          toFormattedNumberOneWay(Priced::total, PricedDto::total, "$#,##0.00", Locale.US)
        )
      );
      assertEquals(
        PairingMessages.formatOneWayInMapper(
          "Telescope.mapper",
          "Priced",
          "PricedDto",
          "toFormattedNumberOneWay",
          "total"
        ),
        failure.getMessage()
      );
      final var forward = Telescope.mapperForward(
        Priced.class,
        PricedDto.class,
        toFormattedNumberOneWay(Priced::total, PricedDto::total, "$#,##0.00", Locale.US)
      );
      assertEquals("$1,234.50", forward.forward(PRICED).total());
      assertNull(forward.forward(new Priced(null)).total());
    }
  }
}
