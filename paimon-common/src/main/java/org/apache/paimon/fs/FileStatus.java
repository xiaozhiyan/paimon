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

package org.apache.paimon.fs;

import org.apache.paimon.annotation.Public;

import javax.annotation.Nullable;

import java.util.Map;

/**
 * Interface that represents the client side information for a file independent of the file system.
 *
 * @since 0.4.0
 */
@Public
public interface FileStatus {

    /**
     * Return the length of this file.
     *
     * @return the length of this file
     */
    long getLen();

    /**
     * Checks if this object represents a directory.
     *
     * @return <code>true</code> if this is a directory, <code>false</code> otherwise
     */
    boolean isDir();

    /**
     * Returns the corresponding Path to the FileStatus.
     *
     * @return the corresponding Path to the FileStatus
     */
    Path getPath();

    /**
     * Get the last modification time of the file.
     *
     * @return A long value representing the time the file was last modified, measured in
     *     milliseconds since the epoch (UTC January 1, 1970).
     */
    long getModificationTime();

    /**
     * Get the last access time of the file.
     *
     * @return A long value representing the time the file was last accessed, measured in
     *     milliseconds since the epoch (UTC January 1, 1970).
     */
    default long getAccessTime() {
        return 0;
    }

    /**
     * Returns the owner of this file.
     *
     * @return the owner of this file
     */
    @Nullable
    default String getOwner() {
        return null;
    }

    /**
     * Returns the generation of this file.
     *
     * @return the generation of this file
     */
    @Nullable
    default Integer getGeneration() {
        return null;
    }

    /**
     * Returns the content type of this file.
     *
     * @return the content type of this file
     */
    @Nullable
    default String getContentType() {
        return null;
    }

    /**
     * Returns the storage class of this file.
     *
     * @return the storage class of this file
     */
    @Nullable
    default String getStorageClass() {
        return null;
    }

    /**
     * Returns the MD5 hash of this file.
     *
     * @return the MD5 hash of this file
     */
    @Nullable
    default String getMd5Hash() {
        return null;
    }

    /**
     * Returns the last modification time of the file's metadata.
     *
     * @return A long value representing the time of the file's metadata was last modified, measured
     *     in milliseconds since the epoch (UTC January 1, 1970).
     */
    @Nullable
    default Long getMetadataModificationTime() {
        return null;
    }

    /**
     * Returns the metadata key-value pairs of the file.
     *
     * @return the metadata key-value pairs of the file
     */
    @Nullable
    default Map<String, String> getMetadata() {
        return null;
    }
}
