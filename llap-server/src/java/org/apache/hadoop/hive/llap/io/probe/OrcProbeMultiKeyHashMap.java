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

package org.apache.hadoop.hive.llap.io.probe;

import org.apache.hadoop.hive.llap.io.api.impl.LlapIoImpl;
import org.apache.hadoop.hive.ql.exec.JoinUtil;
import org.apache.hadoop.hive.ql.exec.vector.ColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorSerializeRow;
import org.apache.hadoop.hive.ql.exec.vector.mapjoin.hashtable.VectorMapJoinBytesHashMap;
import org.apache.hadoop.hive.ql.exec.vector.mapjoin.hashtable.VectorMapJoinHashMapResult;
import org.apache.hadoop.hive.ql.exec.vector.mapjoin.hashtable.VectorMapJoinHashTable;
import org.apache.hadoop.hive.ql.io.filter.MutableFilterContext;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.VectorMapJoinInfo;
import org.apache.hadoop.hive.serde2.ByteStream.Output;
import org.apache.hadoop.hive.serde2.binarysortable.fast.BinarySortableSerializeWrite;
import org.apache.hadoop.hive.serde2.fast.SerializeWrite;

import java.io.IOException;

public class OrcProbeMultiKeyHashMap extends OrcProbeHashTable {

  private VectorMapJoinBytesHashMap probeHashMultiMap;
  private VectorMapJoinHashMapResult hashMultiMapResult;
  // MultiKey vars used for binary sortable conversion
  private SerializeWrite multiKeySerializeWrite;
  private VectorSerializeRow multiKeyVectorSerializeRow;
  private Output currKeyOutput;
  private Output saveKeyOutput;

  public OrcProbeMultiKeyHashMap(VectorMapJoinHashTable vTable, VectorMapJoinInfo vInfo) throws HiveException {
    super(vTable, vInfo);
    this.probeHashMultiMap = (VectorMapJoinBytesHashMap) probeDecodeMapJoinTable;
    this.hashMultiMapResult =  probeHashMultiMap.createHashMapResult();
    this.multiKeySerializeWrite = new BinarySortableSerializeWrite(vInfo.getBigTableKeyColumnMap().length);
    this.multiKeyVectorSerializeRow = new VectorSerializeRow(multiKeySerializeWrite);
    this.currKeyOutput = new Output();
    this.saveKeyOutput = new Output();
  }

  @Override
  public void init() throws HiveException {
    multiKeyVectorSerializeRow.init(probeDecodeMapJoinInfo.getBigTableKeyTypeInfos(), probeDecodeMapJoinInfo.getBigTableKeyColumnMap());
  }

  @Override
  public void filterColumnVector(ColumnVector cv, MutableFilterContext cntx, int batchSize) {
    int[] selected = null;
    int newSize = 0;
    boolean selectedInUse = false;
    ColumnVector probeCol = cv;
    try {
      if (probeCol.isRepeating) {
        // Repeating values case
        if (!probeCol.noNulls && probeCol.isNull[0]) {
          // If repeating and NO match, the entire batch is filtered out.
          selectedInUse = true; // and newSize remains 0
        } else {
          multiKeyVectorSerializeRow.setOutput(currKeyOutput);
          multiKeySerializeWrite.reset();
          multiKeyVectorSerializeRow.serializePrimitiveWrite(probeCol, multiKeyVectorSerializeRow.getFields()[0], 0);
          if (probeHashMultiMap.lookup(currKeyOutput.getData(), 0, currKeyOutput.getLength(), hashMultiMapResult)
              == JoinUtil.JoinResult.MATCH) {
            // If repeating and match, next CVs of batch are read FULLY
            // DO NOT set selected here as next CVs are not necessarily repeating!
            newSize = batchSize;
          } else {
            // If repeating and NO match, the entire batch is filtered out.
            selectedInUse = true; // and newSize remains 0
          }
        }
      } else {
        // Non-repeating values case
        selected = cntx.updateSelected(batchSize);
        // We optimize performance by only looking up the first key in a series of equal keys.
        boolean haveSaveKey = false;
        boolean saveKeyMatch = false;
        Output temp;
        for (int row = 0; row < batchSize; ++row) {
          boolean isNull =!probeCol.noNulls && probeCol.isNull[row];
          // Equal key series checking.
          if (isNull || !haveSaveKey || !saveKeyOutput.arraysEquals(currKeyOutput)) {
            // New key.
            if (isNull) {
              haveSaveKey = false;
              saveKeyMatch = false;
            } else {
              // MultiKey to binary sortable
              multiKeyVectorSerializeRow.setOutput(currKeyOutput);
              multiKeySerializeWrite.reset();
              multiKeyVectorSerializeRow.serializePrimitiveWrite(probeCol, multiKeyVectorSerializeRow.getFields()[0], row);
              // swap
              temp = saveKeyOutput;
              saveKeyOutput = currKeyOutput;
              currKeyOutput = temp;
              haveSaveKey = true;
              saveKeyMatch = probeHashMultiMap.lookup(saveKeyOutput.getData(), 0, saveKeyOutput.getLength(),
                  hashMultiMapResult) == JoinUtil.JoinResult.MATCH;
              // Pass Valid key
              if (saveKeyMatch) {
                selected[newSize++] = row;
              }
            }
          } else {
            // Series of equal keys.
            if (saveKeyMatch) {
              selected[newSize++] = row;
            }
          }
        }
        selectedInUse = true;
      }
      cntx.setFilterContext(selectedInUse, selected, newSize);
      LlapIoImpl.LOG.debug("ProbeDecode MultiKey Matched: {} selectedInUse {} batchSize {}", newSize, selectedInUse, batchSize);
    } catch (IOException e) {
      LlapIoImpl.LOG.error("ProbeDecode MultiKey Filter failed: {}", e);
      e.printStackTrace();
    }
  }
}