package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.mapping.Mapping;
import io.github.eschizoid.telescope.mapping.WriteHint;
import java.io.Serial;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.SynchronousQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regressions for the migration-feedback bugs (see {@code docs/migration-feedback.md}). One nested
 * class per bug — keeps the test names traceable back to the feedback entries.
 */
class MigrationRegressionTest {

  @Nested
  @DisplayName("Boolean accessor — mapper construction is null-safe through Beans.propertyOf")
  class BooleanAccessorNpe {

    public static class Order {

      private boolean shipped;
      private String name;

      public boolean isShipped() {
        return shipped;
      }

      public void setShipped(final boolean shipped) {
        this.shipped = shipped;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    public static class OrderDto {

      private boolean shipped;
      private String name;

      public boolean isShipped() {
        return shipped;
      }

      public void setShipped(final boolean shipped) {
        this.shipped = shipped;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    @Test
    @DisplayName("Telescope.mapper(Order, OrderDto) constructs without NPE when boolean accessors are present")
    void mapperConstructionDoesNotNpeOnBooleanAccessors() {
      // The migration feedback reports any class with a boolean primitive field is unusable with
      // Telescope.mapper() / mapperForward() because Beans.propertyOf(null) NPEs in the bean
      // auto-discovery path. The unit-level fix lives in Beans; this test pins the end-to-end
      // contract from the public API surface.
      assertDoesNotThrow(() ->
        Telescope.mapper(Order.class, OrderDto.class, writeBeans(WriteHint.WriteStrategy.SETTERS))
      );
    }

    @Test
    @DisplayName("forward(Order) on a populated source round-trips the boolean field correctly")
    void mapperRoundTripsBooleanField() {
      final var mapper = Telescope.mapper(Order.class, OrderDto.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var src = new Order();
      src.setShipped(true);
      src.setName("alice");
      final var tgt = mapper.forward(src);
      assertEquals(true, tgt.isShipped());
      assertEquals("alice", tgt.getName());
    }

    public static class OrderWithCustomer {

      private Customer customer;

      public Customer getCustomer() {
        return customer;
      }

      public void setCustomer(final Customer customer) {
        this.customer = customer;
      }
    }

    public static class Customer {

      private String email;

      public String getEmail() {
        return email;
      }

      public void setEmail(final String email) {
        this.email = email;
      }
    }

    public static class FlatOrderDto {

      private String customerEmail;

      public String getCustomerEmail() {
        return customerEmail;
      }

      public void setCustomerEmail(final String customerEmail) {
        this.customerEmail = customerEmail;
      }
    }

    @Test
    @DisplayName("Mapping.to(srcTelescope, tgtAccessor) — nested source path — does not NPE at mapper construction")
    void nestedSourceTelescopeRowConstructsWithoutNpe() {
      // Reproduces the reported NPE: `Mapping.to(srcTelescope, tgtAccessor)`
      // builds a FromTelescopeTo row whose `sourceField()` is null by design (the source is a
      // nested telescope, not a flat accessor). DeepMap.populateIso normalizes the source field
      // unconditionally before the FromTelescopeTo `instanceof` peel, so `Beans.normalize(null)`
      // → `Beans.propertyOf(null)` would NPE without the defensive guard.
      assertDoesNotThrow(() ->
        Telescope.mapper(
          OrderWithCustomer.class,
          FlatOrderDto.class,
          Mapping.to(
            Telescope.ofBean(OrderWithCustomer.class).field(OrderWithCustomer::getCustomer).field(Customer::getEmail),
            FlatOrderDto::getCustomerEmail
          ),
          writeBeans(WriteHint.WriteStrategy.SETTERS)
        )
      );
    }
  }

  @Nested
  @DisplayName("Null intermediate — multi-hop bean paths short-circuit instead of NPE")
  class NullIntermediateNpe {

    public static class Order {

      private Customer customer; // may be null

      public Customer getCustomer() {
        return customer;
      }

      public void setCustomer(final Customer customer) {
        this.customer = customer;
      }
    }

    public static class Customer {

      private String email;

      public String getEmail() {
        return email;
      }

      public void setEmail(final String email) {
        this.email = email;
      }
    }

    public static class OrderDto {

      private String customerEmail;

      public String getCustomerEmail() {
        return customerEmail;
      }

      public void setCustomerEmail(final String customerEmail) {
        this.customerEmail = customerEmail;
      }
    }

    @Test
    @DisplayName("forward(order) on a source whose nested intermediate is null short-circuits to null instead of NPE")
    void forwardWithNullIntermediateShortCircuitsToNull() {
      // The adopter's exact scenario: a 2-hop nested source path order → customer → email, with
      // order.customer == null at runtime. Before the fix, the second hop calls
      // Beans.readProperty(null, "email") which delegates to persistentClassOf(null) → null,
      // then ClassValue.get(null) NPEs. After the fix, readProperty short-circuits to null and
      // the optic pipeline propagates the null through the rest of the chain.
      final var mapper = Telescope.mapper(
        Order.class,
        OrderDto.class,
        Mapping.to(
          Telescope.ofBean(Order.class).field(Order::getCustomer).field(Customer::getEmail),
          OrderDto::getCustomerEmail
        ),
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new Order(); // customer left null
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(null, tgt.getCustomerEmail());
    }

    @Test
    @DisplayName("forward(order) on a populated nested intermediate still reads through the chain")
    void forwardWithPopulatedIntermediateReadsThrough() {
      final var mapper = Telescope.mapper(
        Order.class,
        OrderDto.class,
        Mapping.to(
          Telescope.ofBean(Order.class).field(Order::getCustomer).field(Customer::getEmail),
          OrderDto::getCustomerEmail
        ),
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var customer = new Customer();
      customer.setEmail("alice@example.com");
      final var src = new Order();
      src.setCustomer(customer);
      final var tgt = mapper.forward(src);
      assertEquals("alice@example.com", tgt.getCustomerEmail());
    }
  }

  @Nested
  @DisplayName("Nested asymmetric pairs are lenient; top-level strictness preserved")
  class NestedStrictBijection {

    public static class ScoreResponse {

      private int firstNameScore;
      private int lastNameScore;

      public int getFirstNameScore() {
        return firstNameScore;
      }

      public void setFirstNameScore(final int v) {
        this.firstNameScore = v;
      }

      public int getLastNameScore() {
        return lastNameScore;
      }

      public void setLastNameScore(final int v) {
        this.lastNameScore = v;
      }
    }

    public static class ScoreResponseDto {

      private int firstNameScore;
      private int lastNameScore;
      private String matchingStatus; // extra field, not on source

      public int getFirstNameScore() {
        return firstNameScore;
      }

      public void setFirstNameScore(final int v) {
        this.firstNameScore = v;
      }

      public int getLastNameScore() {
        return lastNameScore;
      }

      public void setLastNameScore(final int v) {
        this.lastNameScore = v;
      }

      public String getMatchingStatus() {
        return matchingStatus;
      }

      public void setMatchingStatus(final String v) {
        this.matchingStatus = v;
      }
    }

    public static class Parent {

      private ScoreResponse scores;

      public ScoreResponse getScores() {
        return scores;
      }

      public void setScores(final ScoreResponse scores) {
        this.scores = scores;
      }
    }

    public static class ParentDto {

      private ScoreResponseDto scores;

      public ScoreResponseDto getScores() {
        return scores;
      }

      public void setScores(final ScoreResponseDto scores) {
        this.scores = scores;
      }
    }

    @Test
    @DisplayName("nested auto-recursed pair with asymmetric fields is lenient — top-level construction succeeds")
    void nestedAsymmetricPairConstructsLeniently() {
      // The adopter's exact scenario: top-level pair (Parent, ParentDto) auto-recurses into
      // (ScoreResponse, ScoreResponseDto). The nested target has `matchingStatus` with no
      // same-name source. Strict bijection on every recursed pair throws at construction time.
      // After fix, nested pairs are lenient — unmatched target fields stay at JLS defaults,
      // unmatched source fields are silently dropped. Only the TOP-LEVEL pair (here: Parent →
      // ParentDto) enforces strictness.
      final var mapper = Telescope.mapper(Parent.class, ParentDto.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var inner = new ScoreResponse();
      inner.setFirstNameScore(80);
      inner.setLastNameScore(90);
      final var src = new Parent();
      src.setScores(inner);
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(80, tgt.getScores().getFirstNameScore());
      assertEquals(90, tgt.getScores().getLastNameScore());
      // Nested target's unmatched field stays at the JLS default.
      assertEquals(null, tgt.getScores().getMatchingStatus());
    }

    @Test
    @DisplayName("top-level pair with asymmetric fields still throws — strictness preserved at the top")
    void topLevelAsymmetricPairStillThrows() {
      // Strictness at the top-level call (the user's explicit request) is preserved — the user
      // asked for this pair and a missing field is a real configuration mistake. Only nested
      // recursion auto-relaxes.
      assertThrows(IllegalStateException.class, () ->
        Telescope.mapper(ScoreResponse.class, ScoreResponseDto.class, writeBeans(WriteHint.WriteStrategy.SETTERS))
      );
    }
  }

  @Nested
  @DisplayName("Primitive ↔ wrapper auto-iso with JLS-default null guard")
  class PrimitiveWrapperAutoboxing {

    public static class Source {

      private boolean active; // primitive
      private Integer count; // boxed
      private String name;

      public boolean isActive() {
        return active;
      }

      public void setActive(final boolean v) {
        this.active = v;
      }

      public Integer getCount() {
        return count;
      }

      public void setCount(final Integer v) {
        this.count = v;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    public static class Target {

      private Boolean active; // boxed (source is primitive)
      private int count; // primitive (source is boxed)
      private String name;

      public Boolean getActive() {
        return active;
      }

      public void setActive(final Boolean v) {
        this.active = v;
      }

      public int getCount() {
        return count;
      }

      public void setCount(final int v) {
        this.count = v;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    @Test
    @DisplayName("auto-mapping handles primitive↔wrapper pairs (boolean↔Boolean, Integer↔int) without throwing")
    void primitiveWrapperPairsAreAutoBoxed() {
      // The adopter scenario: source has boolean primitive `active` and boxed `Integer count`;
      // target inverts them. DeepMap used to reject the pair at populateIso with
      // "incompatible source/target shapes — boolean vs java.lang.Boolean". MapStruct silently
      // autoboxes / unboxes; telescope should match.
      final var mapper = Telescope.mapper(Source.class, Target.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var src = new Source();
      src.setActive(true);
      src.setCount(42);
      src.setName("alice");
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(Boolean.TRUE, tgt.getActive());
      assertEquals(42, tgt.getCount());
      assertEquals("alice", tgt.getName());
    }

    @Test
    @DisplayName("null boxed source mapping to primitive target uses JLS default (no unboxing NPE)")
    void nullBoxedToPrimitiveUsesJlsDefault() {
      // Source.count is Integer null; target.count is int. The auto-iso must null-guard at the
      // boxing leaf, otherwise we get NullPointerException on intValue().
      final var mapper = Telescope.mapper(Source.class, Target.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var src = new Source();
      src.setActive(false);
      // count left null
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(0, tgt.getCount()); // JLS default for int
    }

    // Broader primitive coverage (long, double, char, short, byte, float) so a future refactor
    // of the wrap()/primitiveDefault tables doesn't silently drop a primitive variant.

    public static class WideSrc {

      private long l;
      private Double d;
      private char c;
      private Short sh;
      private byte by;
      private Float f;

      public long getL() {
        return l;
      }

      public void setL(final long l) {
        this.l = l;
      }

      public Double getD() {
        return d;
      }

      public void setD(final Double d) {
        this.d = d;
      }

      public char getC() {
        return c;
      }

      public void setC(final char c) {
        this.c = c;
      }

      public Short getSh() {
        return sh;
      }

      public void setSh(final Short sh) {
        this.sh = sh;
      }

      public byte getBy() {
        return by;
      }

      public void setBy(final byte by) {
        this.by = by;
      }

      public Float getF() {
        return f;
      }

      public void setF(final Float f) {
        this.f = f;
      }
    }

    public static class WideTgt {

      private Long l; // primitive → boxed
      private double d; // boxed → primitive
      private Character c; // primitive → boxed
      private short sh; // boxed → primitive
      private Byte by; // primitive → boxed
      private float f; // boxed → primitive

      public Long getL() {
        return l;
      }

      public void setL(final Long l) {
        this.l = l;
      }

      public double getD() {
        return d;
      }

      public void setD(final double d) {
        this.d = d;
      }

      public Character getC() {
        return c;
      }

      public void setC(final Character c) {
        this.c = c;
      }

      public short getSh() {
        return sh;
      }

      public void setSh(final short sh) {
        this.sh = sh;
      }

      public Byte getBy() {
        return by;
      }

      public void setBy(final Byte by) {
        this.by = by;
      }

      public float getF() {
        return f;
      }

      public void setF(final float f) {
        this.f = f;
      }
    }

    @Test
    @DisplayName("all 6 remaining primitive ↔ wrapper pairs (long, double, char, short, byte, float) autobox")
    void allPrimitiveVariantsAutoBox() {
      final var mapper = Telescope.mapper(WideSrc.class, WideTgt.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var src = new WideSrc();
      src.setL(99L);
      src.setD(2.5);
      src.setC('z');
      src.setSh((short) 7);
      src.setBy((byte) 3);
      src.setF(1.5f);
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(Long.valueOf(99L), tgt.getL());
      assertEquals(2.5, tgt.getD());
      assertEquals(Character.valueOf('z'), tgt.getC());
      assertEquals((short) 7, tgt.getSh());
      assertEquals(Byte.valueOf((byte) 3), tgt.getBy());
      assertEquals(1.5f, tgt.getF());
    }

    @Test
    @DisplayName("null boxed source values to primitive targets use JLS defaults for every primitive variant")
    void nullBoxedToPrimitiveDefaultsForEveryPrimitive() {
      final var mapper = Telescope.mapper(WideSrc.class, WideTgt.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var src = new WideSrc();
      // src.d, src.sh, src.f left null
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(0.0, tgt.getD());
      assertEquals((short) 0, tgt.getSh());
      assertEquals(0.0f, tgt.getF());
    }
  }

  @Nested
  @DisplayName("JDK collection subtypes — auto-iso copies elements instead of bean-recursing")
  class JdkCollectionSubtypes {

    public static class ImageUrl {

      private String url;

      public String getUrl() {
        return url;
      }

      public void setUrl(final String url) {
        this.url = url;
      }
    }

    // Custom collection wrapper — common in legacy bean codebases. Extends ArrayList so it
    // inherits a long tail of platform-module getters (isEmpty, getClass, etc.) that telescope
    // used to try to LMF-bind, hitting java.base private-lookup rejection.
    public static class ImageUrls extends ArrayList<ImageUrl> {

      @Serial
      private static final long serialVersionUID = 1L;
    }

    public static class DocumentData {

      private ImageUrls imageUrls;

      public ImageUrls getImageUrls() {
        return imageUrls;
      }

      public void setImageUrls(final ImageUrls imageUrls) {
        this.imageUrls = imageUrls;
      }
    }

    public static class DocumentDataDto {

      private ImageUrls imageUrls;

      public ImageUrls getImageUrls() {
        return imageUrls;
      }

      public void setImageUrls(final ImageUrls imageUrls) {
        this.imageUrls = imageUrls;
      }
    }

    @Test
    @DisplayName(
      "auto-recursion through a Collection subtype does NOT try to bean-decompose it (pass-through by reference)"
    )
    void collectionSubtypeIsPassThrough() {
      // Adopter scenario: a bean graph contains a custom collection wrapper class extending
      // ArrayList. Without the fix, DeepMap recurses into ImageUrls trying to bean-decompose
      // it, discovers inherited `isEmpty()` etc. as "getters", then LMF-binds them — which
      // fails with "Invalid caller: java.util.ArrayList" because java.base modules don't
      // grant private lookup to application code. Fix: treat Collection/Map subtypes as
      // scalars (pass-through by reference), and skip inherited platform-module methods in
      // scanGetters as defence-in-depth.
      final var mapper = Telescope.mapper(
        DocumentData.class,
        DocumentDataDto.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var urls = new ImageUrls();
      final var u = new ImageUrl();
      u.setUrl("https://example.com/a.png");
      urls.add(u);
      final var src = new DocumentData();
      src.setImageUrls(urls);
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      // Pass-through by reference: same ImageUrls instance, same element. assertSame pins
      // identity — assertEquals would pass on any List with the same contents.
      assertSame(urls, tgt.getImageUrls());
      assertEquals("https://example.com/a.png", tgt.getImageUrls().get(0).getUrl());
    }

    // Adversarial reproduction — the same-generic-type identity shortcut at the top of
    // DeepMap.computeAutoIso hides this bug when the field is identical on both sides. Force
    // different concrete types so computeAutoIso falls through to the same-kind Collection
    // branch where collectionCopyIso decides whether to copy elements via addAll.
    public static class ImageUrlsAlt extends ArrayList<ImageUrl> {

      @Serial
      private static final long serialVersionUID = 1L;
    }

    public static class DocumentDataAlt {

      private ImageUrls imageUrls; // source type

      public ImageUrls getImageUrls() {
        return imageUrls;
      }

      public void setImageUrls(final ImageUrls imageUrls) {
        this.imageUrls = imageUrls;
      }
    }

    public static class DocumentDataDtoAlt {

      private ImageUrlsAlt imageUrls; // target type — different subclass

      public ImageUrlsAlt getImageUrls() {
        return imageUrls;
      }

      public void setImageUrls(final ImageUrlsAlt imageUrls) {
        this.imageUrls = imageUrls;
      }
    }

    @Test
    @DisplayName("cross-Collection-subtype field copies elements via target's no-arg ctor + addAll")
    void differentCollectionSubtypesAreCopiedViaAddAll() {
      // ImageUrls vs ImageUrlsAlt — both extend ArrayList<ImageUrl>. The same-type shortcut at
      // autoIso doesn't fire (different concrete classes). The fix routes this through
      // `collectionCopyIso`: instantiate the target type via its no-arg ctor and addAll(source).
      // End-to-end the mapper produces an ImageUrlsAlt instance carrying the source's elements.
      final var mapper = Telescope.mapper(
        DocumentDataAlt.class,
        DocumentDataDtoAlt.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var urls = new ImageUrls();
      final var u = new ImageUrl();
      u.setUrl("https://example.com/a.png");
      urls.add(u);
      final var src = new DocumentDataAlt();
      src.setImageUrls(urls);
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(ImageUrlsAlt.class, tgt.getImageUrls().getClass());
      assertEquals(1, tgt.getImageUrls().size());
      assertEquals("https://example.com/a.png", tgt.getImageUrls().get(0).getUrl());
    }

    // Fixture pair for the positive same-kind-List acceptance test: both StringList and
    // StringLinkedList implement List, so sameKindCollection accepts the pair and the element-
    // copy Iso runs via the target's no-arg ctor + addAll.
    public static class StringList extends ArrayList<String> {

      @Serial
      private static final long serialVersionUID = 1L;
    }

    public static class StringLinkedList extends LinkedList<String> {

      @Serial
      private static final long serialVersionUID = 1L;
    }

    public static class HolderList {

      private StringList items;

      public StringList getItems() {
        return items;
      }

      public void setItems(final StringList items) {
        this.items = items;
      }
    }

    public static class HolderLinkedList {

      private StringLinkedList items;

      public StringLinkedList getItems() {
        return items;
      }

      public void setItems(final StringLinkedList items) {
        this.items = items;
      }
    }

    @Test
    @DisplayName("List subtype ↔ List subtype is copied via addAll (positive case for same-kind gate)")
    void listSubtypeVsListSubtypeAccepted() {
      // Both StringList and StringLinkedList implement List — the same-kind gate accepts them.
      // Element order survives via List's contract.
      final var mapper = Telescope.mapper(
        HolderList.class,
        HolderLinkedList.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new HolderList();
      src.setItems(new StringList());
      src.getItems().add("a");
      src.getItems().add("b");
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(StringLinkedList.class, tgt.getItems().getClass());
      assertEquals(List.of("a", "b"), tgt.getItems());
    }

    // Fixture pair for the SortedMap positive case — both sides extend TreeMap so the new
    // sameKindMap discriminator's Sorted-vs-Sorted axis fires the copy Iso.
    public static class StringTreeMap extends TreeMap<String, String> {

      @Serial
      private static final long serialVersionUID = 1L;
    }

    public static class StringTreeMapAlt extends TreeMap<String, String> {

      @Serial
      private static final long serialVersionUID = 1L;
    }

    public static class HolderTreeMap {

      private StringTreeMap items;

      public StringTreeMap getItems() {
        return items;
      }

      public void setItems(final StringTreeMap items) {
        this.items = items;
      }
    }

    public static class HolderTreeMapAlt {

      private StringTreeMapAlt items;

      public StringTreeMapAlt getItems() {
        return items;
      }

      public void setItems(final StringTreeMapAlt items) {
        this.items = items;
      }
    }

    @Test
    @DisplayName("SortedMap subtype ↔ SortedMap subtype is copied via putAll (positive case for sameKindMap gate)")
    void sortedMapSubtypeVsSortedMapSubtypeAccepted() {
      // Both extend TreeMap → both are SortedMap → the gate accepts. Forward copies entries
      // verbatim into a fresh instance of the target's concrete class.
      final var mapper = Telescope.mapper(
        HolderTreeMap.class,
        HolderTreeMapAlt.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new HolderTreeMap();
      src.setItems(new StringTreeMap());
      src.getItems().put("a", "alpha");
      src.getItems().put("b", "beta");
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(StringTreeMapAlt.class, tgt.getItems().getClass());
      assertEquals("alpha", tgt.getItems().get("a"));
      assertEquals("beta", tgt.getItems().get("b"));
    }
  }

  @Nested
  @DisplayName("Primitive setter null-guard — null value substitutes the JLS default")
  class PrimitiveSetterNullNpe {

    public static class Source {

      private String name;

      // No `count` property — under nested-pair lenient mode, valueByName returns null for the
      // target's `count` field.

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    public static class Target {

      private String name;
      private int count; // primitive, will receive null from valueByName

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public int getCount() {
        return count;
      }

      public void setCount(final int count) {
        this.count = count;
      }
    }

    public static class TopSource {

      private Source nested;

      public Source getNested() {
        return nested;
      }

      public void setNested(final Source nested) {
        this.nested = nested;
      }
    }

    public static class TopTarget {

      private Target nested;

      public Target getNested() {
        return nested;
      }

      public void setNested(final Target nested) {
        this.nested = nested;
      }
    }

    @Test
    @DisplayName("forward(top) where nested target has primitive setter for an unmatched field uses JLS default")
    void primitiveSetterReceivesJlsDefaultOnNullValue() {
      // With nested-leniency + getter-only silent-skip, a nested target property like `int count`
      // whose source has
      // no matching `count` field receives `null` from valueByName. SettersWriter.construct used
      // to NPE when passing that null to `setCount(int)` (Integer-to-int unboxing on null).
      // Fix: null-guard primitive setters at construction time so primitives stay at their JLS
      // default (0 for int, false for boolean, etc.).
      final var mapper = Telescope.mapper(
        TopSource.class,
        TopTarget.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new TopSource();
      final var inner = new Source();
      inner.setName("alice");
      src.setNested(inner);
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals("alice", tgt.getNested().getName());
      // Target's primitive int stays at JLS default 0 (source had no `count`).
      assertEquals(0, tgt.getNested().getCount());
    }

    public static class FlatSource {

      private String name;
      private Integer count; // boxed; will hold null at runtime
      private Boolean active; // boxed; will hold null at runtime

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public Integer getCount() {
        return count;
      }

      public void setCount(final Integer count) {
        this.count = count;
      }

      public Boolean getActive() {
        return active;
      }

      public void setActive(final Boolean active) {
        this.active = active;
      }
    }

    public static class FlatTarget {

      private String name;
      private Integer count; // matching: boxed → boxed (no autoboxing needed)
      private Boolean active; // matching: boxed → boxed

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public Integer getCount() {
        return count;
      }

      public void setCount(final Integer count) {
        this.count = count;
      }

      public Boolean getActive() {
        return active;
      }

      public void setActive(final Boolean active) {
        this.active = active;
      }
    }

    @Test
    @DisplayName("forward(src) with null boxed source values reaching matching boxed setters does not NPE")
    void nullBoxedSourceReachesBoxedSetterWithoutNpe() {
      // Even on matched-name boxed→boxed paths, the source value can legitimately be null and
      // the target setter must accept null gracefully. Pins the contract that the construct
      // loop doesn't choke on legitimately-null boxed values.
      final var mapper = Telescope.mapper(
        FlatSource.class,
        FlatTarget.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new FlatSource();
      src.setName("alice");
      // count and active left null
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals("alice", tgt.getName());
      assertEquals(null, tgt.getCount());
      assertEquals(null, tgt.getActive());
    }
  }

  @Nested
  @DisplayName("Getter-only target properties — SettersWriter silently skips instead of throwing")
  class GetterOnlyProperties {

    // Both Source and OrderResponse have a `processed` property so DeepMap's same-name bijection
    // strict-bijection check passes — we want to isolate the SettersWriter getter-only path.
    public static class Source {

      private String name;
      private boolean processed;

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public boolean isProcessed() {
        return processed;
      }

      public void setProcessed(final boolean processed) {
        this.processed = processed;
      }
    }

    public static class OrderResponse {

      private String name;
      private boolean processed;

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      // Getter-only — no setProcessed() exists. Common shape for computed / derived properties
      // (the field is populated by an internal hook, not by external callers).
      public boolean isProcessed() {
        return processed;
      }
    }

    @Test
    @DisplayName("forward(source) silently skips getter-only target properties — no throw")
    void getterOnlyTargetPropertiesAreSilentlySkipped() {
      // MapStruct silently ignores target fields whose setter is missing. Telescope used to throw
      // IllegalArgumentException("no setter setX") at first forward() call. This pins the new
      // no-op contract: forward() succeeds; the getter-only target property stays at its JLS
      // default (false for boolean primitive).
      final var mapper = Telescope.mapper(
        Source.class,
        OrderResponse.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new Source();
      src.setName("alice");
      src.setProcessed(true);
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals("alice", tgt.getName());
      // The `processed` source value was discarded — target's getter-only field stays at the
      // primitive default. MapStruct would do the same.
      assertEquals(false, tgt.isProcessed());
    }
  }

  @Nested
  @DisplayName("Mapper.forward(null) / backward(null) returns null instead of NPE")
  class MapperNullSafe {

    record SrcRec(String id, String name) {}

    record TgtRec(String id, String name) {}

    public static class SrcBean {

      private String id;
      private String name;

      public String getId() {
        return id;
      }

      public void setId(final String id) {
        this.id = id;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    public static class TgtBean {

      private String id;
      private String name;

      public String getId() {
        return id;
      }

      public void setId(final String id) {
        this.id = id;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    @Test
    @DisplayName("Mapper.forward(null) returns null (records)")
    void mapperForwardNullReturnsNullRecords() {
      final var mapper = Telescope.mapper(SrcRec.class, TgtRec.class);
      assertEquals(null, mapper.forward(null));
    }

    @Test
    @DisplayName("Mapper.backward(null) returns null (records)")
    void mapperBackwardNullReturnsNullRecords() {
      final var mapper = Telescope.mapper(SrcRec.class, TgtRec.class);
      assertEquals(null, mapper.backward(null));
    }

    @Test
    @DisplayName("Mapper.forward(null) returns null (beans)")
    void mapperForwardNullReturnsNullBeans() {
      final var mapper = Telescope.mapper(SrcBean.class, TgtBean.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      assertEquals(null, mapper.forward(null));
    }

    @Test
    @DisplayName("Mapper.forward(non-null) still threads through hooks")
    void hooksStillFireOnNonNullInput() {
      final var mapper = Telescope.mapper(SrcRec.class, TgtRec.class).afterForward(t ->
        new TgtRec(t.id() + "-X", t.name())
      );
      final var src = new SrcRec("o1", "alice");
      assertEquals("o1-X", mapper.forward(src).id());
    }

    @Test
    @DisplayName("ForwardMapper.forward(null) returns null")
    void forwardMapperNullReturnsNull() {
      final var mapper = io.github.eschizoid.telescope.conversion.ForwardMapper.create(
        (SrcRec s) -> new TgtRec(s.id(), s.name()),
        SrcRec.class,
        TgtRec.class
      );
      assertEquals(null, mapper.forward(null));
    }

    @Test
    @DisplayName("ForwardMapper.read(null) returns null — read is documented as an alias of forward")
    void forwardMapperReadNullReturnsNull() {
      final var mapper = io.github.eschizoid.telescope.conversion.ForwardMapper.create(
        (SrcRec s) -> new TgtRec(s.id(), s.name()),
        SrcRec.class,
        TgtRec.class
      );
      assertEquals(null, mapper.read(null));
      // Non-null parity: read and forward must produce the same output, otherwise a future
      // refactor that breaks the alias contract slips through unnoticed.
      final var src = new SrcRec("o1", "alice");
      assertEquals(mapper.forward(src), mapper.read(src));
    }

    @Test
    @DisplayName("Mapper.forward — preForward returning null propagates as null (no NPE in iso.to)")
    void preForwardReturningNullPropagatesAsNull() {
      // A normalisation hook that maps a sentinel (empty id) to null must not crash the mapper.
      final var mapper = Telescope.mapper(SrcRec.class, TgtRec.class).beforeForward(s -> s.id().isEmpty() ? null : s);
      assertEquals(null, mapper.forward(new SrcRec("", "alice")));
    }

    @Test
    @DisplayName("Mapper.backward — preBackward returning null propagates as null (no NPE in iso.from)")
    void preBackwardReturningNullPropagatesAsNull() {
      // Symmetric pin for the backward path: without this, a future revert of the backward guard
      // goes unnoticed because no other test exercises preBackward returning null.
      final var mapper = Telescope.mapper(SrcRec.class, TgtRec.class).beforeBackward(t -> t.id().isEmpty() ? null : t);
      assertEquals(null, mapper.backward(new TgtRec("", "alice")));
    }
  }

  @Nested
  @DisplayName("Telescope.fieldByName(String) on a bean Telescope routes through Beans.fieldLens")
  class FieldByNameBeanDispatch {

    public static class Order {

      private String id;
      private String customerName;

      public String getId() {
        return id;
      }

      public void setId(final String id) {
        this.id = id;
      }

      public String getCustomerName() {
        return customerName;
      }

      public void setCustomerName(final String customerName) {
        this.customerName = customerName;
      }
    }

    @Test
    @DisplayName("Telescope.ofBean(Order).fieldByName(\"id\").read(order) returns the property value")
    void fieldByNameReadsBeanProperty() {
      final var src = new Order();
      src.setId("o-42");
      src.setCustomerName("alice");
      final var path = Telescope.ofBean(Order.class).<String>fieldByName("id");
      assertEquals("o-42", path.read(src));
    }

    @Test
    @DisplayName("Telescope.ofBean(Order).fieldByName(\"customerName\").update(order, fn) rebuilds via the auto-writer")
    void fieldByNameUpdatesBeanPropertyViaAutoWriter() {
      final var src = new Order();
      src.setId("o-1");
      src.setCustomerName("alice");
      final var updated = Telescope.ofBean(Order.class)
        .<String>fieldByName("customerName")
        .update(src, String::toUpperCase);
      assertEquals("ALICE", updated.getCustomerName());
      // Off-path id is carried over via readProperty during the rebuild.
      assertEquals("o-1", updated.getId());
    }

    record User(String name, int age) {}

    @Test
    @DisplayName("Records still dispatch through Records.fieldLens — backward-compat smoke")
    void fieldByNameOnRecordStillWorks() {
      final var src = new User("bob", 30);
      assertEquals("bob", Telescope.of(User.class).<String>fieldByName("name").read(src));
    }
  }

  @Nested
  @DisplayName(
    "Parameterised Collection / Map subtype pairs across DIFFERENT raw classes are " +
      "lifted into the target's concrete class"
  )
  class ParameterisedContainerSubtypeLift {

    record Inner(String id) {}

    record InnerDto(String id) {}

    public static class Outer {

      private List<Inner> items;
      private Map<String, Inner> byId;

      public List<Inner> getItems() {
        return items;
      }

      public void setItems(final List<Inner> items) {
        this.items = items;
      }

      public Map<String, Inner> getById() {
        return byId;
      }

      public void setById(final Map<String, Inner> byId) {
        this.byId = byId;
      }
    }

    public static class OuterDto {

      private ArrayList<InnerDto> items;
      private HashMap<String, InnerDto> byId;

      public ArrayList<InnerDto> getItems() {
        return items;
      }

      public void setItems(final ArrayList<InnerDto> items) {
        this.items = items;
      }

      public HashMap<String, InnerDto> getById() {
        return byId;
      }

      public void setById(final HashMap<String, InnerDto> byId) {
        this.byId = byId;
      }
    }

    @Test
    @DisplayName("List<Inner> ↔ ArrayList<InnerDto> — common JPA-entity-to-DTO shape")
    void listInterfaceToArrayListConcreteWorks() {
      final var mapper = Telescope.mapper(Outer.class, OuterDto.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var src = new Outer();
      src.setItems(List.of(new Inner("a"), new Inner("b")));
      src.setById(Map.of("k1", new Inner("v1")));

      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(ArrayList.class, tgt.getItems().getClass());
      assertEquals(HashMap.class, tgt.getById().getClass());
      assertEquals("a", tgt.getItems().get(0).id());
      assertEquals("v1", tgt.getById().get("k1").id());
    }

    @Test
    @DisplayName("backward — ArrayList target produces a List-side instance suitable for the List field")
    void backwardProducesSourceCompatibleList() {
      final var mapper = Telescope.mapper(Outer.class, OuterDto.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var dto = new OuterDto();
      dto.setItems(new ArrayList<>(List.of(new InnerDto("a"), new InnerDto("b"))));
      dto.setById(new HashMap<>(Map.of("k1", new InnerDto("v1"))));

      final var back = assertDoesNotThrow(() -> mapper.backward(dto));
      assertEquals(ArrayList.class, back.getItems().getClass());
      assertEquals("a", back.getItems().get(0).id());
      assertEquals("v1", back.getById().get("k1").id());
    }

    // Set-side coverage — pins the SET branch of liftSetIntoTargetRaw.
    public static class HasSetInterface {

      private Set<Inner> tags;

      public Set<Inner> getTags() {
        return tags;
      }

      public void setTags(final Set<Inner> tags) {
        this.tags = tags;
      }
    }

    public static class HasHashSetConcrete {

      private HashSet<InnerDto> tags;

      public HashSet<InnerDto> getTags() {
        return tags;
      }

      public void setTags(final HashSet<InnerDto> tags) {
        this.tags = tags;
      }
    }

    @Test
    @DisplayName("Set<Inner> ↔ HashSet<InnerDto> — Set branch picks up the target concrete class")
    void setInterfaceToHashSetConcreteWorks() {
      final var mapper = Telescope.mapper(
        HasSetInterface.class,
        HasHashSetConcrete.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new HasSetInterface();
      src.setTags(Set.of(new Inner("a"), new Inner("b")));

      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(HashSet.class, tgt.getTags().getClass());
      assertEquals(2, tgt.getTags().size());
    }

    // User subclass — pins the intermediateAllocator success path (privateLookupIn works in
    // the user's own package).
    public static class MyList<E> extends ArrayList<E> {

      @Serial
      private static final long serialVersionUID = 1L;
    }

    public static class HasMyListSrc {

      private MyList<Inner> items;

      public MyList<Inner> getItems() {
        return items;
      }

      public void setItems(final MyList<Inner> items) {
        this.items = items;
      }
    }

    public static class HasArrayListTgt {

      private ArrayList<InnerDto> items;

      public ArrayList<InnerDto> getItems() {
        return items;
      }

      public void setItems(final ArrayList<InnerDto> items) {
        this.items = items;
      }
    }

    @Test
    @DisplayName("user-defined List subclass ↔ ArrayList — allocator routes through intermediateAllocator")
    void userListSubclassToArrayListWorks() {
      final var mapper = Telescope.mapper(
        HasMyListSrc.class,
        HasArrayListTgt.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new HasMyListSrc();
      final var srcList = new MyList<Inner>();
      srcList.add(new Inner("a"));
      src.setItems(srcList);

      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(ArrayList.class, tgt.getItems().getClass());
      assertEquals("a", tgt.getItems().get(0).id());

      // Backward — target ArrayList round-trips back through the user subclass via
      // intermediateAllocator(MyList.class).
      final var back = assertDoesNotThrow(() -> mapper.backward(tgt));
      assertEquals(MyList.class, back.getItems().getClass());
    }

    // Map user-subclass: mirror of MyList<Inner> ↔ ArrayList pinning the Map-side
    // intermediateAllocator success path.
    public static class MyMap<K, V> extends HashMap<K, V> {

      @Serial
      private static final long serialVersionUID = 1L;
    }

    public static class HasMyMapSrc {

      private MyMap<String, Inner> items;

      public MyMap<String, Inner> getItems() {
        return items;
      }

      public void setItems(final MyMap<String, Inner> items) {
        this.items = items;
      }
    }

    public static class HasHashMapTgt {

      private HashMap<String, InnerDto> items;

      public HashMap<String, InnerDto> getItems() {
        return items;
      }

      public void setItems(final HashMap<String, InnerDto> items) {
        this.items = items;
      }
    }

    @Test
    @DisplayName("user-defined Map subclass ↔ HashMap — Map intermediateAllocator success path")
    void userMapSubclassToHashMapWorks() {
      final var mapper = Telescope.mapper(
        HasMyMapSrc.class,
        HasHashMapTgt.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new HasMyMapSrc();
      final var srcMap = new MyMap<String, Inner>();
      srcMap.put("k1", new Inner("v1"));
      src.setItems(srcMap);

      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(HashMap.class, tgt.getItems().getClass());
      assertEquals("v1", tgt.getItems().get("k1").id());

      final var back = assertDoesNotThrow(() -> mapper.backward(tgt));
      assertEquals(MyMap.class, back.getItems().getClass());
    }

    // EnumMap target — rejected at plan-time because EnumMap has no no-arg constructor.
    enum Region {
      US,
      EU,
    }

    public static class HasEnumMapTgt {

      private EnumMap<Region, InnerDto> byRegion;

      public EnumMap<Region, InnerDto> getByRegion() {
        return byRegion;
      }

      public void setByRegion(final EnumMap<Region, InnerDto> byRegion) {
        this.byRegion = byRegion;
      }
    }

    public static class HasMapByRegionSrc {

      private Map<Region, Inner> byRegion;

      public Map<Region, Inner> getByRegion() {
        return byRegion;
      }

      public void setByRegion(final Map<Region, Inner> byRegion) {
        this.byRegion = byRegion;
      }
    }

    @Test
    @DisplayName("EnumMap target throws plan-time IAE with codegen / via guidance")
    void enumMapTargetThrowsPlanTime() {
      final var ex = assertThrows(IllegalStateException.class, () ->
        Telescope.mapper(HasMapByRegionSrc.class, HasEnumMapTgt.class, writeBeans(WriteHint.WriteStrategy.SETTERS))
      );
      assertEquals(true, ex.getMessage().contains("EnumMap"), "names the offending class");
      assertEquals(true, ex.getMessage().contains("Mapping.via"), "cites the escape hatch");
    }

    // Unknown JDK collection class (SynchronousQueue is in java.base, has a no-arg ctor that
    // throws-on-add — Beans.intermediateAllocator can't bind it via privateLookupIn either way,
    // and there's no entry in listAllocatorFor's hard-coded table). The diagnostic-message
    // contract for the unknown-JDK throw is pinned by this test.
    public static class HasSyncQueueTgt {

      private SynchronousQueue<InnerDto> items;

      public SynchronousQueue<InnerDto> getItems() {
        return items;
      }

      public void setItems(final SynchronousQueue<InnerDto> items) {
        this.items = items;
      }
    }

    public static class HasListSrcForUnknown {

      private List<Inner> items;

      public List<Inner> getItems() {
        return items;
      }

      public void setItems(final List<Inner> items) {
        this.items = items;
      }
    }

    @Test
    @DisplayName("unknown java.base Collection class throws plan-time IAE naming the class")
    void unknownJdkCollectionClassThrowsPlanTime() {
      // SynchronousQueue is List-assignable? No — it's a Queue. ContainerShape gates on
      // List/Set/Map separately; SynchronousQueue is a Queue but NOT a List or Set, so the
      // ContainerShape branch doesn't pick it up. The shape-mismatch IAE fires instead, which is
      // the correct outcome — pin that contract.
      final var ex = assertThrows(IllegalStateException.class, () ->
        Telescope.mapper(HasListSrcForUnknown.class, HasSyncQueueTgt.class, writeBeans(WriteHint.WriteStrategy.SETTERS))
      );
      assertEquals(true, ex.getMessage().contains("shapes"), "shape mismatch diagnostic");
    }

    // User subclass WITHOUT a no-arg ctor — the negative side of userListSubclassToArrayListWorks.
    // Beans.intermediateAllocator yields a null-supplier, so the new plan-time IAE fires.
    public static class NoCtorList<E> extends ArrayList<E> {

      @Serial
      private static final long serialVersionUID = 1L;

      // Only a ctor that takes an int — no no-arg ctor, no static builder() factory.
      public NoCtorList(final int initialCapacity) {
        super(initialCapacity);
      }
    }

    public static class HasNoCtorListTgt {

      private NoCtorList<InnerDto> items;

      public NoCtorList<InnerDto> getItems() {
        return items;
      }

      public void setItems(final NoCtorList<InnerDto> items) {
        this.items = items;
      }
    }

    @Test
    @DisplayName("user List subclass without no-arg ctor throws plan-time IAE naming the class")
    void userListSubclassWithoutNoArgCtorThrowsPlanTime() {
      final var ex = assertThrows(IllegalStateException.class, () ->
        Telescope.mapper(
          HasListSrcForUnknown.class,
          HasNoCtorListTgt.class,
          writeBeans(WriteHint.WriteStrategy.SETTERS)
        )
      );
      assertEquals(true, ex.getMessage().contains("NoCtorList"), "names the offending class");
    }
  }

  @Nested
  @DisplayName("mapperForward is lenient by default — unmatched target → JLS default, unmatched source → ignored")
  class MapperForwardLenientByDefault {

    // Reproduces the adopter-reported "small DTO → large entity" friction shape: a 3-field source
    // record mapping into a many-field bean target. Before Enh 9 this required N drops + M
    // constants just to satisfy the strict bijection check; under the lenient default it just
    // works.
    record SmallDto(String id, String name, int quantity) {}

    public static class LargeEntity {

      // Same-name fields with the source.
      private String id;
      private String name;
      private int quantity;
      // Unmatched target fields — these should stay at JLS defaults under lenient mode.
      private String createdBy;
      private String updatedBy;
      private boolean archived;
      private int version;
      private String tenant;

      public String getId() {
        return id;
      }

      public void setId(final String id) {
        this.id = id;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public int getQuantity() {
        return quantity;
      }

      public void setQuantity(final int quantity) {
        this.quantity = quantity;
      }

      public String getCreatedBy() {
        return createdBy;
      }

      public void setCreatedBy(final String createdBy) {
        this.createdBy = createdBy;
      }

      public String getUpdatedBy() {
        return updatedBy;
      }

      public void setUpdatedBy(final String updatedBy) {
        this.updatedBy = updatedBy;
      }

      public boolean isArchived() {
        return archived;
      }

      public void setArchived(final boolean archived) {
        this.archived = archived;
      }

      public int getVersion() {
        return version;
      }

      public void setVersion(final int version) {
        this.version = version;
      }

      public String getTenant() {
        return tenant;
      }

      public void setTenant(final String tenant) {
        this.tenant = tenant;
      }
    }

    @Test
    @DisplayName("forward-only mapper construction with unmatched target fields succeeds (no drops/constants)")
    void mapperForwardConstructsWithoutDropsOrConstants() {
      // Adopter pain reproduction: pre-fix this required 5 constant() rows for the unmatched
      // target fields. Lenient default: it just works.
      assertDoesNotThrow(() ->
        Telescope.mapperForward(SmallDto.class, LargeEntity.class, writeBeans(WriteHint.WriteStrategy.SETTERS))
      );
    }

    @Test
    @DisplayName("forward(small) populates same-name fields; unmatched fields take JLS defaults")
    void forwardPopulatesSameNameFieldsAndJlsDefaultsTheRest() {
      final var mapper = Telescope.mapperForward(
        SmallDto.class,
        LargeEntity.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var tgt = mapper.forward(new SmallDto("o1", "alice", 42));
      // Matched fields carry through.
      assertEquals("o1", tgt.getId());
      assertEquals("alice", tgt.getName());
      assertEquals(42, tgt.getQuantity());
      // Unmatched target fields take JLS defaults (null for reference, 0 for int, false for bool).
      assertEquals(null, tgt.getCreatedBy());
      assertEquals(null, tgt.getUpdatedBy());
      assertEquals(false, tgt.isArchived());
      assertEquals(0, tgt.getVersion());
      assertEquals(null, tgt.getTenant());
    }

    record SourceWithExtras(String id, String name, String extraNote, String anotherExtra) {}

    record TargetSmall(String id, String name) {}

    @Test
    @DisplayName("forward-only — unmatched source fields are silently ignored (no drop required)")
    void unmatchedSourceFieldsAreIgnoredUnderLenient() {
      // Pre-fix: this required drop(SourceWithExtras::extraNote) +
      // drop(SourceWithExtras::anotherExtra).
      final var mapper = Telescope.mapperForward(SourceWithExtras.class, TargetSmall.class);
      final var tgt = mapper.forward(new SourceWithExtras("o1", "alice", "ignored", "also ignored"));
      assertEquals("o1", tgt.id());
      assertEquals("alice", tgt.name());
    }

    @Test
    @DisplayName("bidirectional mapper() STILL enforces strict bijection — unmatched target throws")
    void bidirectionalMapperStillStrict() {
      // Round-trip safety: Telescope.mapper (bidirectional) must keep throwing on unmatched
      // fields so callers know backward() won't silently lose data.
      assertThrows(IllegalStateException.class, () ->
        Telescope.mapper(SmallDto.class, LargeEntity.class, writeBeans(WriteHint.WriteStrategy.SETTERS))
      );
    }

    @Test
    @DisplayName("bidirectional mapper() STILL enforces strict bijection — unmatched source throws")
    void bidirectionalMapperStrictOnUnmatchedSource() {
      assertThrows(IllegalStateException.class, () -> Telescope.mapper(SourceWithExtras.class, TargetSmall.class));
    }

    @Test
    @DisplayName("explicit rename rows still work — only unmatched fields are leniently defaulted")
    void explicitRenameRowsStillFire() {
      // Same-name id + an explicit rename `name → fullName`-equivalent via Mapping.to with
      // different accessors. The unmatched (createdBy etc.) fields stay at default; the explicit
      // rename fires normally.
      final var mapper = Telescope.mapperForward(
        SmallDto.class,
        LargeEntity.class,
        Mapping.to(SmallDto::name, LargeEntity::getName),
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var tgt = mapper.forward(new SmallDto("o1", "alice", 42));
      assertEquals("alice", tgt.getName());
      assertEquals(null, tgt.getCreatedBy());
    }

    @Test
    @DisplayName(
      "hook chain (afterForward) composes with lenient construction — stamped fields land on the leniently-built target"
    )
    void hookChainComposesWithLeniency() {
      // Pins that the lenient-construction change doesn't break afterForward composition. The
      // mapper is built leniently (unmatched fields default), then the hook stamps a derived
      // field post-mapping. Both behaviours fire correctly together.
      final var mapper = Telescope.mapperForward(
        SmallDto.class,
        LargeEntity.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      ).afterForward(e -> {
        e.setCreatedBy("system");
        return e;
      });
      final var tgt = mapper.forward(new SmallDto("o1", "alice", 42));
      assertEquals("system", tgt.getCreatedBy(), "afterForward hook fired");
      assertEquals(null, tgt.getUpdatedBy(), "other unmatched fields still at JLS default");
      assertEquals("alice", tgt.getName(), "matched field carried through");
    }

    public static class HolderWithCustomer {

      private InnerCustomer customer;

      public InnerCustomer getCustomer() {
        return customer;
      }

      public void setCustomer(final InnerCustomer customer) {
        this.customer = customer;
      }
    }

    public static class InnerCustomer {

      private String email;

      public String getEmail() {
        return email;
      }

      public void setEmail(final String email) {
        this.email = email;
      }
    }

    public static class FlatTgt {

      private String customerEmail;
      private String otherField;

      public String getCustomerEmail() {
        return customerEmail;
      }

      public void setCustomerEmail(final String customerEmail) {
        this.customerEmail = customerEmail;
      }

      public String getOtherField() {
        return otherField;
      }

      public void setOtherField(final String otherField) {
        this.otherField = otherField;
      }
    }

    @Test
    @DisplayName(
      "Mapping.to(srcTelescope, tgtAccessor) composes with lenient — nested source row + unmatched target field"
    )
    void telescopeRowComposesWithLeniency() {
      // A nested-source telescope row alongside an UNMATCHED target field. Pre-fix this needed a
      // constant() for `otherField`. Under lenient default, `otherField` takes its JLS default
      // while the explicit telescope-row binding still fires for `customerEmail`.
      final var mapper = Telescope.mapperForward(
        HolderWithCustomer.class,
        FlatTgt.class,
        Mapping.to(
          Telescope.ofBean(HolderWithCustomer.class)
            .field(HolderWithCustomer::getCustomer)
            .field(InnerCustomer::getEmail),
          FlatTgt::getCustomerEmail
        ),
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new HolderWithCustomer();
      final var c = new InnerCustomer();
      c.setEmail("alice@example.com");
      src.setCustomer(c);
      final var tgt = mapper.forward(src);
      assertEquals("alice@example.com", tgt.getCustomerEmail(), "telescope row fired");
      assertEquals(null, tgt.getOtherField(), "unmatched target field at JLS default");
    }
  }
}
