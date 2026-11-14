package com.migrateflow.execution;

import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Date;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Spring Batch execution (spec 7.2). Two jobs (rehearsalJob, cutoverJob),
 * each: loadCustomers then loadOrders, chunk-oriented. The identifying job
 * parameter is runId only; restarts reuse the same JobInstance.
 *
 * Idempotency has two layers: readers persist their position in the step
 * execution context, and writers consult control.applied_rows inside the
 * same chunk transaction as the target insert - a replayed row with the
 * same normalized hash is treated as already applied; a mismatch fails.
 */
@Configuration
public class BatchConfig {

    static final class ListReader<T> implements ItemStreamReader<T> {
        private final java.util.function.Supplier<List<T>> supplier;
        private Iterator<T> iterator;
        private int readCount;

        ListReader(java.util.function.Supplier<List<T>> supplier) { this.supplier = supplier; }

        @Override
        public void open(ExecutionContext ctx) {
            List<T> items = supplier.get();
            readCount = ctx.getInt("read.count", 0);
            iterator = items.listIterator(Math.min(readCount, items.size()));
        }

        @Override
        public T read() {
            if (iterator != null && iterator.hasNext()) {
                readCount++;
                return iterator.next();
            }
            return null;
        }

        @Override
        public void update(ExecutionContext ctx) {
            ctx.putInt("read.count", readCount);
        }

        @Override
        public void close() {}
    }

    @Bean
    @StepScope
    public ListReader<AcceptedRows.CustomerTarget> customerReader(
            AcceptedRows loader,
            @Value("#{jobParameters['manifestId']}") String manifestId,
            @Value("#{jobParameters['snapshotId']}") String snapshotId) {
        return new ListReader<>(() -> loader.customers(UUID.fromString(manifestId), UUID.fromString(snapshotId)));
    }

    @Bean
    @StepScope
    public ListReader<AcceptedRows.OrderTarget> orderReader(
            AcceptedRows loader,
            @Value("#{jobParameters['manifestId']}") String manifestId,
            @Value("#{jobParameters['snapshotId']}") String snapshotId) {
        return new ListReader<>(() -> loader.orders(UUID.fromString(manifestId), UUID.fromString(snapshotId)));
    }

    @Bean
    @StepScope
    public ItemWriter<AcceptedRows.CustomerTarget> customerWriter(
            JdbcTemplate jdbc, FaultInjection fault,
            @Value("#{jobParameters['runId']}") String runId,
            @Value("#{jobParameters['manifestId']}") String manifestId,
            @Value("#{jobParameters['scope']}") String scope) {
        UUID run = UUID.fromString(runId);
        UUID manifest = UUID.fromString(manifestId);
        boolean rehearsal = "REHEARSAL".equals(scope);
        return chunk -> {
            fault.beforeWrite(run);
            for (AcceptedRows.CustomerTarget item : chunk.getItems()) {
                if (alreadyApplied(jdbc, run, "CUSTOMERS", item)) continue;
                if (rehearsal) {
                    jdbc.update("INSERT INTO rehearsal.customers (id, rehearsal_run_id, external_customer_id, full_name, email, joined_on, "
                                    + "origin_run_id, origin_manifest_id, source_file_id, source_row_number) VALUES (?,?,?,?,?,?,?,?,?,?)",
                            item.targetId(), run, item.externalCustomerId(), item.fullName(), item.email(),
                            item.joinedOn() == null ? null : Date.valueOf(item.joinedOn()),
                            run, manifest, item.sourceFileId(), item.sourceRowNumber());
                } else {
                    jdbc.update("INSERT INTO target.customers (id, external_customer_id, full_name, email, joined_on, "
                                    + "origin_run_id, origin_manifest_id, source_file_id, source_row_number) VALUES (?,?,?,?,?,?,?,?,?)",
                            item.targetId(), item.externalCustomerId(), item.fullName(), item.email(),
                            item.joinedOn() == null ? null : Date.valueOf(item.joinedOn()),
                            run, manifest, item.sourceFileId(), item.sourceRowNumber());
                }
                jdbc.update("INSERT INTO control.applied_rows (id, run_id, kind, source_row_id, target_record_id, normalized_hash) "
                                + "VALUES (?,?, 'CUSTOMERS', ?, ?, ?)",
                        UUID.randomUUID(), run, item.sourceRowId(), item.targetId(), item.normalizedHash());
            }
        };
    }

    @Bean
    @StepScope
    public ItemWriter<AcceptedRows.OrderTarget> orderWriter(
            JdbcTemplate jdbc, FaultInjection fault,
            @Value("#{jobParameters['runId']}") String runId,
            @Value("#{jobParameters['manifestId']}") String manifestId,
            @Value("#{jobParameters['scope']}") String scope) {
        UUID run = UUID.fromString(runId);
        UUID manifest = UUID.fromString(manifestId);
        boolean rehearsal = "REHEARSAL".equals(scope);
        return chunk -> {
            fault.beforeWrite(run);
            for (AcceptedRows.OrderTarget item : chunk.getItems()) {
                if (alreadyApplied(jdbc, run, "ORDERS", item)) continue;
                if (rehearsal) {
                    jdbc.update("INSERT INTO rehearsal.orders (id, rehearsal_run_id, external_order_id, customer_id, order_date, amount_yen, status, "
                                    + "origin_run_id, origin_manifest_id, source_file_id, source_row_number) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                            item.targetId(), run, item.externalOrderId(), item.customerTargetId(),
                            Date.valueOf(item.orderDate()), item.amountYen(), item.status(),
                            run, manifest, item.sourceFileId(), item.sourceRowNumber());
                } else {
                    jdbc.update("INSERT INTO target.orders (id, external_order_id, customer_id, order_date, amount_yen, status, "
                                    + "origin_run_id, origin_manifest_id, source_file_id, source_row_number) VALUES (?,?,?,?,?,?,?,?,?,?)",
                            item.targetId(), item.externalOrderId(), item.customerTargetId(),
                            Date.valueOf(item.orderDate()), item.amountYen(), item.status(),
                            run, manifest, item.sourceFileId(), item.sourceRowNumber());
                }
                jdbc.update("INSERT INTO control.applied_rows (id, run_id, kind, source_row_id, target_record_id, normalized_hash) "
                                + "VALUES (?,?, 'ORDERS', ?, ?, ?)",
                        UUID.randomUUID(), run, item.sourceRowId(), item.targetId(), item.normalizedHash());
            }
        };
    }

    private static boolean alreadyApplied(JdbcTemplate jdbc, UUID runId, String kind, Object item) {
        UUID sourceRowId;
        String hash;
        if (item instanceof AcceptedRows.CustomerTarget c) { sourceRowId = c.sourceRowId(); hash = c.normalizedHash(); }
        else if (item instanceof AcceptedRows.OrderTarget o) { sourceRowId = o.sourceRowId(); hash = o.normalizedHash(); }
        else throw new IllegalArgumentException("Unknown item " + item);
        List<String> existing = jdbc.query(
                "SELECT normalized_hash FROM control.applied_rows WHERE run_id = ? AND kind = ? AND source_row_id = ?",
                (rs, n) -> rs.getString(1), runId, kind, sourceRowId);
        if (existing.isEmpty()) return false;
        if (!existing.get(0).equals(hash)) {
            // Same source row, different content: fail closed, never overwrite (spec 7.2).
            throw new IllegalStateException("Applied-row ledger conflict for " + kind + " source row " + sourceRowId);
        }
        return true;
    }

    @Bean
    public ChunkListener faultChunkListener(FaultInjection fault) {
        return new ChunkListener() {
            @Override
            public void afterChunk(ChunkContext context) {
                Object runId = context.getStepContext().getJobParameters().get("runId");
                if (runId != null) {
                    fault.onChunkCommitted(UUID.fromString(String.valueOf(runId)));
                }
            }
        };
    }

    @Bean
    public Step loadCustomersStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                                  ListReader<AcceptedRows.CustomerTarget> customerReader,
                                  ItemWriter<AcceptedRows.CustomerTarget> customerWriter,
                                  ChunkListener faultChunkListener,
                                  @Value("${migrateflow.batch-chunk-size}") int chunkSize) {
        return new StepBuilder("loadCustomers", jobRepository)
                .<AcceptedRows.CustomerTarget, AcceptedRows.CustomerTarget>chunk(chunkSize, txManager)
                .reader(customerReader)
                .writer(customerWriter)
                .listener(faultChunkListener)
                .build();
    }

    @Bean
    public Step loadOrdersStep(JobRepository jobRepository, PlatformTransactionManager txManager,
                               ListReader<AcceptedRows.OrderTarget> orderReader,
                               ItemWriter<AcceptedRows.OrderTarget> orderWriter,
                               ChunkListener faultChunkListener,
                               @Value("${migrateflow.batch-chunk-size}") int chunkSize) {
        return new StepBuilder("loadOrders", jobRepository)
                .<AcceptedRows.OrderTarget, AcceptedRows.OrderTarget>chunk(chunkSize, txManager)
                .reader(orderReader)
                .writer(orderWriter)
                .listener(faultChunkListener)
                .build();
    }

    @Bean
    public Job rehearsalJob(JobRepository jobRepository, Step loadCustomersStep, Step loadOrdersStep) {
        return new JobBuilder("rehearsalJob", jobRepository)
                .start(loadCustomersStep).next(loadOrdersStep).build();
    }

    @Bean
    public Job cutoverJob(JobRepository jobRepository, Step loadCustomersStep, Step loadOrdersStep) {
        return new JobBuilder("cutoverJob", jobRepository)
                .start(loadCustomersStep).next(loadOrdersStep).build();
    }
}
