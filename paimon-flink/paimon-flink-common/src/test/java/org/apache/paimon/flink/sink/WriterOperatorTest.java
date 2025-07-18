/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.sink;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.flink.utils.InternalRowTypeSerializer;
import org.apache.paimon.flink.utils.InternalTypeInfo;
import org.apache.paimon.flink.utils.TestingMetricUtils;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.io.CompactIncrement;
import org.apache.paimon.io.IndexIncrement;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.FileStoreTableFactory;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.CommitMessageImpl;
import org.apache.paimon.table.sink.TableCommitImpl;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.table.source.StreamTableScan;
import org.apache.paimon.table.source.TableRead;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowKind;
import org.apache.paimon.types.RowType;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.metrics.Gauge;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.metrics.groups.OperatorMetricGroup;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.OneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link TableWriteOperator}. */
public class WriterOperatorTest {

    @TempDir public java.nio.file.Path tempDir;
    private Path tablePath;
    private String commitUser;

    @BeforeEach
    public void before() {
        tablePath = new Path(tempDir.toString());
        commitUser = UUID.randomUUID().toString();
    }

    @Test
    public void testPrimaryKeyTableMetrics() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {DataTypes.INT(), DataTypes.INT()}, new String[] {"a", "b"});

        Options options = new Options();
        options.set("bucket", "1");
        options.set("write-buffer-size", "256 b");
        options.set("write-buffer-spillable", "false");
        options.set("page-size", "32 b");

        FileStoreTable table =
                createFileStoreTable(
                        rowType, Collections.singletonList("a"), Collections.emptyList(), options);
        testMetricsImpl(table);
    }

    @Test
    public void testAppendOnlyTableMetrics() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {DataTypes.INT(), DataTypes.INT()}, new String[] {"a", "b"});

        Options options = new Options();
        options.set("write-buffer-for-append", "true");
        options.set("write-buffer-size", "256 b");
        options.set("page-size", "32 b");
        options.set("write-buffer-spillable", "false");

        FileStoreTable table =
                createFileStoreTable(
                        rowType, Collections.emptyList(), Collections.emptyList(), options);
        testMetricsImpl(table);
    }

    private void testMetricsImpl(FileStoreTable fileStoreTable) throws Exception {
        String tableName = tablePath.getName();
        RowDataStoreWriteOperator.Factory operatorFactory =
                getStoreSinkWriteOperatorFactory(fileStoreTable);
        OneInputStreamOperatorTestHarness<InternalRow, Committable> harness =
                createHarness(operatorFactory);

        TypeSerializer<Committable> serializer =
                new CommittableTypeInfo().createSerializer(new ExecutionConfig());
        harness.setup(serializer);
        harness.open();

        int size = 10;
        for (int i = 0; i < size; i++) {
            GenericRow row = GenericRow.of(1, 1);
            harness.processElement(row, 1);
        }
        harness.prepareSnapshotPreBarrier(1);
        harness.snapshot(1, 2);
        harness.notifyOfCompletedCheckpoint(1);

        OperatorMetricGroup metricGroup = harness.getOneInputOperator().getMetricGroup();
        MetricGroup writerBufferMetricGroup =
                metricGroup
                        .addGroup("paimon")
                        .addGroup("table", tableName)
                        .addGroup("writerBuffer");

        Gauge<Long> bufferPreemptCount =
                TestingMetricUtils.getGauge(writerBufferMetricGroup, "bufferPreemptCount");
        assertThat(bufferPreemptCount.getValue()).isEqualTo(0);

        Gauge<Long> totalWriteBufferSizeByte =
                TestingMetricUtils.getGauge(writerBufferMetricGroup, "totalWriteBufferSizeByte");
        assertThat(totalWriteBufferSizeByte.getValue()).isEqualTo(256);

        GenericRow row = GenericRow.of(1, 1);
        harness.processElement(row, 1);
        Gauge<Long> usedWriteBufferSizeByte =
                TestingMetricUtils.getGauge(writerBufferMetricGroup, "usedWriteBufferSizeByte");
        assertThat(usedWriteBufferSizeByte.getValue()).isGreaterThan(0);

        harness.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testLookupWithFailure(boolean lookupWait) throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {DataTypes.INT(), DataTypes.INT(), DataTypes.INT()},
                        new String[] {"pt", "k", "v"});

        Options options = new Options();
        options.set("bucket", "1");
        options.set("changelog-producer", "lookup");

        FileStoreTable fileStoreTable =
                createFileStoreTable(
                        rowType, Arrays.asList("pt", "k"), Collections.singletonList("k"), options);

        RowDataStoreWriteOperator.Factory operatorFactory =
                getLookupWriteOperatorFactory(fileStoreTable, lookupWait);
        OneInputStreamOperatorTestHarness<InternalRow, Committable> harness =
                createHarness(operatorFactory);

        TableCommitImpl commit = fileStoreTable.newCommit(commitUser);

        TypeSerializer<Committable> serializer =
                new CommittableTypeInfo().createSerializer(new ExecutionConfig());
        harness.setup(serializer);
        harness.open();

        // write basic records
        harness.processElement(GenericRow.of(1, 10, 100), 1);
        harness.processElement(GenericRow.of(2, 20, 200), 2);
        harness.processElement(GenericRow.of(3, 30, 300), 3);
        harness.prepareSnapshotPreBarrier(1);
        harness.snapshot(1, 10);
        harness.notifyOfCompletedCheckpoint(1);
        commitAll(harness, commit, 1);

        // apply changes but does not wait for compaction (lookupWait == false)
        // or does not commit compact result
        harness.processElement(GenericRow.of(1, 10, 101), 11);
        harness.processElement(GenericRow.of(3, 30, 301), 13);
        harness.prepareSnapshotPreBarrier(2);
        OperatorSubtaskState state = harness.snapshot(2, 20);
        harness.notifyOfCompletedCheckpoint(2);
        if (ThreadLocalRandom.current().nextBoolean()) {
            commitAll(harness, commit, 2);
        } else {
            commitAppend(harness, commit, 2);
        }

        // operator is closed due to failure
        harness.close();

        // re-create operator from state, this time wait for compaction to check result
        operatorFactory = getLookupWriteOperatorFactory(fileStoreTable, true);
        harness = createHarness(operatorFactory);
        harness.setup(serializer);
        harness.initializeState(state);
        harness.open();

        // write nothing, just wait for compaction
        harness.prepareSnapshotPreBarrier(3);
        harness.snapshot(3, 30);
        harness.notifyOfCompletedCheckpoint(3);
        commitAll(harness, commit, 3);

        harness.close();
        commit.close();

        // check result
        ReadBuilder readBuilder = fileStoreTable.newReadBuilder();
        StreamTableScan scan = readBuilder.newStreamScan();
        List<Split> splits = scan.plan().splits();
        TableRead read = readBuilder.newRead();
        RecordReader<InternalRow> reader = read.createReader(splits);
        List<String> actual = new ArrayList<>();
        reader.forEachRemaining(
                row ->
                        actual.add(
                                String.format(
                                        "%s[%d, %d, %d]",
                                        row.getRowKind().shortString(),
                                        row.getInt(0),
                                        row.getInt(1),
                                        row.getInt(2))));
        assertThat(actual)
                .containsExactlyInAnyOrder("+I[1, 10, 101]", "+I[2, 20, 200]", "+I[3, 30, 301]");
    }

    @Test
    public void testGentleLookupWithFailure() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {DataTypes.INT(), DataTypes.INT(), DataTypes.INT()},
                        new String[] {"pt", "k", "v"});

        int lookupCompactMaxInterval = 5;

        Options options = new Options();
        options.set("bucket", "1");
        options.set("changelog-producer", "lookup");
        options.set(CoreOptions.LOOKUP_COMPACT, CoreOptions.LookupCompactMode.GENTLE);
        options.set(CoreOptions.LOOKUP_COMPACT_MAX_INTERVAL, lookupCompactMaxInterval);

        FileStoreTable fileStoreTable =
                createFileStoreTable(
                        rowType, Arrays.asList("pt", "k"), Collections.singletonList("k"), options);

        RowDataStoreWriteOperator.Factory operatorFactory =
                getLookupWriteOperatorFactory(fileStoreTable, false);
        OneInputStreamOperatorTestHarness<InternalRow, Committable> harness =
                createHarness(operatorFactory);

        TableCommitImpl commit = fileStoreTable.newCommit(commitUser);

        TypeSerializer<Committable> serializer =
                new CommittableTypeInfo().createSerializer(new ExecutionConfig());
        harness.setup(serializer);
        harness.open();

        // write basic records
        harness.processElement(GenericRow.of(1, 10, 100), 1);
        harness.processElement(GenericRow.of(2, 20, 200), 2);
        harness.processElement(GenericRow.of(3, 30, 300), 3);
        harness.prepareSnapshotPreBarrier(1);
        harness.snapshot(1, 10);
        harness.notifyOfCompletedCheckpoint(1);
        commitAll(harness, commit, 1);

        harness.processElement(GenericRow.of(1, 10, 101), 11);
        harness.processElement(GenericRow.of(3, 30, 301), 13);
        harness.prepareSnapshotPreBarrier(2);
        OperatorSubtaskState state = harness.snapshot(2, 20);
        harness.notifyOfCompletedCheckpoint(2);
        commitAll(harness, commit, 2);

        // operator is closed due to failure
        harness.close();

        // restore operator to trigger gentle lookup compaction
        operatorFactory = getLookupWriteOperatorFactory(fileStoreTable, true);
        harness = createHarness(operatorFactory);
        harness.setup(serializer);
        harness.initializeState(state);
        harness.open();

        // write nothing, wait for compaction
        harness.prepareSnapshotPreBarrier(3);
        harness.snapshot(3, 30);
        harness.notifyOfCompletedCheckpoint(3);
        commitAll(harness, commit, 3);

        harness.close();

        // check gentle lookup compaction result, no data available
        ReadBuilder readBuilder = fileStoreTable.newReadBuilder();
        StreamTableScan scan = readBuilder.newStreamScan();
        List<Split> splits = scan.plan().splits();
        TableRead read = readBuilder.newRead();
        RecordReader<InternalRow> reader = read.createReader(splits);
        List<String> actual = new ArrayList<>();
        reader.forEachRemaining(
                row ->
                        actual.add(
                                String.format(
                                        "%s[%d, %d, %d]",
                                        row.getRowKind().shortString(),
                                        row.getInt(0),
                                        row.getInt(1),
                                        row.getInt(2))));
        assertThat(actual).isEmpty();

        // restore operator to force trigger gentle lookup compaction
        operatorFactory = getLookupWriteOperatorFactory(fileStoreTable, true);
        harness = createHarness(operatorFactory);
        harness.setup(serializer);
        harness.initializeState(state);
        harness.open();

        // force trigger gentle lookup compaction by max interval
        // start count from 1, since the first lookup compact will be triggered when initialize
        // AsyncLookupWriteOperator
        for (int i = 1; i < lookupCompactMaxInterval; i++) {
            long checkpointId = i + 3;
            harness.prepareSnapshotPreBarrier(checkpointId);
            harness.snapshot(checkpointId, i + 30);
            harness.notifyOfCompletedCheckpoint(checkpointId);
            commitAll(harness, commit, checkpointId);
        }

        harness.close();
        commit.close();

        // check all partition result
        readBuilder = fileStoreTable.newReadBuilder();
        scan = readBuilder.newStreamScan();
        splits = scan.plan().splits();
        read = readBuilder.newRead();
        reader = read.createReader(splits);
        List<String> finalResult = new ArrayList<>();
        reader.forEachRemaining(
                row ->
                        finalResult.add(
                                String.format(
                                        "%s[%d, %d, %d]",
                                        row.getRowKind().shortString(),
                                        row.getInt(0),
                                        row.getInt(1),
                                        row.getInt(2))));
        assertThat(finalResult)
                .containsExactlyInAnyOrder("+I[1, 10, 101]", "+I[2, 20, 200]", "+I[3, 30, 301]");
    }

    @Test
    public void testChangelog() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {DataTypes.INT(), DataTypes.INT(), DataTypes.INT()},
                        new String[] {"pt", "k", "v"});

        Options options = new Options();
        options.set("bucket", "1");
        options.set("changelog-producer", "input");

        FileStoreTable fileStoreTable =
                createFileStoreTable(
                        rowType, Arrays.asList("pt", "k"), Collections.singletonList("k"), options);
        RowDataStoreWriteOperator.Factory operatorFactory =
                getStoreSinkWriteOperatorFactory(fileStoreTable);
        OneInputStreamOperatorTestHarness<InternalRow, Committable> harness =
                createHarness(operatorFactory);

        TableCommitImpl commit = fileStoreTable.newCommit(commitUser);

        TypeSerializer<Committable> serializer =
                new CommittableTypeInfo().createSerializer(new ExecutionConfig());
        harness.setup(serializer);
        harness.open();

        // write basic records
        harness.processElement(GenericRow.ofKind(RowKind.INSERT, 1, 10, 100), 1);
        harness.processElement(GenericRow.ofKind(RowKind.DELETE, 1, 10, 200), 2);
        harness.processElement(GenericRow.ofKind(RowKind.INSERT, 1, 10, 300), 3);
        harness.prepareSnapshotPreBarrier(1);
        harness.snapshot(1, 10);
        harness.notifyOfCompletedCheckpoint(1);
        commitAll(harness, commit, 1);
        harness.close();
        commit.close();

        // check result
        ReadBuilder readBuilder = fileStoreTable.newReadBuilder();
        StreamTableScan scan = readBuilder.newStreamScan();
        scan.restore(1L);
        List<Split> splits = scan.plan().splits();
        TableRead read = readBuilder.newRead();
        RecordReader<InternalRow> reader = read.createReader(splits);
        List<String> actual = new ArrayList<>();
        reader.forEachRemaining(
                row ->
                        actual.add(
                                String.format(
                                        "%s[%d, %d, %d]",
                                        row.getRowKind().shortString(),
                                        row.getInt(0),
                                        row.getInt(1),
                                        row.getInt(2))));
        assertThat(actual)
                .containsExactlyInAnyOrder("+I[1, 10, 100]", "-D[1, 10, 200]", "+I[1, 10, 300]");
    }

    @Test
    public void testNumWritersMetric() throws Exception {
        String tableName = tablePath.getName();
        RowType rowType =
                RowType.of(
                        new DataType[] {DataTypes.INT(), DataTypes.INT(), DataTypes.INT()},
                        new String[] {"pt", "k", "v"});

        Options options = new Options();
        options.set("bucket", "1");
        options.set("write-buffer-size", "256 b");
        options.set("write-buffer-spillable", "false");
        options.set("page-size", "32 b");

        FileStoreTable fileStoreTable =
                createFileStoreTable(
                        rowType,
                        Arrays.asList("pt", "k"),
                        Collections.singletonList("pt"),
                        options);
        TableCommitImpl commit = fileStoreTable.newCommit(commitUser);

        RowDataStoreWriteOperator.Factory operatorFactory =
                getStoreSinkWriteOperatorFactory(fileStoreTable);
        OneInputStreamOperatorTestHarness<InternalRow, Committable> harness =
                createHarness(operatorFactory);

        TypeSerializer<Committable> serializer =
                new CommittableTypeInfo().createSerializer(new ExecutionConfig());
        harness.setup(serializer);
        harness.open();

        OperatorMetricGroup metricGroup = harness.getOneInputOperator().getMetricGroup();
        MetricGroup writerBufferMetricGroup =
                metricGroup
                        .addGroup("paimon")
                        .addGroup("table", tableName)
                        .addGroup("writerBuffer");

        Gauge<Integer> numWriters =
                TestingMetricUtils.getGauge(writerBufferMetricGroup, "numWriters");

        // write into three partitions
        harness.processElement(GenericRow.of(1, 10, 100), 1);
        harness.processElement(GenericRow.of(2, 20, 200), 2);
        harness.processElement(GenericRow.of(3, 30, 300), 3);
        assertThat(numWriters.getValue()).isEqualTo(3);

        // commit messages in three partitions, no writer should be cleaned
        harness.prepareSnapshotPreBarrier(1);
        harness.snapshot(1, 10);
        harness.notifyOfCompletedCheckpoint(1);
        commit.commit(
                1,
                harness.extractOutputValues().stream()
                        .map(c -> (CommitMessage) c.wrappedCommittable())
                        .collect(Collectors.toList()));
        assertThat(numWriters.getValue()).isEqualTo(3);

        // write into two partitions
        harness.processElement(GenericRow.of(1, 11, 110), 11);
        harness.processElement(GenericRow.of(3, 13, 130), 13);
        // checkpoint has not come yet, so no writer should be cleaned
        assertThat(numWriters.getValue()).isEqualTo(3);

        // checkpoint comes, note that prepareSnapshotPreBarrier is called before commit, so writer
        // of partition 2 is not cleaned, but it should be cleaned in the next checkpoint
        harness.prepareSnapshotPreBarrier(2);
        harness.snapshot(2, 20);
        harness.notifyOfCompletedCheckpoint(2);
        commit.commit(
                2,
                harness.extractOutputValues().stream()
                        .map(c -> (CommitMessage) c.wrappedCommittable())
                        .collect(Collectors.toList()));
        harness.prepareSnapshotPreBarrier(3);

        // writer of partition 2 should be cleaned in this snapshot
        harness.snapshot(3, 30);
        harness.notifyOfCompletedCheckpoint(3);
        assertThat(numWriters.getValue()).isEqualTo(2);

        harness.endInput();
        harness.close();
        commit.close();
    }

    // ------------------------------------------------------------------------
    //  Test utils
    // ------------------------------------------------------------------------

    private RowDataStoreWriteOperator.Factory getStoreSinkWriteOperatorFactory(
            FileStoreTable fileStoreTable) {
        return new RowDataStoreWriteOperator.Factory(
                fileStoreTable,
                null,
                (table, commitUser, state, ioManager, memoryPool, metricGroup) ->
                        new StoreSinkWriteImpl(
                                table,
                                commitUser,
                                state,
                                ioManager,
                                false,
                                false,
                                true,
                                memoryPool,
                                metricGroup),
                commitUser);
    }

    private RowDataStoreWriteOperator.Factory getLookupWriteOperatorFactory(
            FileStoreTable fileStoreTable, boolean waitCompaction) {
        return new RowDataStoreWriteOperator.Factory(
                fileStoreTable,
                null,
                (table, commitUser, state, ioManager, memoryPool, metricGroup) ->
                        new LookupSinkWrite(
                                table,
                                commitUser,
                                state,
                                ioManager,
                                false,
                                waitCompaction,
                                true,
                                memoryPool,
                                metricGroup),
                commitUser);
    }

    @SuppressWarnings("unchecked")
    private void commitAll(
            OneInputStreamOperatorTestHarness<InternalRow, Committable> harness,
            TableCommitImpl commit,
            long commitIdentifier) {
        List<CommitMessage> commitMessages = new ArrayList<>();
        while (!harness.getOutput().isEmpty()) {
            Committable committable =
                    ((StreamRecord<Committable>) harness.getOutput().poll()).getValue();
            assertThat(committable.kind()).isEqualTo(Committable.Kind.FILE);
            commitMessages.add((CommitMessage) committable.wrappedCommittable());
        }
        commit.commit(commitIdentifier, commitMessages);
    }

    @SuppressWarnings("unchecked")
    private void commitAppend(
            OneInputStreamOperatorTestHarness<InternalRow, Committable> harness,
            TableCommitImpl commit,
            long commitIdentifier) {
        List<CommitMessage> commitMessages = new ArrayList<>();
        while (!harness.getOutput().isEmpty()) {
            Committable committable =
                    ((StreamRecord<Committable>) harness.getOutput().poll()).getValue();
            assertThat(committable.kind()).isEqualTo(Committable.Kind.FILE);
            CommitMessageImpl message = (CommitMessageImpl) committable.wrappedCommittable();
            CommitMessageImpl newMessage =
                    new CommitMessageImpl(
                            message.partition(),
                            message.bucket(),
                            message.totalBuckets(),
                            message.newFilesIncrement(),
                            CompactIncrement.emptyIncrement(),
                            new IndexIncrement(Collections.emptyList()));
            commitMessages.add(newMessage);
        }
        commit.commit(commitIdentifier, commitMessages);
    }

    private FileStoreTable createFileStoreTable(
            RowType rowType, List<String> primaryKeys, List<String> partitionKeys, Options conf)
            throws Exception {
        conf.set(CoreOptions.PATH, tablePath.toString());
        SchemaManager schemaManager = new SchemaManager(LocalFileIO.create(), tablePath);
        schemaManager.createTable(
                new Schema(rowType.getFields(), partitionKeys, primaryKeys, conf.toMap(), ""));
        return FileStoreTableFactory.create(LocalFileIO.create(), conf);
    }

    private OneInputStreamOperatorTestHarness<InternalRow, Committable> createHarness(
            RowDataStoreWriteOperator.Factory operatorFactory) throws Exception {
        InternalTypeInfo<InternalRow> internalRowInternalTypeInfo =
                new InternalTypeInfo<>(new InternalRowTypeSerializer(RowType.builder().build()));
        return new OneInputStreamOperatorTestHarness<>(
                operatorFactory,
                internalRowInternalTypeInfo.createSerializer(new ExecutionConfig()));
    }
}
