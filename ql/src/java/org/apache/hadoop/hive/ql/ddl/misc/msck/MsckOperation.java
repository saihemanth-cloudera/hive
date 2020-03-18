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

package org.apache.hadoop.hive.ql.ddl.misc.msck;

import org.apache.hadoop.hive.ql.ddl.DDLOperationContext;
import org.apache.hadoop.hive.ql.exec.Utilities;

import java.io.IOException;

import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.Msck;
import org.apache.hadoop.hive.metastore.MsckInfo;
import org.apache.hadoop.hive.metastore.PartitionManagementTask;
import org.apache.hadoop.hive.metastore.Warehouse;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.ql.ddl.DDLOperation;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.thrift.TException;

/**
 * Operation process of metastore check.
 *
 * MetastoreCheck, see if the data in the metastore matches what is on the dfs. Current version checks for tables
 * and partitions that are either missing on disk on in the metastore.
 */
public class MsckOperation extends DDLOperation<MsckDesc> {
  public MsckOperation(DDLOperationContext context, MsckDesc desc) {
    super(context, desc);
  }

  @Override
  public int execute() throws HiveException, IOException, TException {
    try {
      Msck msck = new Msck(false, false);
      msck.init(context.getDb().getConf());

      String[] names = Utilities.getDbTableName(desc.getTableName());

      long partitionExpirySeconds = -1L;
      try (HiveMetaStoreClient msc = new HiveMetaStoreClient(context.getConf())) {
        Table table = msc.getTable("hive", names[0], names[1]);
        String qualifiedTableName = Warehouse.getCatalogQualifiedTableName(table);
        boolean msckEnablePartitionRetention = MetastoreConf.getBoolVar(context.getConf(),
            MetastoreConf.ConfVars.MSCK_REPAIR_ENABLE_PARTITION_RETENTION);
        if (msckEnablePartitionRetention) {
          partitionExpirySeconds = PartitionManagementTask.getRetentionPeriodInSeconds(table);
          LOG.info("{} - Retention period ({}s) for partition is enabled for MSCK REPAIR..", qualifiedTableName,
              partitionExpirySeconds);
        }
      }

      // SessionState.get().getCurrentCatalog() does not exists, so using "hive" for now
      MsckInfo msckInfo = new MsckInfo("hive", names[0], names[1], desc.getPartitionsSpecs(), desc.getResFile(),
          desc.isRepairPartitions(), desc.isAddPartitions(), desc.isDropPartitions(), partitionExpirySeconds);
      return msck.repair(msckInfo);
    } catch (MetaException e) {
      LOG.error("Unable to create msck instance.", e);
      return 1;
    } catch (SemanticException e) {
      LOG.error("Msck failed.", e);
      return 1;
    }
  }
}