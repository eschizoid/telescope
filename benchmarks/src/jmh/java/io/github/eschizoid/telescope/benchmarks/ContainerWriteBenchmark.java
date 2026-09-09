package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Traversal writes over hash containers, across cardinality. Sibling of {@link
 * ContainerAllocationBenchmark}, which drives {@code Telescope.mapper(...)} and therefore covers
 * the container lifts; these rows drive {@code .each(...)} / {@code .eachValue(...)} updates, which
 * rebuild through the traversal instead.
 *
 * <p>Both rows size their table the same way, so the ratio between them is traversal overhead, not
 * sizing — the sizing change itself is only visible by running this file against two builds. What
 * this gate catches is a future regression in either dimension: a telescope row drifting away from
 * its loop floor, or both drifting together.
 *
 * <p>The sizes deliberately straddle the power-of-two bands. A table built straight from an element
 * count only resizes when that count exceeds three quarters of the next power of two, so a sweep of
 * powers of two alone would sample only the band where a mis-sized table always resizes and never
 * the roughly half of sizes where it never does.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=ContainerWriteBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
public class ContainerWriteBenchmark {

  public record TagSet(Set<String> tags) {}

  public record TagMap(Map<String, String> tags) {}

  @Param({ "0", "1", "12", "16", "256", "3000", "4096", "32768" })
  public int size;

  private TagSet tagSet;
  private TagMap tagMap;
  private Telescope<TagSet, String> setElements;
  private Telescope<TagMap, String> mapValues;

  @Setup
  public void setup() {
    final var tags = LinkedHashSet.<String>newLinkedHashSet(size);
    final var byName = LinkedHashMap.<String, String>newLinkedHashMap(size);
    for (var i = 0; i < size; i++) {
      tags.add("tag" + i);
      byName.put("k" + i, "tag" + i);
    }
    tagSet = new TagSet(Collections.unmodifiableSet(tags));
    tagMap = new TagMap(Collections.unmodifiableMap(byName));
    setElements = Telescope.of(TagSet.class).each(TagSet::tags);
    mapValues = Telescope.of(TagMap.class).eachValue(TagMap::tags);
  }

  @Benchmark
  public TagSet eachSetUpdate() {
    return setElements.update(tagSet, String::toUpperCase);
  }

  /** The loop floor for a Set rebuild: one pass, one correctly sized table. */
  @Benchmark
  public Set<String> eachSetUpdateHand() {
    final var out = LinkedHashSet.<String>newLinkedHashSet(tagSet.tags().size());
    for (final var tag : tagSet.tags()) out.add(tag.toUpperCase());
    return Collections.unmodifiableSet(out);
  }

  @Benchmark
  public TagMap eachMapValueUpdate() {
    return mapValues.update(tagMap, String::toUpperCase);
  }

  /** The loop floor for a Map-values rebuild. */
  @Benchmark
  public Map<String, String> eachMapValueUpdateHand() {
    final var out = LinkedHashMap.<String, String>newLinkedHashMap(tagMap.tags().size());
    for (final var e : tagMap.tags().entrySet()) out.put(e.getKey(), e.getValue().toUpperCase());
    return Collections.unmodifiableMap(out);
  }
}
