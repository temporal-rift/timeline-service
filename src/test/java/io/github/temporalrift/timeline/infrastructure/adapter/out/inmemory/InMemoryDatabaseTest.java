package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class InMemoryDatabaseTest {

    private InMemoryDatabase database;
    private Table<String, String> rows;
    private TransactionTemplate transaction;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        database = new InMemoryDatabase(Duration.ofSeconds(10));
        rows = database.table("rows");
        transaction = new TransactionTemplate(new InMemoryTransactionManager(database));
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("a write is visible to its own transaction at once and to others only after commit")
    void write_isVisibleToOthersOnlyAfterCommit() throws Exception {
        var written = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var writer = CompletableFuture.runAsync(
                () -> transaction.executeWithoutResult(status -> {
                    rows.put("k", "v");
                    assertThat(rows.find("k")).contains("v");
                    written.countDown();
                    await(commit);
                }),
                executor);
        assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(rows.find("k")).isEmpty();
        commit.countDown();
        writer.get(10, TimeUnit.SECONDS);

        assertThat(rows.find("k")).contains("v");
    }

    @Test
    @DisplayName("a rollback discards every write of the transaction, including removals")
    void rollback_discardsEveryWrite() {
        rows.put("kept", "before");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    rows.put("kept", "after");
                    rows.remove("kept");
                    rows.put("new", "v");
                    throw new IllegalStateException("boom");
                }))
                .hasMessage("boom");

        assertThat(rows.find("kept")).contains("before");
        assertThat(rows.find("new")).isEmpty();
    }

    @Test
    @DisplayName("a transaction marked rollback-only discards its writes on completion")
    void rollbackOnly_discardsWrites() {
        transaction.executeWithoutResult(status -> {
            rows.put("k", "v");
            status.setRollbackOnly();
        });

        assertThat(rows.find("k")).isEmpty();
    }

    @Test
    @DisplayName("a REQUIRES_NEW transaction commits on its own while the suspended outer one rolls back")
    void requiresNew_commitsIndependently() {
        var inner = new TransactionTemplate(new InMemoryTransactionManager(database));
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    rows.put("outer", "v");
                    inner.executeWithoutResult(innerStatus -> rows.put("inner", "v"));
                    assertThat(rows.find("outer")).contains("v");
                    throw new IllegalStateException("boom");
                }))
                .hasMessage("boom");

        assertThat(rows.find("inner")).contains("v");
        assertThat(rows.find("outer")).isEmpty();
    }

    @Test
    @DisplayName("a REQUIRED transaction joins the outer one, so the outer rollback discards its writes too")
    void required_joinsTheOuterTransaction() {
        transaction.executeWithoutResult(status -> {
            transaction.executeWithoutResult(inner -> rows.put("k", "v"));
            status.setRollbackOnly();
        });

        assertThat(rows.find("k")).isEmpty();
    }

    @Test
    @DisplayName("after-commit synchronisations run after the commit and not at all after a rollback")
    void afterCommit_runsOnlyOnCommit() {
        var committedValues = new java.util.ArrayList<java.util.Optional<String>>();
        TransactionSynchronization afterCommit = new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                committedValues.add(rows.find("k"));
            }
        };

        transaction.executeWithoutResult(status -> {
            rows.put("k", "v");
            TransactionSynchronizationManager.registerSynchronization(afterCommit);
        });
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    TransactionSynchronizationManager.registerSynchronization(afterCommit);
                    throw new IllegalStateException("boom");
                }))
                .hasMessage("boom");

        assertThat(committedValues).containsExactly(java.util.Optional.of("v"));
    }

    @Test
    @DisplayName("a second transaction locking a row waits for the first to commit, then sees its write")
    void rowLock_waitsForTheHolder() throws Exception {
        rows.put("k", "original");
        var locked = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var holder = CompletableFuture.runAsync(
                () -> transaction.executeWithoutResult(status -> {
                    rows.findForUpdate("k");
                    rows.put("k", "updated");
                    locked.countDown();
                    await(commit);
                }),
                executor);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        var waiter = CompletableFuture.supplyAsync(
                () -> transaction.execute(status -> rows.findForUpdate("k").orElseThrow()), executor);

        Thread.sleep(300);
        assertThat(waiter).isNotDone();
        commit.countDown();

        holder.get(10, TimeUnit.SECONDS);
        assertThat(waiter.get(10, TimeUnit.SECONDS)).isEqualTo("updated");
    }

    @Test
    @DisplayName("a concurrent insert of the same key waits, then fails once the first commits")
    void insertConflict_failsAfterTheFirstCommits() throws Exception {
        var inserted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var first = CompletableFuture.runAsync(
                () -> transaction.executeWithoutResult(status -> {
                    rows.insert("k", "first");
                    inserted.countDown();
                    await(commit);
                }),
                executor);
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
        var second = CompletableFuture.runAsync(
                () -> transaction.executeWithoutResult(status -> rows.insert("k", "second")), executor);

        Thread.sleep(300);
        assertThat(second).isNotDone();
        commit.countDown();

        first.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(DuplicateKeyException.class);
        assertThat(rows.find("k")).contains("first");
    }

    @Test
    @DisplayName("a concurrent insert of the same key succeeds when the first transaction rolls back")
    void insertConflict_succeedsAfterTheFirstRollsBack() throws Exception {
        var inserted = new CountDownLatch(1);
        var rollback = new CountDownLatch(1);
        var first = CompletableFuture.runAsync(
                () -> assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                            rows.insert("k", "first");
                            inserted.countDown();
                            await(rollback);
                            throw new IllegalStateException("boom");
                        }))
                        .hasMessage("boom"),
                executor);
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
        var second = CompletableFuture.runAsync(
                () -> transaction.executeWithoutResult(status -> rows.insert("k", "second")), executor);

        Thread.sleep(300);
        assertThat(second).isNotDone();
        rollback.countDown();

        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        assertThat(rows.find("k")).contains("second");
    }

    @Test
    @DisplayName("waiting for a row lock gives up with an error after the configured time")
    void lockWait_timesOut() throws Exception {
        var shortWait = new InMemoryDatabase(Duration.ofMillis(200));
        var shortRows = shortWait.<String, String>table("rows");
        var shortTransaction = new TransactionTemplate(new InMemoryTransactionManager(shortWait));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = CompletableFuture.runAsync(
                () -> shortTransaction.executeWithoutResult(status -> {
                    shortRows.lock("k");
                    locked.countDown();
                    await(release);
                }),
                executor);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> shortTransaction.executeWithoutResult(status -> shortRows.lock("k")))
                .isInstanceOf(CannotAcquireLockException.class)
                .hasMessageContaining("rows");

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("an operation outside any transaction commits on its own")
    void implicitTransaction_commitsEachOperation() {
        rows.put("k", "v");
        rows.insert("other", "v");

        assertThat(rows.list()).containsExactly("v", "v");
        assertThat(rows.remove("k")).isTrue();
        assertThat(rows.remove("k")).isFalse();
        assertThat(rows.find("k")).isEmpty();
    }

    @Test
    @DisplayName("an operation run from an after-commit callback is a transaction of its own")
    void operationInAfterCommit_isItsOwnTransaction() {
        var ran = new AtomicBoolean();
        transaction.executeWithoutResult(status -> {
            rows.put("k", "v");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    rows.put("after", "v");
                    ran.set(true);
                }
            });
        });

        assertThat(ran).isTrue();
        assertThat(rows.find("after")).contains("v");
    }

    @Test
    @DisplayName("two databases share nothing")
    void databases_areIndependent() {
        var other = new InMemoryDatabase();
        other.<String, String>table("rows").put("k", "other");
        rows.put("k", "mine");

        assertThat(other.<String, String>table("rows").find("k")).contains("other");
        assertThat(rows.find("k")).contains("mine");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
