/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.mr;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.data.DeleteReadTests;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.util.StructLikeSet;
import org.junit.Assert;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TestInputFormatReaderDeletes extends DeleteReadTests {
  private final Configuration conf = new Configuration();
  private final HadoopTables tables = new HadoopTables(conf);
  private TestHelper helper;

  // parametrized variables
  private final String inputFormat;
  private final FileFormat fileFormat;

  @Parameterized.Parameters(name = "fileFormat = {0}, formatVersion = {1}, inputFormat={2}")
  public static Object[][] parameters() {
    return new Object[][] {
        { FileFormat.PARQUET, 2, "IcebergInputFormat" },
        { FileFormat.AVRO, 2, "IcebergInputFormat" },
        { FileFormat.ORC, 2, "IcebergInputFormat" },
        { FileFormat.PARQUET, 2, "MapredIcebergInputFormat" },
        { FileFormat.AVRO, 2, "MapredIcebergInputFormat" },
        { FileFormat.ORC, 2, "MapredIcebergInputFormat" },
        { FileFormat.PARQUET, 3, "IcebergInputFormat" },
        { FileFormat.PARQUET, 3, "MapredIcebergInputFormat" },
    };
  }

  @Before
  @Override
  public void writeTestDataFile() throws IOException {
    conf.set(CatalogUtil.ICEBERG_CATALOG_TYPE, Catalogs.LOCATION);
    super.writeTestDataFile();
  }

  public TestInputFormatReaderDeletes(FileFormat fileFormat, int formatVersion, String inputFormat) {
    super(formatVersion);
    this.fileFormat = fileFormat;
    this.inputFormat = inputFormat;
  }

  @Override
  protected Table createTable(String name, Schema schema, PartitionSpec spec) throws IOException {
    Table table;

    File location = temp.newFolder(inputFormat, fileFormat.name());
    Assert.assertTrue(location.delete());
    helper = new TestHelper(conf, tables, location.toString(), schema, spec, fileFormat, temp);
    table = helper.createTable();

    TableOperations ops = ((BaseTable) table).operations();
    TableMetadata meta = ops.current();
    ops.commit(meta, meta.upgradeToFormatVersion(formatVersion));

    return table;
  }

  @Override
  protected void dropTable(String name) {
    tables.dropTable(helper.table().location());
  }

  @Override
  public StructLikeSet rowSet(String name, Table table, String... columns) {
    InputFormatConfig.ConfigBuilder builder = new InputFormatConfig.ConfigBuilder(conf).readFrom(table.location());
    Schema projected = table.schema().select(columns);
    StructLikeSet set = StructLikeSet.create(projected.asStruct());

    set.addAll(TestIcebergInputFormats.TESTED_INPUT_FORMATS.stream()
        .filter(recordFactory -> recordFactory.name().equals(inputFormat))
        .map(recordFactory -> recordFactory.create(builder.project(projected).conf()).getRecords())
        .flatMap(List::stream)
        .map(record -> new InternalRecordWrapper(projected.asStruct()).wrap(record))
        .collect(Collectors.toList())
    );

    return set;
  }

  @Override
  protected boolean expectPruned() {
    return false;
  }
}
