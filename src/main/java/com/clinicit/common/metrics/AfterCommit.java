package com.clinicit.common.metrics;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs an action once the current transaction commits (immediately when there is none).
 * Metrics about clinic operations use it so that a rolled-back operation is never counted.
 */
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
