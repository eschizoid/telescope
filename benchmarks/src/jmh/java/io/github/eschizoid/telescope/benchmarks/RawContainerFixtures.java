package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.List;

/**
 * Raw container subtypes — the adopter shape {@code class ImageUrls extends ArrayList<ImageUrl>} —
 * which no other benchmark fixture has. A raw subtype declares only its implicit no-arg
 * constructor, because Java does not inherit constructors, so the generated helper cannot size it
 * from the source the way it sizes a JDK default impl.
 *
 * <p>The element types differ across the pair, which forces the element-bridging loop rather than
 * the {@code addAll}/{@code putAll} copy: that loop is where a hash container grows its table
 * repeatedly instead of once.
 *
 * <p>The {@code plain} field is the mixed shape — an interface on one side, a raw subtype on the
 * other — so one direction allocates a default impl that can be sized and the other a subtype that
 * cannot.
 */
record RawUrlA(String url) {}

record RawUrlB(String url) {}

@Bridge(RawHolderB.class)
record RawHolderA(RawListA urls, RawMapA index, List<RawUrlA> plain) {}

record RawHolderB(RawListB urls, RawMapB index, RawListB plain) {}
