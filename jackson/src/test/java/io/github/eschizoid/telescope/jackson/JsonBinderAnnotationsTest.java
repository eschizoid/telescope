package io.github.eschizoid.telescope.jackson;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static io.github.eschizoid.telescope.mapping.MapExtractStep.required;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.jackson.Annotated.AliasClash;
import io.github.eschizoid.telescope.jackson.Annotated.AnySetter;
import io.github.eschizoid.telescope.jackson.Annotated.AnySetterField;
import io.github.eschizoid.telescope.jackson.Annotated.Created;
import io.github.eschizoid.telescope.jackson.Annotated.CreatedByConstructor;
import io.github.eschizoid.telescope.jackson.Annotated.Feed;
import io.github.eschizoid.telescope.jackson.Annotated.HoldsNamed;
import io.github.eschizoid.telescope.jackson.Annotated.IgnoresByName;
import io.github.eschizoid.telescope.jackson.Annotated.Lenient;
import io.github.eschizoid.telescope.jackson.Annotated.NameClash;
import io.github.eschizoid.telescope.jackson.Annotated.Named;
import io.github.eschizoid.telescope.jackson.Annotated.NotIgnored;
import io.github.eschizoid.telescope.jackson.Annotated.Partner;
import io.github.eschizoid.telescope.jackson.Annotated.PartnerBean;
import io.github.eschizoid.telescope.jackson.Annotated.ReadOnly;
import io.github.eschizoid.telescope.jackson.Annotated.Renamed;
import io.github.eschizoid.telescope.jackson.Annotated.Required;
import io.github.eschizoid.telescope.jackson.Annotated.RequiredBean;
import io.github.eschizoid.telescope.jackson.Annotated.SplitBean;
import io.github.eschizoid.telescope.jackson.Annotated.TwoNames;
import io.github.eschizoid.telescope.jackson.Annotated.Unwrapped;
import io.github.eschizoid.telescope.jackson.Annotated.Viewed;
import io.github.eschizoid.telescope.jackson.Annotated.ViewedClass;
import io.github.eschizoid.telescope.mapping.MapExtractStep;
import java.io.File;
import java.lang.reflect.Array;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.InvalidDefinitionException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The binder reads {@code @JsonProperty}, {@code @JsonAlias} and {@code @JsonIgnore} as databind
 * reads them. Each case reads the same document into the same type both ways and expects the same
 * value, except where the module's README names a difference.
 */
class JsonBinderAnnotationsTest {

  private static final JsonMapper DATABIND = JsonMapper.builder().build();

  /** The value databind reads, after checking the binder reads the same one. */
  private static <T> T same(final Class<T> type, final String json) {
    final var expected = DATABIND.readValue(json, type);
    assertEquals(expected, TelescopeJson.fromJson(type).read(json), json);
    return expected;
  }

  private static IllegalArgumentException refusal(final Runnable run) {
    return assertThrows(IllegalArgumentException.class, run::run);
  }

  @Nested
  class Records {

    @Test
    @DisplayName("@JsonProperty sets the key a component reads, and its own name is then an unknown key")
    void jsonPropertyRenames() {
      assertEquals(new Partner("P-1", null, null, null), same(Partner.class, "{\"partner_id\":\"P-1\"}"));
      assertEquals(new Partner(null, null, null, null), same(Partner.class, "{\"id\":\"P-1\"}"));
    }

    @Test
    @DisplayName("@JsonAlias adds keys, and when the key and an alias both appear the later one wins")
    void jsonAliasAddsKeys() {
      assertEquals("Ada", same(Partner.class, "{\"partnerName\":\"Ada\"}").name());
      assertEquals("Ada", same(Partner.class, "{\"name_v1\":\"Ada\"}").name());
      assertEquals("alias", same(Partner.class, "{\"name\":\"main\",\"partnerName\":\"alias\"}").name());
      assertEquals("main", same(Partner.class, "{\"partnerName\":\"alias\",\"name\":\"main\"}").name());
      assertNull(same(Partner.class, "{\"partnerName\":\"alias\",\"name\":null}").name());
    }

    @Test
    @DisplayName("@JsonIgnore leaves a component its default, whatever the document holds under its name")
    void jsonIgnoreLeavesDefault() {
      assertEquals(
        new Partner("P-1", null, null, 2),
        same(Partner.class, "{\"partner_id\":\"P-1\",\"internal\":{\"any\":[1]},\"tier\":2}")
      );
    }

    @Test
    @DisplayName("a renamed nested record and a list of annotated records read as databind reads them")
    void nestedAndListed() {
      final var feed = same(
        Feed.class,
        """
        {"feed_name":"daily","partner_info":{"partner_id":"P-1","partnerName":"Ada","tier":1},
         "partners":[{"partner_id":"P-2","name":"Bo"},{"partner_id":"P-3","name_v1":"Cy","internal":"x"}]}
        """
      );
      assertEquals(
        new Feed(
          "daily",
          new Partner("P-1", "Ada", null, 1),
          List.of(new Partner("P-2", "Bo", null, null), new Partner("P-3", "Cy", null, null))
        ),
        feed
      );
      assertEquals(
        List.of(new Partner("P-2", "Bo", null, null)),
        TelescopeJson.fromJson(Partner.class).readList("[{\"partner_id\":\"P-2\",\"name\":\"Bo\"}]")
      );
    }

    @Test
    @DisplayName("@JsonProperty on an accessor declared in the record body renames the component")
    void accessorRenames() {
      assertEquals(new Renamed("x"), same(Renamed.class, "{\"ident\":\"x\"}"));
      assertEquals(new Renamed(null), same(Renamed.class, "{\"id\":\"x\"}"));
    }

    @Test
    @DisplayName("a READ_ONLY component is never read, and @JsonIgnore(false) ignores nothing")
    void accessAndIgnoreFalse() {
      assertEquals(new ReadOnly(null, "k"), same(ReadOnly.class, "{\"shown\":\"s\",\"kept\":\"k\"}"));
      assertEquals(new NotIgnored("a", "x"), same(NotIgnored.class, "{\"a\":\"a\",\"x\":\"x\"}"));
      assertEquals(new Lenient("i"), same(Lenient.class, "{\"id\":\"i\",\"other\":1}"));
    }

    @Test
    @DisplayName("a required component refuses a document without its key, as databind refuses it")
    void requiredComponent() {
      assertEquals(new Required("P-1", null), same(Required.class, "{\"partner_id\":\"P-1\"}"));
      assertThrows(MismatchedInputException.class, () -> DATABIND.readValue("{\"name\":\"n\"}", Required.class));
      assertEquals(
        "TelescopeJson.fromJson: the document carries no value for required key \"partner_id\" (component 'id')" +
          " of Required, at line 1, column 12",
        refusal(() -> TelescopeJson.fromJson(Required.class).read("{\"name\":\"n\"}")).getMessage()
      );
      // The README's difference: the binder counts a null value as missing, databind as a value.
      assertEquals(new Required(null, null), DATABIND.readValue("{\"partner_id\":null}", Required.class));
      refusal(() -> TelescopeJson.fromJson(Required.class).read("{\"partner_id\":null}"));
    }

    @Test
    @DisplayName("a row decides its property's key over the annotations")
    void rowOverridesAnnotations() {
      final var binder = TelescopeJson.fromJson(
        Partner.class,
        extract("pid", Partner::id, Object::toString),
        extract("secret", Partner::internal, Object::toString)
      );
      assertEquals(new Partner("1", null, "s", null), binder.read("{\"pid\":1,\"partner_id\":\"x\",\"secret\":\"s\"}"));
    }
  }

  @Nested
  class Beans {

    @Test
    @DisplayName("on a bean, the annotations on its field, getter and setter read as databind reads them")
    void beanMembers() {
      final var bean = same(
        PartnerBean.class,
        """
        {"partner_id":"P-1","partnerName":"Ada","internal":"i","secret":"s","shown":"x","display_label":"L"}
        """
      );
      assertEquals("P-1", bean.getId());
      assertEquals("Ada", bean.getName());
      assertNull(bean.getInternal());
      assertEquals("s", bean.getSecret());
      assertNull(bean.getShown());
      assertEquals("L", bean.getLabel());
      final var unread = same(PartnerBean.class, "{\"id\":\"P-1\",\"label\":\"L\"}");
      assertNull(unread.getId());
      assertNull(unread.getLabel());
    }

    @Test
    @DisplayName("a bean named apart on its getter and setter reads the setter's key")
    void setterNameWins() {
      assertEquals("v", DATABIND.readValue("{\"read_as\":\"v\"}", SplitBean.class).getName());
      assertEquals("v", TelescopeJson.fromJson(SplitBean.class).read("{\"read_as\":\"v\"}").getName());
      assertNull(TelescopeJson.fromJson(SplitBean.class).read("{\"written_as\":\"v\"}").getName());
      assertNull(DATABIND.readValue("{\"written_as\":\"v\"}", SplitBean.class).getName());
    }

    @Test
    @DisplayName("a bean property's required flag holds no document to its key, as in databind")
    void requiredBeanProperty() {
      assertNull(DATABIND.readValue("{}", RequiredBean.class).getName());
      assertNull(TelescopeJson.fromJson(RequiredBean.class).read("{}").getName());
    }
  }

  @Nested
  class PropertyCombinations {

    /** Keys each combination's annotations could name, alone, together and in either order. */
    private static final List<String> DOCUMENTS = List.of(
      "{\"p\":\"P\"}",
      "{\"k\":\"K\"}",
      "{\"f\":\"F\"}",
      "{\"g\":\"G\"}",
      "{\"s\":\"S\"}",
      "{\"acc\":\"A\"}",
      "{\"x2\":\"X\"}",
      "{\"p\":\"P\",\"x2\":\"X\"}",
      "{\"x2\":\"X\",\"p\":\"P\"}",
      "{\"x3\":\"Y\",\"p\":\"P\"}",
      "{\"p\":\"P\",\"k\":\"K\"}",
      "{\"p\":\"P\",\"q\":\"Q\"}",
      "{\"p\":\"P\",\"p2\":\"Z\"}",
      "{}",
      "{\"p\":null}"
    );

    /** Every document read into {@code type} both ways; each mismatch, or none. */
    private static List<String> mismatches(final Class<?> type) {
      final var binder = TelescopeJson.fromJson(type);
      final var wrong = new ArrayList<String>();
      for (final var json : DOCUMENTS) {
        final var expected = DATABIND.readValue(json, type);
        final var actual = binder.read(json);
        if (!expected.equals(actual)) wrong.add(json + ": databind " + expected + ", binder " + actual);
      }
      return wrong;
    }

    @Test
    @DisplayName("a bean's field, getter and setter annotations, in each combination, read as databind reads them")
    void beans() {
      final var wrong = new ArrayList<String>();
      for (final var type : List.<Class<?>>of(
        Combinations.FieldNamed.class,
        Combinations.GetterNamed.class,
        Combinations.GetterIgnoredSetterNamed.class,
        Combinations.GetterNamedSetterIgnored.class,
        Combinations.FieldIgnoredGetterNamed.class,
        Combinations.FieldIgnoredSetterExplicit.class,
        Combinations.FieldIgnored.class,
        Combinations.FieldNamedAndIgnored.class,
        Combinations.FieldNamedIgnoredSetterNamed.class,
        Combinations.FieldNotIgnored.class,
        Combinations.GetterIgnored.class,
        Combinations.SetterIgnored.class,
        Combinations.SetterReadOnly.class,
        Combinations.FieldWriteOnly.class,
        Combinations.FieldAlias.class
      )) {
        wrong.addAll(mismatches(type));
      }
      assertEquals(List.of(), wrong);
    }

    @Test
    @DisplayName("a record component's annotations, in each combination, read as databind reads them")
    void records() {
      final var wrong = new ArrayList<String>();
      for (final var type : List.<Class<?>>of(
        Combinations.Renamed.class,
        Combinations.AccessorNamed.class,
        Combinations.Aliased.class,
        Combinations.Ignored.class,
        Combinations.NamedAndIgnored.class,
        Combinations.ReadOnly.class,
        Combinations.WriteOnly.class,
        Combinations.NotIgnored.class,
        Combinations.Swapped.class
      )) {
        wrong.addAll(mismatches(type));
      }
      assertEquals(List.of(), wrong);
    }

    @Test
    @DisplayName("a field ignored beside a getter renamed and a plain setter reads the getter's key through the setter")
    void fieldIgnoredGetterNamed() {
      final var expected = new Combinations.FieldIgnoredGetterNamed();
      expected.setP("G");
      assertEquals(expected, same(Combinations.FieldIgnoredGetterNamed.class, "{\"g\":\"G\"}"));
    }

    @Test
    @DisplayName("a component both renamed and ignored is ignored, though its parameter carries only the rename")
    void namedAndIgnored() {
      assertEquals(
        new Combinations.NamedAndIgnored(null, null),
        same(Combinations.NamedAndIgnored.class, "{\"k\":\"K\",\"p\":\"P\"}")
      );
    }
  }

  @Nested
  class Refusals {

    @Test
    @DisplayName("an enum constant renamed by @JsonProperty or @JsonAlias is refused, as a value or a map key")
    void renamedConstants() {
      assertEquals(
        "TelescopeJson.fromJson: constant RED of Color carries @JsonProperty, which JsonBinder does not read; it" +
          " reads an enum constant by its exact name. Read the type holding it with Jackson databind, through a" +
          " TelescopeModule where a mapper converts it, or remove the annotation",
        refusal(() -> TelescopeJson.fromJson(Combinations.Colored.class)).getMessage()
      );
      assertTrue(
        refusal(() -> TelescopeJson.fromJson(Combinations.Sized.class))
          .getMessage()
          .startsWith("TelescopeJson.fromJson: constant SMALL of Size carries @JsonAlias")
      );
      assertTrue(
        refusal(() -> TelescopeJson.fromJson(Combinations.ColorKeyed.class))
          .getMessage()
          .startsWith("TelescopeJson.fromJson: constant RED of Color carries @JsonProperty")
      );
      assertEquals(
        Fixtures.Status.PAID,
        TelescopeJson.fromJson(Fixtures.Order.class).read("{\"status\":\"PAID\"}").status()
      );
    }

    @Test
    @DisplayName("@JsonIgnoreProperties on a component is accepted when it only ignores unknown keys")
    void ignoreUnknownOnComponent() {
      assertEquals(
        new Combinations.IgnoresUnknownInside(new Combinations.Inner("a")),
        same(Combinations.IgnoresUnknownInside.class, "{\"inner\":{\"n\":\"a\",\"z\":1}}")
      );
    }

    @Test
    @DisplayName("two components reading one key by @JsonProperty are refused, as databind refuses them")
    void nameClash() {
      assertEquals(
        "TelescopeJson.fromJson: 'a' and 'b' of NameClash both read the key \"b\", and a JSON value is read once;" +
          " give one of them another key with @JsonProperty or a row, or drop the @JsonAlias that names it",
        refusal(() -> TelescopeJson.fromJson(NameClash.class)).getMessage()
      );
      assertThrows(InvalidDefinitionException.class, () -> DATABIND.readValue("{\"b\":\"x\"}", NameClash.class));
    }

    @Test
    @DisplayName("an alias that is another component's key is refused, where databind gives the key to its owner")
    void aliasClash() {
      assertEquals(
        "TelescopeJson.fromJson: 'a' and 'b' of AliasClash both read the key \"b\", and a JSON value is read once;" +
          " give one of them another key with @JsonProperty or a row, or drop the @JsonAlias that names it",
        refusal(() -> TelescopeJson.fromJson(AliasClash.class)).getMessage()
      );
      assertEquals(new AliasClash(null, "x"), DATABIND.readValue("{\"b\":\"x\"}", AliasClash.class));
    }

    @Test
    @DisplayName("a property named two ways by the members it is written through is refused, as databind refuses it")
    void namedTwice() {
      assertEquals(
        "TelescopeJson.fromJson: property 'name' of TwoNames is named \"s\" and \"f\" by @JsonProperty on the" +
          " members it is written through, and a JSON value is read from one key; give them one name",
        refusal(() -> TelescopeJson.fromJson(TwoNames.class)).getMessage()
      );
      assertThrows(RuntimeException.class, () -> DATABIND.readValue("{\"s\":\"x\"}", TwoNames.class));
    }

    @Test
    @DisplayName("each annotation the binder does not read is refused by name, on whichever type carries it")
    void unsupportedAnnotations() {
      final var cases = List.of(
        new Object[] { Named.class, "Named carries @JsonNaming on the class" },
        new Object[] { HoldsNamed.class, "Named carries @JsonNaming on the class" },
        new Object[] { Unwrapped.class, "Unwrapped carries @JsonUnwrapped on component 'partner'" },
        new Object[] { Created.class, "Created carries @JsonCreator on method of(String)" },
        new Object[] {
          CreatedByConstructor.class,
          "CreatedByConstructor carries @JsonCreator on constructor CreatedByConstructor(String)",
        },
        new Object[] { AnySetter.class, "AnySetter carries @JsonAnySetter on method other(String, Object)" },
        new Object[] { AnySetterField.class, "AnySetterField carries @JsonAnySetter on field 'other'" },
        new Object[] { Viewed.class, "Viewed carries @JsonView on component 'id'" },
        new Object[] { ViewedClass.class, "ViewedClass carries @JsonView on the class" },
        new Object[] { IgnoresByName.class, "IgnoresByName carries @JsonIgnoreProperties on the class" },
        new Object[] {
          Combinations.IgnoresInside.class,
          "IgnoresInside carries @JsonIgnoreProperties on component 'inner'",
        },
        new Object[] { Combinations.SetterConfigured.class, "SetterConfigured carries @JsonSetter on property 'p'" },
        new Object[] { Combinations.ComponentSetter.class, "ComponentSetter carries @JsonSetter on component 'p'" },
        new Object[] { Combinations.Formatted.class, "Formatted carries @JsonFormat on component 'p'" },
        new Object[] { Combinations.FormattedClass.class, "FormattedClass carries @JsonFormat on the class" },
        new Object[] {
          Combinations.Composite.class,
          "Composite carries @Bundled, which bundles Jackson annotations, on component 'p'",
        },
        new Object[] {
          Combinations.CompositeClass.class,
          "CompositeClass carries @Bundled, which bundles Jackson annotations, on the class",
        }
      );
      final var wrong = new ArrayList<String>();
      for (final var refused : cases) {
        final var message = refusal(() -> TelescopeJson.fromJson((Class<?>) refused[0])).getMessage();
        if (!message.startsWith("TelescopeJson.fromJson: " + refused[1] + ", which JsonBinder does not read")) {
          wrong.add(message);
        }
      }
      assertEquals(List.of(), wrong);
    }
  }

  @Nested
  class Messages {

    @Test
    @DisplayName("a refused value names the property by its Java name and, where it differs, by its JSON key")
    void bothPaths() {
      final var feeds = TelescopeJson.fromJson(Feed.class);
      assertEquals(
        "'partner.id' (JSON 'partner_info.partner_id') got 12, not a String, at line 1, column 31",
        refusal(() -> feeds.read("{\"partner_info\":{\"partner_id\":12}}")).getMessage()
      );
      assertEquals(
        "'partners[1].id' (JSON 'partners[1].partner_id') got 7, not a String, at line 1, column 47",
        refusal(() -> feeds.read("{\"partners\":[{\"partner_id\":\"a\"},{\"partner_id\":7}]}")).getMessage()
      );
      assertEquals(
        "'partners[0].tier' got \"x\", not an Integer, at line 1, column 22",
        refusal(() -> feeds.read("{\"partners\":[{\"tier\":\"x\"}]}")).getMessage()
      );
      assertEquals(
        "'name' (JSON 'partnerName') got 1, not a String, at line 1, column 16",
        refusal(() -> TelescopeJson.fromJson(Partner.class).read("{\"partnerName\":1}")).getMessage()
      );
    }

    @Test
    @DisplayName("a missing required key under a renamed object names the object both ways")
    void missingUnderRenamedObject() {
      final var binder = TelescopeJson.fromJson(Feed.class, required("pid", Partner::id, String::valueOf));
      assertEquals(
        "TelescopeJson.fromJson: the object at 'partner' (JSON 'partner_info') carries no value for required key" +
          " \"pid\" (component 'id') of Partner, at line 1, column 18",
        refusal(() -> binder.read("{\"partner_info\":{}}")).getMessage()
      );
    }

    @Test
    @DisplayName("explain reports a renamed key and an ignored component as dropped")
    void explain() {
      assertEquals(
        List.of(
          new OpticNode.Extracted("partner_id", "id", "String", OpticNode.WhenAbsent.DEFAULTS),
          new OpticNode.Extracted("name", "name", "String", OpticNode.WhenAbsent.DEFAULTS),
          new OpticNode.Skipped("internal", OpticNode.Reason.DROPPED),
          new OpticNode.Extracted("tier", "tier", "Integer", OpticNode.WhenAbsent.DEFAULTS)
        ),
        TelescopeJson.fromJson(Partner.class).explain().nodes()
      );
    }
  }

  @Test
  @DisplayName("without jackson-annotations at run time no annotation is present, and every key is the property's name")
  void annotationsJarAbsent() throws Exception {
    final var entries = new ArrayList<URL>();
    for (final var entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      if (!new File(entry).getName().startsWith("jackson-annotations")) entries.add(toUrl(entry));
    }
    try (final var loader = new URLClassLoader(entries.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
      assertThrows(ClassNotFoundException.class, () ->
        Class.forName("com.fasterxml.jackson.annotation.JsonProperty", false, loader)
      );
      final var partner = Class.forName(Partner.class.getName(), true, loader);
      final var steps = Class.forName(MapExtractStep.class.getName(), true, loader);
      final var binder = Class.forName(TelescopeJson.class.getName(), true, loader)
        .getMethod("fromJson", Class.class, steps.arrayType())
        .invoke(null, partner, Array.newInstance(steps, 0));
      final var read = binder
        .getClass()
        .getMethod("read", String.class)
        .invoke(binder, "{\"id\":\"own\",\"partner_id\":\"renamed\",\"internal\":\"i\"}");
      assertEquals("own", partner.getMethod("id").invoke(read));
      assertEquals("i", partner.getMethod("internal").invoke(read));
    }
  }

  private static URL toUrl(final String entry) {
    try {
      return new File(entry).toURI().toURL();
    } catch (final MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }
}
