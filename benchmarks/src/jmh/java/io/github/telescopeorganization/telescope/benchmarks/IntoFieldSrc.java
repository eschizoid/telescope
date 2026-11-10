package io.github.telescopeorganization.telescope.benchmarks;

/**
 * The five-property source of {@link IntoBenchmark} again, for the generated mapper {@link
 * IntoMappers#NARROW} declares on its field: the same properties as {@link
 * IntoBenchmark.NarrowRec}, so the field's mapper and the runtime mapper do the same work.
 */
public record IntoFieldSrc(String a, String b, String c, String d, String e) {}
