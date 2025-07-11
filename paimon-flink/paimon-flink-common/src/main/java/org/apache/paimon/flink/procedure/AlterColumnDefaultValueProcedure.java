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

package org.apache.paimon.flink.procedure;

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.schema.SchemaChange;
import org.apache.paimon.utils.StringUtils;

import org.apache.paimon.shade.guava30.com.google.common.collect.ImmutableList;

import org.apache.flink.table.annotation.ArgumentHint;
import org.apache.flink.table.annotation.DataTypeHint;
import org.apache.flink.table.annotation.ProcedureHint;
import org.apache.flink.table.procedure.ProcedureContext;

/**
 * Alter column default value procedure. Usage:
 *
 * <pre><code>
 *  CALL sys.alter_column_default_value('table_identifier', 'column', 'default_value')
 * </code></pre>
 */
public class AlterColumnDefaultValueProcedure extends ProcedureBase {

    @Override
    public String identifier() {
        return "alter_column_default_value";
    }

    @ProcedureHint(
            argument = {
                @ArgumentHint(name = "table", type = @DataTypeHint("STRING")),
                @ArgumentHint(name = "column", type = @DataTypeHint("STRING")),
                @ArgumentHint(name = "default_value", type = @DataTypeHint("STRING"))
            })
    public String[] call(
            ProcedureContext procedureContext, String table, String column, String defaultValue)
            throws Catalog.ColumnAlreadyExistException, Catalog.TableNotExistException,
                    Catalog.ColumnNotExistException {
        Identifier identifier = Identifier.fromString(table);
        String[] fieldNames = StringUtils.split(column, ".");
        SchemaChange schemaChange = SchemaChange.updateColumnDefaultValue(fieldNames, defaultValue);
        catalog.alterTable(identifier, ImmutableList.of(schemaChange), false);
        return new String[] {"Success"};
    }
}
