package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.conversion.ContainerCopy;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * {@code into} writing an order's list of entity lines, sent back as the order holds them, across
 * the list's size: merged by identifier with telescope-jpa's container policy registered, and
 * cleared and refilled without it, on the generated path and the runtime path.
 *
 * <p>This module's class path carries telescope-jpa without its service registration, so the policy
 * {@code ContainerCopy} takes is fixed for the whole JVM. Each side is therefore loaded by a class
 * loader of its own that defines telescope's classes and this module's itself: {@code merged*}
 * through one that also serves the registration, so {@code ContainerCopy} takes the module's
 * policy, and {@code refilled*} through one that serves none, so it takes the standard policy, over
 * the very same entity classes. Each benchmark calls one {@link MbDrive} through {@link Supplier},
 * which costs every row the same.
 *
 * <p>{@code handWritten} is the control: the loop a developer writes to set each line's two
 * properties from the line sent at its index, through the same {@link Supplier}, reaching no
 * telescope code, so no change to the merge moves it.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=MergeByIdBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MergeByIdBenchmark {

  @Param({ "10", "100", "1000" })
  public int size;

  private URLClassLoader merging;
  private URLClassLoader refilling;
  private Supplier<Object> mergedGenerated;
  private Supplier<Object> mergedRuntime;
  private Supplier<Object> refilledGenerated;
  private Supplier<Object> refilledRuntime;
  private Supplier<Object> handWritten;

  @Setup(Level.Trial)
  public void setup() throws ReflectiveOperationException, IOException {
    final var services = Files.createTempDirectory("merge-by-id");
    final var registration = services.resolve("META-INF/services/" + ContainerCopy.Policy.class.getName());
    Files.createDirectories(registration.getParent());
    Files.writeString(registration, "io.github.eschizoid.telescope.jpa.LoadedContainers\n");
    merging = isolated(services);
    refilling = isolated(null);
    mergedGenerated = drive(merging, "generated", true);
    mergedRuntime = drive(merging, "runtime", true);
    refilledGenerated = drive(refilling, "generated", false);
    refilledRuntime = drive(refilling, "runtime", false);
    handWritten = drive(refilling, "hand", true);
  }

  @TearDown(Level.Trial)
  public void tearDown() throws IOException {
    merging.close();
    refilling.close();
  }

  @Benchmark
  public Object mergedGenerated() {
    return mergedGenerated.get();
  }

  @Benchmark
  public Object mergedRuntime() {
    return mergedRuntime.get();
  }

  @Benchmark
  public Object refilledGenerated() {
    return refilledGenerated.get();
  }

  @Benchmark
  public Object refilledRuntime() {
    return refilledRuntime.get();
  }

  @Benchmark
  public Object handWritten() {
    return handWritten.get();
  }

  /**
   * The drive of {@code mode} from {@code loader}, run once and checked: a merge keeps the line the
   * order held, and a refill puts a new one in its place.
   */
  @SuppressWarnings("unchecked")
  private Supplier<Object> drive(final ClassLoader loader, final String mode, final boolean keeps)
    throws ReflectiveOperationException {
    final var type = loader.loadClass(MbDrive.class.getName());
    if (type == MbDrive.class) throw new IllegalStateException("the drive was not loaded apart");
    final var drive = (Supplier<Object>) type.getConstructor(String.class, int.class).newInstance(mode, size);
    final var order = type.getMethod("order").invoke(drive);
    final var lines = (List<Object>) order.getClass().getMethod("getLines").invoke(order);
    final var first = lines.getFirst();
    drive.get();
    final var after = ((List<Object>) order.getClass().getMethod("getLines").invoke(order)).getFirst();
    if ((after == first) != keeps) throw new IllegalStateException(
      mode + ": the first line was " + (keeps ? "replaced" : "kept") + ", so another write is measured"
    );
    return drive;
  }

  /**
   * A loader over this JVM's class path that defines telescope's classes and this module's itself,
   * and serves {@code services}, a directory of service registrations, where it is not null.
   */
  private static URLClassLoader isolated(final Path services) throws MalformedURLException {
    final var urls = new ArrayList<URL>();
    if (services != null) urls.add(services.toUri().toURL());
    for (final var entry : System.getProperty("java.class.path").split(File.pathSeparator))
      urls.add(Path.of(entry).toUri().toURL());
    final var parent = MergeByIdBenchmark.class.getClassLoader();
    return new URLClassLoader(urls.toArray(URL[]::new), parent) {
      @Override
      protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        if (!name.startsWith("io.github.eschizoid.telescope.")) return super.loadClass(name, resolve);
        synchronized (getClassLoadingLock(name)) {
          final var loaded = findLoadedClass(name);
          if (loaded != null) return loaded;
          final var found = findClass(name);
          if (resolve) resolveClass(found);
          return found;
        }
      }
    };
  }
}
