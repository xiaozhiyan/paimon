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

package org.apache.paimon.io;

import org.apache.paimon.data.InternalRow;
import org.apache.paimon.format.SimpleColStats;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.Preconditions;

import java.io.IOException;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * A {@link SingleFileWriter} which also produces statistics for each written field.
 *
 * @param <T> type of records to write.
 * @param <R> type of result to produce after writing a file.
 */
public abstract class StatsCollectingSingleFileWriter<T, R> extends SingleFileWriter<T, R> {

    private final RowType rowType;
    private final SimpleStatsProducer statsProducer;
    private final boolean isStatsDisabled;
    private final boolean statsRequirePerRecord;

    public StatsCollectingSingleFileWriter(
            FileIO fileIO,
            FileWriterContext context,
            Path path,
            Function<T, InternalRow> converter,
            RowType rowType,
            boolean asyncWrite) {
        super(fileIO, context.factory(), path, converter, context.compression(), asyncWrite);
        this.rowType = rowType;
        this.statsProducer = context.statsProducer();
        this.isStatsDisabled = statsProducer.isStatsDisabled();
        this.statsRequirePerRecord = statsProducer.requirePerRecord();
    }

    @Override
    public void write(T record) throws IOException {
        InternalRow rowData = writeImpl(record);
        if (!isStatsDisabled && statsRequirePerRecord) {
            statsProducer.collect(rowData);
        }
    }

    @Override
    public void writeBundle(BundleRecords bundle) throws IOException {
        if (statsRequirePerRecord) {
            throw new IllegalArgumentException(
                    String.format(
                            "Can't write bundle for %s, we may lose all the statistical information.",
                            statsProducer.getClass().getName()));
        }
        super.writeBundle(bundle);
    }

    public SimpleColStats[] fieldStats(long fileSize) throws IOException {
        Preconditions.checkState(closed, "Cannot access metric unless the writer is closed.");
        if (isStatsDisabled) {
            return IntStream.range(0, rowType.getFieldCount())
                    .mapToObj(i -> SimpleColStats.NONE)
                    .toArray(SimpleColStats[]::new);
        }

        return statsProducer.extract(fileIO, path, fileSize);
    }
}
