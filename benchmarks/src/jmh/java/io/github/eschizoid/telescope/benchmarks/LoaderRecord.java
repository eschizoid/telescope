package io.github.eschizoid.telescope.benchmarks;

/**
 * A record that is not public, so its accessors cannot be reached by code outside its own runtime
 * package. {@link LmfBenchmark} reads it once as loaded by the application class loader, where
 * telescope's lookup into it has full privilege, and once as defined by a loader of its own, where
 * the lookup loses module access and the accessor is a method handle closure.
 */
record LoaderRecord(String id, String name, int age) {}
