package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;

/**
 * Spring transaction manager over an {@link InMemoryDatabase}. It supplies only the resource handling — begin, commit,
 * rollback, suspend and resume — so propagation, synchronisations and transactional event listeners behave exactly as
 * they do over the JPA manager.
 */
public final class InMemoryTransactionManager extends AbstractPlatformTransactionManager {

    private final InMemoryDatabase database;

    public InMemoryTransactionManager(InMemoryDatabase database) {
        this.database = database;
    }

    @Override
    protected Object doGetTransaction() {
        return new Handle(database.current());
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((Handle) transaction).transaction != null;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        ((Handle) transaction).transaction = database.begin();
    }

    @Override
    protected Object doSuspend(Object transaction) {
        var handle = (Handle) transaction;
        handle.transaction = null;
        return database.unbind();
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
        database.bind((Transaction) suspendedResources);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        database.commit(((Handle) status.getTransaction()).transaction);
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        database.rollback(((Handle) status.getTransaction()).transaction);
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
        ((Handle) status.getTransaction()).transaction.markRollbackOnly();
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        database.unbind();
        ((Handle) transaction).transaction = null;
    }

    private static final class Handle implements SmartTransactionObject {

        private Transaction transaction;

        private Handle(Transaction transaction) {
            this.transaction = transaction;
        }

        @Override
        public boolean isRollbackOnly() {
            return transaction != null && transaction.rollbackOnly();
        }

        @Override
        public void flush() {
            // Writes are staged in the transaction and applied at commit; there is nothing to flush.
        }
    }
}
