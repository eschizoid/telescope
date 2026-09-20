package io.github.eschizoid.telescope;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** One immutable dispatcher per path; only the submitted task is allocated per focus. */
final class AsyncObserver<A> implements Consumer<A> {

  private static final System.Logger LOG = System.getLogger(AsyncObserver.class.getName());
  private final Consumer<? super A> callback;
  private final Executor executor;
  private final BiConsumer<? super A, ? super Throwable> onError;

  AsyncObserver(Consumer<? super A> callback, Executor executor, BiConsumer<? super A, ? super Throwable> onError) {
    this.callback = Objects.requireNonNull(callback, "callback");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.onError = Objects.requireNonNull(onError, "onError");
  }

  @Override
  public void accept(A value) {
    try {
      executor.execute(() -> invoke(value));
    } catch (RejectedExecutionException failure) {
      report(value, failure);
    }
  }

  private void invoke(A value) {
    try {
      callback.accept(value);
    } catch (RuntimeException failure) {
      report(value, failure);
    }
  }

  private void report(A value, Throwable failure) {
    try {
      onError.accept(value, failure);
    } catch (RuntimeException handlerFailure) {
      LOG.log(System.Logger.Level.ERROR, "Async observer error handler failed", handlerFailure);
    }
  }
}
