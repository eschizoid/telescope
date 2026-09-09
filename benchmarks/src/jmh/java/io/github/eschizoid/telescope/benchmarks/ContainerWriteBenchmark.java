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
 * <p>Cardinality is the dimension that matters. A hash table sized for n entries resizes on the nth
 * insert at the default load factor, so a rebuild that mis-sizes its table pays one full
 * reallocation plus a rehash of everything it has already inserted — invisible at small n, and
 * roughly a third of the cost by the tens of thousands. Each telescope row has a hand-written loop
 * beside it as the floor; the ratio between them is the whole signal.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=ContainerWriteBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
public class ContainerWriteBenchmark {

  public record TagSet(Set<String> tags) {}

  public record TagMap(Map<String, String> tags) {}

  @Param({ "16", "256", "4096", "32768" })
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
