package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.conversion.ContainerCopy;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * What telescope-jpa's container policy adds to the same-typed copy every mapper and bridge makes,
 * across the container's size: the {@code PersistenceUtil.isLoaded} call asked of a provider's
 * collection, and the class check that spares a JDK container the call.
 *
 * <p>{@code standardLoadedBag} and {@code standardArrayList} copy through {@code
 * ContainerCopy.standardCopy}, the copy the policy hands a loaded container to, and are the
 * controls: no change to the policy moves them. {@code policyLoadedBag} is the policy over a
 * Hibernate {@code PersistentBag} that holds its elements, so it pays for {@code isLoaded} and then
 * the same copy; {@code policyArrayList} is the policy over a JDK list, which never asks; {@code
 * policyUnloadedBag} is the policy sharing a bag that holds nothing yet.
 *
 * <p>The policy is instantiated directly. This module's class path carries telescope-jpa's classes
 * without its service registrations, so no other benchmark takes the policy, and Hibernate is on it
 * so the persistence utility asks a real provider.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=LoadCheckBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class LoadCheckBenchmark {

  @Param({ "1", "16", "256" })
  public int size;

  private ContainerCopy.Policy policy;
  private List<String> arrayList;
  private List<String> loadedBag;
  private List<String> unloadedBag;

  @Setup
  @SuppressWarnings("unchecked")
  public void setup() throws ReflectiveOperationException {
    policy = (ContainerCopy.Policy) Class.forName("io.github.eschizoid.telescope.jpa.LoadedContainers")
      .getConstructor()
      .newInstance();
    if (!policy.active()) {
      throw new IllegalStateException("the policy found no persistence API");
    }
    if (ServiceLoader.load(ContainerCopy.Policy.class).findFirst().isPresent()) {
      throw new IllegalStateException("a container policy is registered, so every other benchmark takes it");
    }
    arrayList = new ArrayList<>(size);
    for (var i = 0; i < size; i++) {
      arrayList.add("e" + i);
    }
    // A bag Hibernate builds to load into holds nothing and reports itself unloaded. The loaded one
    // is given its elements and marked loaded as a load marks it, with no session behind either.
    final var bag = Class.forName("org.hibernate.collection.spi.PersistentBag");
    unloadedBag = (List<String>) bag.getConstructor().newInstance();
    loadedBag = (List<String>) bag.getConstructor().newInstance();
    // The bag keeps its elements in two fields, a list and the same list seen as a collection.
    final var held = new ArrayList<>(arrayList);
    for (final var name : List.of("bag", "collection")) {
      final var field = bag.getDeclaredField(name);
      field.setAccessible(true);
      field.set(loadedBag, held);
    }
    final var loaded = bag.getSuperclass().getDeclaredMethod("setInitialized");
    loaded.setAccessible(true);
    loaded.invoke(loadedBag);
    if (policy.copy(unloadedBag, List.class) != unloadedBag) {
      throw new IllegalStateException("the bag was copied");
    }
    if (!policy.copy(loadedBag, List.class).equals(arrayList)) {
      throw new IllegalStateException("the copy differs");
    }
  }

  @Benchmark
  public Object standardLoadedBag() {
    return ContainerCopy.standardCopy(loadedBag, List.class);
  }

  @Benchmark
  public Object policyLoadedBag() {
    return policy.copy(loadedBag, List.class);
  }

  @Benchmark
  public Object standardArrayList() {
    return ContainerCopy.standardCopy(arrayList, List.class);
  }

  @Benchmark
  public Object policyArrayList() {
    return policy.copy(arrayList, List.class);
  }

  @Benchmark
  public Object policyUnloadedBag() {
    return policy.copy(unloadedBag, List.class);
  }
}
