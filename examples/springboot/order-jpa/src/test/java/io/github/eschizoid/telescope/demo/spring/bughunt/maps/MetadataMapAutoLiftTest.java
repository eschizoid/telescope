package io.github.eschizoid.telescope.demo.spring.bughunt.maps;

import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Bug-hunt slice for the {@code Map<K, V>} auto-lift code path. Exercises {@link
 * io.github.eschizoid.telescope.internal.optics.Iso#liftMapValues} via {@code DeepMap#autoIso} →
 * {@code ContainerShape.MAP_VALUES}.
 *
 * <p>Coverage:
 *
 * <ol>
 *   <li>Same-typed {@code Map<String, String>} round-trips through forward / backward (empty, null,
 *       single, multi).
 *   <li>Recursive {@code Map<String, Tag> ↔ Map<String, TagDto>} round-trips — value type recurses
 *       through {@code autoIso}.
 *   <li>Iteration order survives the round-trip ({@code LinkedHashMap} preservation contract).
 *   <li>Key-type mismatch surfaces the documented precise IAE before any data flows.
 * </ol>
 */
class MetadataMapAutoLiftTest {

  private final Mapper<MetadataOrder, MetadataOrderEntity> mapper = MetadataOrderMappers.mapper();

  @Test
  void emptyMapsRoundTrip() {
    final var src = new MetadataOrder("o-1", Map.of(), Map.of());

    final var fwd = mapper.forward(src);
    assertThat(fwd.getId()).isEqualTo("o-1");
    assertThat(fwd.getMetadata()).isEmpty();
    assertThat(fwd.getTags()).isEmpty();

    final var back = mapper.backward(fwd);
    assertThat(back).isEqualTo(src);
  }

  @Test
  void nullMapsRoundTripAsNull() {
    final var src = new MetadataOrder("o-2", null, null);

    final var fwd = mapper.forward(src);
    assertThat(fwd.getId()).isEqualTo("o-2");
    assertThat(fwd.getMetadata()).isNull();
    assertThat(fwd.getTags()).isNull();

    final var back = mapper.backward(fwd);
    assertThat(back.id()).isEqualTo("o-2");
    assertThat(back.metadata()).isNull();
    assertThat(back.tags()).isNull();
  }

  @Test
  void singleEntryMetadataMapRoundTrips() {
    final var src = new MetadataOrder("o-3", Map.of("region", "us-east"), Map.of());

    final var fwd = mapper.forward(src);
    assertThat(fwd.getMetadata()).containsEntry("region", "us-east").hasSize(1);

    final var back = mapper.backward(fwd);
    assertThat(back.metadata()).containsEntry("region", "us-east").hasSize(1);
  }

  @Test
  void multiEntryMetadataAndTagsRoundTripWithOrder() {
    final var meta = new LinkedHashMap<String, String>();
    meta.put("region", "us-east");
    meta.put("tier", "gold");
    meta.put("source", "web");

    final var tags = new LinkedHashMap<String, Tag>();
    tags.put("priority", new Tag("high", 9));
    tags.put("audit", new Tag("required", 1));

    final var src = new MetadataOrder("o-4", meta, tags);

    final var fwd = mapper.forward(src);
    assertThat(fwd.getMetadata()).containsExactlyEntriesOf(meta);

    // Value type recurses: Tag → TagDto, fields auto-inferred by name.
    assertThat(fwd.getTags()).hasSize(2);
    assertThat(fwd.getTags().get("priority").getLabel()).isEqualTo("high");
    assertThat(fwd.getTags().get("priority").getWeight()).isEqualTo(9);
    assertThat(fwd.getTags().get("audit").getLabel()).isEqualTo("required");
    // LinkedHashMap order should be preserved by Iso.liftMapValues.
    assertThat(fwd.getTags().keySet()).containsExactly("priority", "audit");

    final var back = mapper.backward(fwd);
    assertThat(back.metadata()).containsExactlyEntriesOf(meta);
    assertThat(back.tags()).containsKeys("priority", "audit");
    assertThat(back.tags().get("priority")).isEqualTo(new Tag("high", 9));
    assertThat(back.tags().get("audit")).isEqualTo(new Tag("required", 1));
  }

  @Test
  void keyTypeMismatchSurfacesPreciseError() {
    // Build a pair where the source declares Map<String, String> but the target declares
    // Map<Long, String>. DeepMap.autoIso should reject the mismatched key class with a precise
    // IllegalStateException pointing at the component name and the key types involved.
    assertThatThrownBy(() -> Telescope.mapper(StringKeyHolder.class, LongKeyHolder.class, writeBeans(SETTERS)))
      .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class)
      .hasMessageContaining("data")
      .hasMessageContaining("String")
      .hasMessageContaining("Long");
  }

  // --- fixtures for the key-mismatch case (kept local to the test class) -----------------

  public record StringKeyHolder(Map<String, String> data) {}

  public static class LongKeyHolder {

    private Map<Long, String> data;

    public LongKeyHolder() {}

    public Map<Long, String> getData() {
      return data;
    }

    public void setData(final Map<Long, String> data) {
      this.data = data;
    }
  }
}
