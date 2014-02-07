/*
 * Copyright 2014 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end.index;

import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.COLUMN_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_CAT_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_NAME_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_SCHEM_NAME;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TYPE_SCHEMA;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TYPE_TABLE;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;

import com.google.common.collect.Lists;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.ColumnModifier;
import org.apache.phoenix.schema.ColumnNotFoundException;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PColumnFamily;
import org.apache.phoenix.schema.PDataType;
import org.apache.phoenix.schema.PRow;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.RowKeySchema;
import org.apache.phoenix.util.IndexUtil;
import org.apache.phoenix.util.MetaDataUtil;
import org.apache.phoenix.util.SchemaUtil;

public class IndexTestUtil {

    // the normal db metadata interface is insufficient for all fields needed for an
    // index table test.
    private static final String SELECT_DATA_INDEX_ROW = "SELECT " + TABLE_CAT_NAME
            + " FROM "
            + TYPE_SCHEMA + ".\"" + TYPE_TABLE
            + "\" WHERE "
            + TABLE_SCHEM_NAME + "=? AND " + TABLE_NAME_NAME + "=? AND " + COLUMN_NAME + " IS NULL AND " + TABLE_CAT_NAME + "=?";
    
    public static ResultSet readDataTableIndexRow(Connection conn, String schemaName, String tableName, String indexName) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement(SELECT_DATA_INDEX_ROW);
        stmt.setString(1, schemaName);
        stmt.setString(2, tableName);
        stmt.setString(3, indexName);
        return stmt.executeQuery();
    }
    
    
    private static void coerceDataValueToIndexValue(PColumn dataColumn, PColumn indexColumn, ImmutableBytesWritable ptr) {
        PDataType dataType = dataColumn.getDataType();
        // TODO: push to RowKeySchema? 
        ColumnModifier dataModifier = dataColumn.getColumnModifier();
        PDataType indexType = indexColumn.getDataType();
        ColumnModifier indexModifier = indexColumn.getColumnModifier();
        // We know ordinal position will match pk position, because you cannot
        // alter an index table.
        indexType.coerceBytes(ptr, dataType, dataModifier, indexModifier);
    }
    
    public static List<Mutation> generateIndexData(PTable index, PTable table, List<Mutation> dataMutations, ImmutableBytesWritable ptr) throws SQLException {
        List<Mutation> indexMutations = Lists.newArrayListWithExpectedSize(dataMutations.size());
        for (Mutation dataMutation : dataMutations) {
            indexMutations.addAll(generateIndexData(index, table, dataMutation, ptr));
        }
        return indexMutations;
    }

    public static List<Mutation> generateIndexData(PTable indexTable, PTable dataTable, Mutation dataMutation, ImmutableBytesWritable ptr) throws SQLException {
        byte[] dataRowKey = dataMutation.getRow();
        RowKeySchema dataRowKeySchema = dataTable.getRowKeySchema();
        List<PColumn> dataPKColumns = dataTable.getPKColumns();
        int i = 0;
        int indexOffset = 0;
        Boolean hasValue;
        // Skip salt column
        int maxOffset = dataRowKey.length;
        dataRowKeySchema.iterator(dataRowKey, ptr, dataTable.getBucketNum() == null ? i : ++i);
        List<PColumn> indexPKColumns = indexTable.getPKColumns();
        List<PColumn> indexColumns = indexTable.getColumns();
        int nIndexColumns = indexPKColumns.size();
        int maxIndexValues = indexColumns.size() - nIndexColumns - indexOffset;
        BitSet indexValuesSet = new BitSet(maxIndexValues);
        byte[][] indexValues = new byte[indexColumns.size() - indexOffset][];
        while ((hasValue = dataRowKeySchema.next(ptr, i, maxOffset)) != null) {
            if (hasValue) {
                PColumn dataColumn = dataPKColumns.get(i);
                PColumn indexColumn = indexTable.getColumn(IndexUtil.getIndexColumnName(dataColumn));
                coerceDataValueToIndexValue(dataColumn, indexColumn, ptr);
                indexValues[indexColumn.getPosition()-indexOffset] = ptr.copyBytes();
            }
            i++;
        }
        PRow row;
        long ts = MetaDataUtil.getClientTimeStamp(dataMutation);
        if (dataMutation instanceof Delete && dataMutation.getFamilyMap().values().isEmpty()) {
            indexTable.newKey(ptr, indexValues);
            row = indexTable.newRow(ts, ptr);
            row.delete();
        } else {
            // If no column families in table, then nothing to look for 
            if (!dataTable.getColumnFamilies().isEmpty()) {
                for (Map.Entry<byte[],List<KeyValue>> entry : dataMutation.getFamilyMap().entrySet()) {
                    PColumnFamily family = dataTable.getColumnFamily(entry.getKey());
                    for (KeyValue kv : entry.getValue()) {
                        byte[] cq = kv.getQualifier();
                        if (Bytes.compareTo(QueryConstants.EMPTY_COLUMN_BYTES, cq) != 0) {
                            try {
                                PColumn dataColumn = family.getColumn(cq);
                                PColumn indexColumn = indexTable.getColumn(IndexUtil.getIndexColumnName(family.getName().getString(), dataColumn.getName().getString()));
                                ptr.set(kv.getBuffer(),kv.getValueOffset(),kv.getValueLength());
                                coerceDataValueToIndexValue(dataColumn, indexColumn, ptr);
                                indexValues[indexPKColumns.indexOf(indexColumn)-indexOffset] = ptr.copyBytes();
                                if (!SchemaUtil.isPKColumn(indexColumn)) {
                                    indexValuesSet.set(indexColumn.getPosition()-nIndexColumns-indexOffset);
                                }
                            } catch (ColumnNotFoundException e) {
                                // Ignore as this means that the data column isn't in the index
                            }
                        }
                    }
                }
            }
            indexTable.newKey(ptr, indexValues);
            row = indexTable.newRow(ts, ptr);
            int pos = 0;
            while ((pos = indexValuesSet.nextSetBit(pos)) >= 0) {
                int index = nIndexColumns + indexOffset + pos++;
                PColumn indexColumn = indexColumns.get(index);
                row.setValue(indexColumn, indexValues[index]);
            }
        }
        return row.toRowMutations();
    }

}
