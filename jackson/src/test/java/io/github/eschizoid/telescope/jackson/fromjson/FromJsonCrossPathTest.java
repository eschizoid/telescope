package io.github.eschizoid.telescope.jackson.fromjson;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.jackson.JsonBinder;
import io.github.eschizoid.telescope.jackson.TelescopeJson;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The binder generated for {@code @FromJson} against the binder {@code TelescopeJson.fromJson}
 * builds, on the same type and the same document: each document of the runtime binder's own tests
 * is read through both, and the two must give an equal value, or refuse it with the same exception
 * class and the same message, location included.
 */
class FromJsonCrossPathTest {

  /** One document, read into {@code type}, as an object or, where {@code list} is set, an array. */
  private record Case(Class<?> type, String json, boolean list) {
    static Case of(final Class<?> type, final String json) {
      return new Case(type, json, false);
    }

    static Case list(final Class<?> type, final String json) {
      return new Case(type, json, true);
    }
  }

  private static final Map<Class<?>, JsonBinder<?>> GENERATED = Map.ofEntries(
    Map.entry(Order.class, OrderFromJson.BINDER),
    Map.entry(Line.class, LineFromJson.BINDER),
    Map.entry(Scalars.class, ScalarsFromJson.BINDER),
    Map.entry(Tree.class, TreeFromJson.BINDER),
    Map.entry(Keyed.class, KeyedFromJson.BINDER),
    Map.entry(Nesting.class, NestingFromJson.BINDER),
    Map.entry(Outer.class, OuterFromJson.BINDER),
    Map.entry(Customer.class, CustomerFromJson.BINDER),
    Map.entry(Signed.class, SignedFromJson.BINDER),
    Map.entry(Signups.class, SignupsFromJson.BINDER),
    Map.entry(Partner.class, PartnerFromJson.BINDER),
    Map.entry(Feed.class, FeedFromJson.BINDER),
    Map.entry(Renamed.class, RenamedFromJson.BINDER),
    Map.entry(Shown.class, ShownFromJson.BINDER),
    Map.entry(Mandatory.class, MandatoryFromJson.BINDER),
    Map.entry(NotIgnored.class, NotIgnoredFromJson.BINDER),
    Map.entry(PartnerBean.class, PartnerBeanFromJson.BINDER),
    Map.entry(BuiltBean.class, BuiltBeanFromJson.BINDER),
    Map.entry(MadeBean.class, MadeBeanFromJson.BINDER),
    Map.entry(TwoSetters.class, TwoSettersFromJson.BINDER),
    Map.entry(LooseSetter.class, LooseSetterFromJson.BINDER),
    Map.entry(Shelf.class, ShelfFromJson.BINDER)
  );

  private static final List<Case> CASES = List.of(
    Case.of(
      Order.class,
      """
      {"number":"SO-1","lines":[{"sku":"MUG","quantity":2,"price":12.50}],"status":"PAID",
       "note":"ring twice","counts":{"a":1},"tags":["x","y"],"placedOn":"2026-01-02T03:04:05Z",
       "gift":true,"version":9000000000,"weight":1.5,"grade":"A","serial":123456789012345678901234567890,
       "extra":{"n":[1,2]}}
      """
    ),
    Case.of(Order.class, "{}"),
    Case.of(
      Order.class,
      """
      {"number":null,"lines":null,"status":null,"note":null,"counts":null,"tags":null,"placedOn":null,
       "gift":null,"version":null,"weight":null,"grade":null,"serial":null,"extra":null}
      """
    ),
    Case.of(Line.class, "{\"colour\":{\"r\":[1,{\"g\":2}]},\"sku\":\"MUG\",\"more\":[[],{}],\"quantity\":3}"),
    Case.of(Line.class, "{\"sku\":\"A\",\"quantity\":5,\"sku\":\"B\",\"quantity\":null}"),
    Case.of(Outer.class, "{\"inner\":{\"id\":\"i\"},\"id\":\"o\"}"),
    Case.of(
      Tree.class,
      "{\"name\":\"root\",\"children\":[{\"name\":\"a\",\"children\":[{\"name\":\"b\"}]},{\"name\":\"c\"}]}"
    ),
    Case.of(
      Keyed.class,
      "{\"byStatus\":{\"PAID\":\"p\"},\"byId\":{\"123e4567-e89b-12d3-a456-426614174000\":1}," +
        "\"byNumber\":{\"7\":\"seven\"},\"byFlag\":{\"TRUE\":\"t\",\"false\":\"f\"}}"
    ),
    Case.of(
      Nesting.class,
      "{\"grid\":[[1,2],[],null,[3]],\"maybe\":[\"a\",null],\"groups\":{\"g\":[\"x\"],\"h\":null}}"
    ),
    Case.of(
      Scalars.class,
      """
      {"i":1,"l":2,"s":3,"b":4,"d":5.5,"f":6.5,"z":true,"c":"c","boxed":7,"decimal":8.25,"big":9,
       "text":"t","chars":"cs","id":"123e4567-e89b-12d3-a456-426614174000","locale":"en-GB"}
      """
    ),
    Case.of(Order.class, "{\"extra\":[1,9000000000,99999999999999999999,0.1,\"s\",true,false,null]}"),
    Case.of(Customer.class, "{\"name\":\"Ada\",\"visits\":3,\"tags\":[\"vip\"],\"label\":{\"ignored\":1}}"),
    Case.of(Order.class, "null"),
    Case.list(Line.class, "null"),
    Case.of(Line.class, "{\"sku\":\"x\",\"quantity\":1.5}"),
    Case.of(Line.class, "{\"quantity\":2147483648}"),
    Case.of(Scalars.class, "{\"b\":128}"),
    Case.of(Scalars.class, "{\"b\":-129}"),
    Case.of(Scalars.class, "{\"s\":40000}"),
    Case.of(Scalars.class, "{\"l\":9223372036854775808}"),
    Case.of(Scalars.class, "{\"d\":1e400}"),
    Case.of(Scalars.class, "{\"f\":1e40}"),
    Case.of(Line.class, "{\"quantity\":\"12\"}"),
    Case.of(Line.class, "{\"sku\":12}"),
    Case.of(Line.class, "{\"price\":\"1.50\"}"),
    Case.of(Scalars.class, "{\"z\":1}"),
    Case.of(Scalars.class, "{\"c\":\"ab\"}"),
    Case.of(Scalars.class, "{\"boxed\":true}"),
    Case.of(Scalars.class, "{\"big\":1.0}"),
    Case.of(Scalars.class, "{\"chars\":[]}"),
    Case.of(Scalars.class, "{\"id\":7}"),
    Case.of(Scalars.class, "{\"id\":\"not-a-uuid\"}"),
    Case.of(Scalars.class, "{\"locale\":\"::bad::\"}"),
    Case.of(Order.class, "{\"lines\":[{\"sku\":\"A\",\"quantity\":1},\n{\"sku\":\"B\",\"quantity\":1.5}]}"),
    Case.of(Order.class, "{\"status\":\"paid\"}"),
    Case.of(Order.class, "{\"placedOn\":\"yesterday\"}"),
    Case.of(Order.class, "{\"lines\":{}}"),
    Case.of(Order.class, "{\"tags\":\"x\"}"),
    Case.of(Order.class, "{\"counts\":[]}"),
    Case.of(Order.class, "{\"lines\":[4]}"),
    Case.of(Order.class, "[]"),
    Case.of(Order.class, ""),
    Case.of(Keyed.class, "{\"byNumber\":{\"x\":\"seven\"}}"),
    Case.of(Keyed.class, "{\"byStatus\":{\"paid\":\"p\"}}"),
    Case.of(Keyed.class, "{\"byFlag\":{\"yes\":\"y\"}}"),
    Case.of(Order.class, "{\"counts\":{\"a\":\"1\"}}"),
    Case.of(Line.class, "{\"sku\":"),
    Case.of(Line.class, "{\"sku\":\"A\"} x"),
    Case.of(Line.class, "{\"sku\":\"A\"} {\"sku\":\"B\"}"),
    Case.of(Line.class, "null 1"),
    Case.list(Line.class, "[{\"sku\":\"A\",\"quantity\":1},null]"),
    Case.list(Line.class, "[{},3]"),
    Case.list(Line.class, "[{\"quantity\":\"x\"}]"),
    Case.list(Line.class, "{}"),
    Case.list(Line.class, "[{\"sku\":\"A\"}] []"),
    Case.of(Signed.class, "{\"email\":\"a@b.c\"}"),
    Case.of(Signed.class, "{\"name\":\"Ada\"}"),
    Case.of(Signed.class, "{\"email\":null,\"name\":\"Ada\"}"),
    Case.of(Signups.class, "{\"signups\":[{\"email\":\"a\"},{\"name\":\"b\"}]}"),
    Case.of(
      Partner.class,
      "{\"partner_id\":\"P1\",\"name_v1\":\"Old\",\"partnerName\":\"New\",\"internal\":\"x\",\"tier\":2}"
    ),
    Case.of(Partner.class, "{\"id\":\"ignored\",\"name\":\"ignored\"}"),
    Case.of(Partner.class, "{\"partner_id\":7}"),
    Case.of(Partner.class, "{\"partnerName\":7}"),
    Case.of(
      Feed.class,
      "{\"feed_name\":\"f\",\"partner_info\":{\"partner_id\":\"P\"},\"partners\":[{\"partner_id\":\"Q\"}]}"
    ),
    Case.of(Feed.class, "{\"partner_info\":{\"partner_id\":12}}"),
    Case.of(Feed.class, "{\"partners\":[{},{\"tier\":\"high\"}]}"),
    Case.of(Renamed.class, "{\"ident\":\"a\",\"id\":\"b\"}"),
    Case.of(Shown.class, "{\"shown\":\"s\",\"kept\":\"k\"}"),
    Case.of(Mandatory.class, "{\"partner_id\":\"P\"}"),
    Case.of(Mandatory.class, "{\"name\":\"n\"}"),
    Case.of(Mandatory.class, "{\"partner_id\":null}"),
    Case.of(NotIgnored.class, "{\"a\":\"1\",\"x\":\"2\"}"),
    Case.of(NotIgnored.class, "{\"a\":\"1\",\"b\":\"2\",\"x\":\"3\"}"),
    Case.of(
      PartnerBean.class,
      "{\"partner_id\":\"P\",\"partnerName\":\"N\",\"internal\":\"i\",\"secret\":\"s\",\"shown\":\"w\",\"display_label\":\"L\"}"
    ),
    Case.of(PartnerBean.class, "{\"id\":\"x\",\"name\":\"y\",\"label\":\"z\"}"),
    Case.of(BuiltBean.class, "{\"name\":\"n\",\"count\":2}"),
    Case.of(BuiltBean.class, "{}"),
    Case.of(BuiltBean.class, "{\"count\":1.5}"),
    Case.of(MadeBean.class, "{\"name\":\"a\",\"scores\":[1,2],\"rank\":3}"),
    Case.of(MadeBean.class, "{\"rank\":null}"),
    Case.of(MadeBean.class, "{\"scores\":\"x\"}"),
    Case.of(TwoSetters.class, "{\"label\":\"x\",\"size\":4}"),
    Case.of(TwoSetters.class, "{\"size\":null}"),
    Case.of(TwoSetters.class, "{\"size\":\"x\"}"),
    Case.of(LooseSetter.class, "{\"name\":\"n\"}"),
    Case.of(LooseSetter.class, "{\"name\":5}"),
    Case.of(Shelf.class, "{\"item\":{\"q\":1,\"tag\":\"t\"},\"items\":[{\"q\":2},null]}"),
    Case.of(Shelf.class, "{\"items\":[{\"q\":1.5}]}")
  );

  @Test
  @DisplayName("the generated binder reads every document as the runtime binder does, or refuses it in the same words")
  void bothBindersAgree() {
    final var failures = new ArrayList<String>();
    for (final var c : CASES) {
      final var runtime = TelescopeJson.fromJson(c.type());
      final var generated = GENERATED.get(c.type());
      final Function<JsonBinder<?>, Object> read = binder ->
        c.list() ? binder.readList(c.json()) : binder.read(c.json());
      final var expected = outcome(() -> read.apply(runtime));
      final var actual = outcome(() -> read.apply(generated));
      if (!expected.equals(actual)) failures.add(
        c.type().getSimpleName() + " " + c.json() + "\n  runtime   " + expected + "\n  generated " + actual
      );
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  @Test
  @DisplayName("the generated binder explains the same binding plan as the runtime binder")
  void bothBindersExplainAlike() {
    final var failures = new ArrayList<String>();
    for (final var entry : GENERATED.entrySet()) {
      final var runtime = TelescopeJson.fromJson(entry.getKey()).explain().nodes();
      final var generated = entry.getValue().explain().nodes();
      // A bean's properties come from reflection in an order the platform does not fix.
      if (!new HashSet<>(runtime).equals(new HashSet<>(generated)) || runtime.size() != generated.size()) {
        failures.add(entry.getKey().getSimpleName() + ": runtime " + runtime + ", generated " + generated);
      }
    }
    assertEquals(List.of(), failures, () -> String.join("\n", failures));
  }

  /** A read that yields a value or throws. */
  private interface Read {
    Object run();
  }

  private static String outcome(final Read read) {
    try {
      return "value " + read.run();
    } catch (final RuntimeException e) {
      return "throws " + e.getClass().getName() + ": " + e.getMessage();
    }
  }
}
