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

package org.apache.paimon.data.columnar.heap;

import org.apache.paimon.data.columnar.writable.AbstractWritableVector;
import org.apache.paimon.memory.MemorySegment;

import java.nio.ByteOrder;
import java.util.Arrays;

/** Heap vector that nullable shared structure. */
public abstract class AbstractHeapVector extends AbstractWritableVector
        implements ElementCountable {

    public static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    public static final sun.misc.Unsafe UNSAFE = MemorySegment.UNSAFE;
    public static final int BYTE_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    public static final int INT_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(int[].class);
    public static final int LONG_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(long[].class);
    public static final int FLOAT_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(float[].class);
    public static final int DOUBLE_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(double[].class);

    /*
     * If hasNulls is true, then this array contains true if the value
     * is null, otherwise false. The array is always allocated, so a batch can be re-used
     * later and nulls added.
     */
    protected boolean[] isNull;

    /** Reusable column for ids of dictionary. */
    protected HeapIntVector dictionaryIds;

    public AbstractHeapVector(int capacity) {
        super(capacity);
        isNull = new boolean[capacity];
    }

    /** Resets the column to default state. - fills the isNull array with false. */
    @Override
    public void reset() {
        super.reset();
        if (isNull.length != capacity) {
            isNull = new boolean[capacity];
        } else {
            Arrays.fill(isNull, false);
        }
        if (dictionaryIds != null) {
            dictionaryIds.reset();
        }
    }

    @Override
    public void setNullAt(int i) {
        isNull[i] = true;
        noNulls = false;
    }

    @Override
    public void setNulls(int i, int count) {
        for (int j = 0; j < count; j++) {
            isNull[i + j] = true;
        }
        if (count > 0) {
            noNulls = false;
        }
    }

    @Override
    public void fillWithNulls() {
        this.noNulls = false;
        Arrays.fill(isNull, true);
    }

    @Override
    public boolean isNullAt(int i) {
        return isAllNull || (!noNulls && isNull[i]);
    }

    @Override
    public HeapIntVector reserveDictionaryIds(int capacity) {
        if (dictionaryIds == null) {
            dictionaryIds = new HeapIntVector(capacity);
        } else {
            if (capacity > dictionaryIds.vector.length) {
                int current = dictionaryIds.vector.length;
                while (current < capacity) {
                    current <<= 1;
                }
                dictionaryIds = new HeapIntVector(current);
            } else {
                dictionaryIds.reset();
            }
        }
        return dictionaryIds;
    }

    /** Returns the underlying integer column for ids of dictionary. */
    @Override
    public HeapIntVector getDictionaryIds() {
        return dictionaryIds;
    }

    @Override
    protected void reserveInternal(int newCapacity) {
        if (isNull.length < newCapacity) {
            isNull = Arrays.copyOf(isNull, newCapacity);
        }
        reserveForHeapVector(newCapacity);
    }

    abstract void reserveForHeapVector(int newCapacity);
}
