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

package org.apache.hadoop.hive.ql.parse;

import static org.apache.hadoop.hive.ql.metadata.HiveUtils.unparseIdentifier;
import static org.apache.hadoop.hive.ql.metadata.VirtualColumn.PARTITION_SPEC_ID;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.google.common.collect.Maps;
import org.apache.hadoop.hive.common.HiveStatsUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.conf.VariableSubstitution;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.Context;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.QueryProperties;
import org.apache.hadoop.hive.ql.QueryState;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.plan.HiveOperation;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.ql.session.SessionState.LogHelper;
import org.apache.hadoop.hive.ql.stats.ColStatsProcessor.ColumnStatsField;
import org.apache.hadoop.hive.ql.stats.ColStatsProcessor.ColumnStatsType;
import org.apache.hadoop.hive.ql.stats.StatsUtils;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ColumnStatsSemanticAnalyzer.
 * Handles semantic analysis and rewrite for gathering column statistics both at the level of a
 * partition and a table. Note that table statistics are implemented in SemanticAnalyzer.
 *
 */
public class ColumnStatsSemanticAnalyzer extends SemanticAnalyzer {
  private static final Logger LOG = LoggerFactory
      .getLogger(ColumnStatsSemanticAnalyzer.class);
  private static final LogHelper CONSOLE = new LogHelper(LOG);

  private ASTNode originalTree;
  private ASTNode rewrittenTree;
  private String rewrittenQuery;

  private Context ctx;
  private boolean isRewritten;

  private boolean isTableLevel;
  private List<String> colNames;
  private List<String> colType;
  private Table tbl;

  public ColumnStatsSemanticAnalyzer(QueryState queryState) throws SemanticException {
    super(queryState);
  }

  private boolean shouldRewrite(ASTNode tree) {
    boolean rwt = false;
    if (tree.getChildCount() > 1) {
      ASTNode child0 = (ASTNode) tree.getChild(0);
      ASTNode child1;
      if (child0.getToken().getType() == HiveParser.TOK_TAB) {
        child0 = (ASTNode) child0.getChild(0);
        if (child0.getToken().getType() == HiveParser.TOK_TABNAME) {
          child1 = (ASTNode) tree.getChild(1);
          if (child1.getToken().getType() == HiveParser.KW_COLUMNS) {
            rwt = true;
          }
        }
      }
    }
    return rwt;
  }

  private List<String> getColumnName(ASTNode tree) throws SemanticException {

    switch (tree.getChildCount()) {
    case 2:
      return Utilities.getColumnNamesFromFieldSchema(tbl.getCols());
    case 3:
      int numCols = tree.getChild(2).getChildCount();
      List<String> colName = new ArrayList<>(numCols);
      for (int i = 0; i < numCols; i++) {
        colName.add(getUnescapedName((ASTNode) tree.getChild(2).getChild(i)));
      }
      return colName;
    default:
      throw new SemanticException("Internal error. Expected number of children of ASTNode to be"
          + " either 2 or 3. Found : " + tree.getChildCount());
    }
  }

  private void handlePartialPartitionSpec(Map<String, String> partSpec, ColumnStatsAutoGatherContext context) throws
    SemanticException {

    // If user has fully specified partition, validate that partition exists
    int partValsSpecified = 0;
    for (String partKey : partSpec.keySet()) {
      partValsSpecified += partSpec.get(partKey) == null ? 0 : 1;
    }
    try {
      // for static partition, it may not exist when HIVE_STATS_COL_AUTOGATHER is
      // set to true
      if (context == null && partValsSpecified > 0) {
        if ((partValsSpecified == tbl.getPartitionKeys().size())
            && (db.getPartition(tbl, partSpec, false, null, false) == null)) {
          throw new SemanticException(ErrorMsg.COLUMNSTATSCOLLECTOR_INVALID_PARTITION.getMsg()
              + " : " + partSpec);
        }
      }
    } catch (HiveException he) {
      throw new SemanticException(ErrorMsg.COLUMNSTATSCOLLECTOR_INVALID_PARTITION.getMsg() + " : "
          + partSpec);
    }

    // User might have only specified partial list of partition keys, in which case add other partition keys in partSpec
    List<String> partKeys = Utilities.getColumnNamesFromFieldSchema(tbl.getPartitionKeys());
    for (String partKey : partKeys) {
      if (!partSpec.containsKey(partKey)) {
        partSpec.put(partKey, null);
      }
    }

    // Check if user have erroneously specified non-existent partitioning columns
    for (String partKey : partSpec.keySet()) {
      if (!partKeys.contains(partKey)) {
        throw new SemanticException(ErrorMsg.COLUMNSTATSCOLLECTOR_INVALID_PART_KEY.getMsg() + " : " + partKey);
      }
    }
  }

  private static CharSequence genPartitionClause(Table tbl, List<TransformSpec> partTransformSpec, int specId, 
    Map<String, String> partSpec, HiveConf conf) {
    boolean predPresent = partSpec.values().stream().anyMatch(Objects::nonNull);
    
    StringBuilder whereClause = new StringBuilder(" where ").append(
      partSpec.entrySet().stream()
        .filter(part -> part.getValue() != null)
        .map(part -> unparseIdentifier(part.getKey(), conf) + " = " 
            + genPartValueString(getColTypeOf(tbl, part.getKey()), part.getValue()))
        .collect(Collectors.joining(" and "))
    );

    if (specId >= 0) {
      whereClause.append((predPresent) ? " and " : "")
          .append(unparseIdentifier(PARTITION_SPEC_ID.getName(), conf) + "=" + specId);
      predPresent = true;
    }

    StringBuilder groupByClause = new StringBuilder(" group by ").append((
      (partTransformSpec != null) ?
          partTransformSpec.stream().map(spec -> spec.toHiveExpr(conf)) :
          tbl.getPartColNames().stream().map(col -> unparseIdentifier(col, conf))
      )
      .collect(Collectors.joining(", "))
    );

    // attach the predicate and group by to the return clause
    return predPresent ? whereClause.append(groupByClause) : groupByClause;
  }



  private static String getColTypeOf(Table tbl, String partKey) {
    for (FieldSchema fs : tbl.hasNonNativePartitionSupport() ?
          tbl.getStorageHandler().getPartitionKeys(tbl) : tbl.getPartitionKeys()) {
      if (partKey.equalsIgnoreCase(fs.getName())) {
        return fs.getType().toLowerCase();
      }
    }
    throw new RuntimeException("Unknown partition key : " + partKey);
  }

  protected static List<String> getColumnTypes(Table tbl, List<String> colNames) {
    List<String> colTypes = new ArrayList<>();
    List<FieldSchema> cols = tbl.getCols();
    List<String> copyColNames = new ArrayList<>(colNames);

    for (String colName : copyColNames) {
      for (FieldSchema col : cols) {
        if (colName.equalsIgnoreCase(col.getName())) {
          String type = col.getType();
          TypeInfo typeInfo = TypeInfoUtils.getTypeInfoFromTypeString(type);
          if (typeInfo.getCategory() != ObjectInspector.Category.PRIMITIVE) {
            logTypeWarning(colName, type);
            colNames.remove(colName);
          } else {
            colTypes.add(type);
          }
        }
      }
    }

    return colTypes;
  }

  private String genRewrittenQuery(List<String> colNames, List<String> colTypes, HiveConf conf,
      List<TransformSpec> partTransformSpec, int specId, Map<String, String> partSpec, 
      boolean isPartitionStats) {
    String rewritten = genRewrittenQuery(tbl, colNames, colTypes, conf, partTransformSpec, specId, partSpec, 
        isPartitionStats, false);
    isRewritten = true;
    return rewritten;
  }

  /**
   * Generates a SQL statement that will compute the stats for all columns
   * included in the input table.
   */
  protected static String genRewrittenQuery(Table tbl,
      HiveConf conf, List<TransformSpec> partTransformSpec, Map<String, String> partSpec, 
      boolean isPartitionStats) {
    List<String> colNames = Utilities.getColumnNamesFromFieldSchema(tbl.getCols());
    List<String> colTypes = ColumnStatsSemanticAnalyzer.getColumnTypes(tbl, colNames);
    return ColumnStatsSemanticAnalyzer.genRewrittenQuery(
        tbl, colNames, colTypes, conf, partTransformSpec, -1, partSpec, isPartitionStats, true);
  }

  private static String genRewrittenQuery(Table tbl, List<String> colNames, List<String> colTypes,
      HiveConf conf, List<TransformSpec> partTransformSpec, int specId, Map<String, String> partSpec, 
      boolean isPartitionStats, boolean useTableValues) {
    StringBuilder rewrittenQueryBuilder = new StringBuilder("select ");

    StringBuilder columnNamesBuilder = new StringBuilder();
    StringBuilder columnDummyValuesBuilder = new StringBuilder();
    for (int i = 0; i < colNames.size(); i++) {
      if (i > 0) {
        rewrittenQueryBuilder.append(", ");
        columnNamesBuilder.append(", ");
        columnDummyValuesBuilder.append(", ");
      }

      final String columnName = unparseIdentifier(colNames.get(i), conf);
      final TypeInfo typeInfo = TypeInfoUtils.getTypeInfoFromTypeString(colTypes.get(i));
      
      try {
        genComputeStats(rewrittenQueryBuilder, conf, i, columnName, typeInfo);
      } catch (SemanticException e) {
        throw new RuntimeException(e);
      }

      columnNamesBuilder.append(columnName);

      columnDummyValuesBuilder.append(
          "cast(null as " + typeInfo + ")");
    }

    if (isPartitionStats) {
      if (partTransformSpec == null) {
        for (FieldSchema fs : tbl.getPartCols()) {
          String identifier = unparseIdentifier(fs.getName(), conf);
          rewrittenQueryBuilder.append(", ").append(identifier);
          columnNamesBuilder.append(", ").append(identifier);

          columnDummyValuesBuilder.append(", cast(null as ")
            .append(TypeInfoUtils.getTypeInfoFromTypeString(fs.getType()).toString())
            .append(")");
        }
      } else {
        rewrittenQueryBuilder.append(", ")
          .append(TransformSpec.toNamedStruct(partTransformSpec, conf));
      }
    }

    rewrittenQueryBuilder.append(" from ");
    if (useTableValues) {
      //TABLE(VALUES(cast(null as int),cast(null as string))) AS tablename(col1,col2)
      rewrittenQueryBuilder.append("table(values(")
        // Values
        .append(columnDummyValuesBuilder)
        .append(")) as ")
        .append(unparseIdentifier(tbl.getTableName() ,conf))
        .append("(")
        // Columns
        .append(columnNamesBuilder)
        .append(")");
    } else {
      rewrittenQueryBuilder.append(unparseIdentifier(tbl.getDbName(), conf))
        .append(".")
        .append(unparseIdentifier(tbl.getTableName(), conf));
      
      if (tbl.getMetaTable() != null) {
        rewrittenQueryBuilder.append(".")
          .append(unparseIdentifier(tbl.getMetaTable(), conf));
      }
    }

    // If partition level statistics is requested, add predicate and group by as needed to rewritten
    // query
    if (isPartitionStats) {
      rewrittenQueryBuilder.append(genPartitionClause(tbl, partTransformSpec, specId, partSpec, conf));
    }

    String rewrittenQuery = rewrittenQueryBuilder.toString();
    rewrittenQuery = new VariableSubstitution(
        () -> SessionState.get().getHiveVariables()).substitute(conf, rewrittenQuery);
    return rewrittenQuery;
  }

  private static void genComputeStats(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      int pos, String columnName, TypeInfo typeInfo) throws SemanticException {
    Preconditions.checkArgument(typeInfo.getCategory() == Category.PRIMITIVE);
    ColumnStatsType columnStatsType =
        ColumnStatsType.getColumnStatsType((PrimitiveTypeInfo) typeInfo);
    List<ColumnStatsField> columnStatsFields = columnStatsType.getColumnStats();
    columnStatsFields = ColumnStatsType.removeDisabledStatistics(conf, columnStatsFields);
    // The first column is always the type
    // The rest of columns will depend on the type itself
    for (int i = 0; i < columnStatsFields.size(); i++) {
      ColumnStatsField columnStatsField = columnStatsFields.get(i);
      if (i > 0) {
        rewrittenQueryBuilder.append(", ");
      }
      appendStatsField(rewrittenQueryBuilder, conf, columnStatsField, columnStatsType,
          columnName, pos);
    }
  }

  private static void appendStatsField(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      ColumnStatsField columnStatsField, ColumnStatsType columnStatsType,
      String columnName, int pos) throws SemanticException {
    switch (columnStatsField) {
    case COLUMN_STATS_TYPE:
      appendColumnType(rewrittenQueryBuilder, conf, columnStatsType, pos);
      break;
    case COUNT_TRUES:
      appendCountTrues(rewrittenQueryBuilder, conf, columnName, pos);
      break;
    case COUNT_FALSES:
      appendCountFalses(rewrittenQueryBuilder, conf, columnName, pos);
      break;
    case COUNT_NULLS:
      appendCountNulls(rewrittenQueryBuilder, conf, columnName, pos);
      break;
    case MIN:
      appendMin(rewrittenQueryBuilder, conf, columnStatsType, columnName, pos);
      break;
    case MAX:
      appendMax(rewrittenQueryBuilder, conf, columnStatsType, columnName, pos);
      break;
    case NDV:
      appendNDV(rewrittenQueryBuilder, conf, columnName, pos);
      break;
    case BITVECTOR:
      appendBitVector(rewrittenQueryBuilder, conf, columnName, pos);
      break;
    case KLL_SKETCH:
      appendKllSketch(rewrittenQueryBuilder, conf, columnName, columnStatsType, pos);
      break;
    case MAX_LENGTH:
      appendMaxLength(rewrittenQueryBuilder, conf, columnName, pos);
      break;
    case AVG_LENGTH:
      appendAvgLength(rewrittenQueryBuilder, conf, columnName, pos);
      break;
    default:
      throw new SemanticException("Not supported field " + columnStatsField);
    }
  }

  private static void appendColumnType(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      ColumnStatsType columnStatsType, int pos) {
    rewrittenQueryBuilder.append("'")
        .append(columnStatsType.toString())
        .append("' AS ")
        .append(unparseIdentifier(ColumnStatsField.COLUMN_STATS_TYPE.getFieldName() + pos, conf));
  }

  private static void appendMin(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      ColumnStatsType columnStatsType, String columnName, int pos) {
    switch (columnStatsType) {
    case LONG:
      rewrittenQueryBuilder.append("CAST(min(")
          .append(columnName)
          .append(") AS bigint) AS ");
      break;
    case DOUBLE:
      rewrittenQueryBuilder.append("CAST(min(")
          .append(columnName)
          .append(") AS double) AS ");
      break;
    default:
      rewrittenQueryBuilder.append("min(")
          .append(columnName)
          .append(") AS ");
      break;
    }
    rewrittenQueryBuilder.append(
        unparseIdentifier(ColumnStatsField.MIN.getFieldName() + pos, conf));
  }

  private static void appendMax(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      ColumnStatsType columnStatsType, String columnName, int pos) {
    switch (columnStatsType) {
    case LONG:
      rewrittenQueryBuilder.append("CAST(max(")
          .append(columnName)
          .append(") AS bigint) AS ");
      break;
    case DOUBLE:
      rewrittenQueryBuilder.append("CAST(max(")
          .append(columnName)
          .append(") AS double) AS ");
      break;
    default:
      rewrittenQueryBuilder.append("max(")
          .append(columnName)
          .append(") AS ");
      break;
    }
    rewrittenQueryBuilder.append(
        unparseIdentifier(ColumnStatsField.MAX.getFieldName() + pos, conf));
  }

  private static void appendMaxLength(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      String columnName, int pos) {
    rewrittenQueryBuilder.append("CAST(COALESCE(max(LENGTH(")
        .append(columnName)
        .append(")), 0) AS bigint) AS ")
        .append(unparseIdentifier(ColumnStatsField.MAX_LENGTH.getFieldName() + pos, conf));
  }

  private static void appendAvgLength(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      String columnName, int pos) {
    rewrittenQueryBuilder.append("CAST(COALESCE(avg(COALESCE(LENGTH(")
        .append(columnName)
        .append("), 0)), 0) AS double) AS ")
        .append(unparseIdentifier(ColumnStatsField.AVG_LENGTH.getFieldName() + pos, conf));
  }

  private static void appendCountNulls(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      String columnName, int pos) {
    rewrittenQueryBuilder.append("CAST(count(1) - count(")
        .append(columnName)
        .append(") AS bigint) AS ")
        .append(unparseIdentifier(ColumnStatsField.COUNT_NULLS.getFieldName() + pos, conf));
  }

  private static void appendNDV(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      String columnName, int pos) throws SemanticException {
    rewrittenQueryBuilder.append("COALESCE(NDV_COMPUTE_BIT_VECTOR(");
    appendBitVector(rewrittenQueryBuilder, conf, columnName);
    rewrittenQueryBuilder.append("), 0) AS ")
        .append(unparseIdentifier(ColumnStatsField.NDV.getFieldName() + pos, conf));
  }

  private static void appendBitVector(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      String columnName, int pos) throws SemanticException {
    appendBitVector(rewrittenQueryBuilder, conf, columnName);
    rewrittenQueryBuilder.append(" AS ")
        .append(unparseIdentifier(ColumnStatsField.BITVECTOR.getFieldName() + pos, conf));
  }

  private static void appendKllSketch(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      String columnName, ColumnStatsType columnStatsType, int pos) throws SemanticException {
    appendKllSketch(rewrittenQueryBuilder, conf, columnName, columnStatsType);
    rewrittenQueryBuilder.append(" AS ")
        .append(unparseIdentifier(ColumnStatsField.KLL_SKETCH.getFieldName() + pos, conf));
  }

  private static void appendBitVector(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      String columnName) throws SemanticException {
    String func = HiveConf.getVar(conf, HiveConf.ConfVars.HIVE_STATS_NDV_ALGO).toLowerCase();
    if ("hll".equals(func)) {
      rewrittenQueryBuilder
          .append("compute_bit_vector_hll(")
          .append(columnName)
          .append(")");
    } else if ("fm".equals(func)) {
      int numBitVectors;
      try {
        numBitVectors = HiveStatsUtils.getNumBitVectorsForNDVEstimation(conf);
      } catch (Exception e) {
        throw new SemanticException(e.getMessage());
      }
      rewrittenQueryBuilder.append("compute_bit_vector_fm(")
          .append(columnName)
          .append(", ")
          .append(numBitVectors)
          .append(")");
    } else {
      throw new UDFArgumentException("available ndv computation options are hll and fm. Got: " + func);
    }
  }

  private static void appendKllSketch(StringBuilder rewrittenQueryBuilder, HiveConf conf, String columnName,
      ColumnStatsType columnStatsType) throws SemanticException {
    int k;
    try {
      k = HiveStatsUtils.getKParamForKllSketch(conf);
    } catch (Exception e) {
      throw new SemanticException(e.getMessage());
    }

    boolean isDateFamily = columnStatsType.equals(ColumnStatsType.DATE) ||
        columnStatsType.equals(ColumnStatsType.TIMESTAMP);

    if (isDateFamily) {
      rewrittenQueryBuilder.append("ds_kll_sketch(cast(unix_timestamp(")
          .append(columnName)
          .append(") as float)");
    } else {
      // add cast to float to make sure it works for other numeric types
      rewrittenQueryBuilder.append("ds_kll_sketch(cast(")
          .append(columnName)
          .append(" as float)");
    }
    rewrittenQueryBuilder.append(", ")
        .append(k)
        .append(")");
  }

  private static void appendCountTrues(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      String columnName, int pos) {
    rewrittenQueryBuilder.append("CAST(count(CASE WHEN ")
        .append(columnName)
        .append(" IS TRUE THEN 1 ELSE null END) AS bigint) AS ")
        .append(unparseIdentifier(ColumnStatsField.COUNT_TRUES.getFieldName() + pos, conf));
  }

  private static void appendCountFalses(StringBuilder rewrittenQueryBuilder, HiveConf conf,
      String columnName, int pos) {
    rewrittenQueryBuilder.append("CAST(count(CASE WHEN ")
        .append(columnName)
        .append(" IS FALSE THEN 1 ELSE null END) AS bigint) AS ")
        .append(unparseIdentifier(ColumnStatsField.COUNT_FALSES.getFieldName() + pos, conf));
  }

  private ASTNode genRewrittenTree(String rewrittenQuery) throws SemanticException {
    // Parse the rewritten query string
    ctx = new Context(conf);
    ctx.setCmd(rewrittenQuery);
    ctx.setHDFSCleanup(true);

    try {
      return ParseUtils.parse(rewrittenQuery, ctx);
    } catch (ParseException e) {
      throw new SemanticException(ErrorMsg.COLUMNSTATSCOLLECTOR_PARSE_ERROR.getMsg());
    }
  }

  // fail early if the columns specified for column statistics are not valid
  private void validateSpecifiedColumnNames(List<String> specifiedCols)
      throws SemanticException {
    List<String> tableCols = Utilities.getColumnNamesFromFieldSchema(tbl.getCols());
    for (String sc : specifiedCols) {
      if (!tableCols.contains(sc.toLowerCase())) {
        String msg = "'" + sc + "' (possible columns are " + tableCols + ")";
        throw new SemanticException(ErrorMsg.INVALID_COLUMN.getMsg(msg));
      }
    }
  }

  private void checkForPartitionColumns(List<String> specifiedCols, List<String> partCols)
      throws SemanticException {
    // Raise error if user has specified partition column for stats
    for (String pc : partCols) {
      for (String sc : specifiedCols) {
        if (pc.equalsIgnoreCase(sc)) {
          throw new SemanticException(ErrorMsg.COLUMNSTATSCOLLECTOR_INVALID_COLUMN.getMsg()
              + " [Try removing column '" + sc + "' from column list]");
        }
      }
    }
  }

  private static void logTypeWarning(String colName, String colType) {
    String warning = "Only primitive type arguments are accepted but " + colType
        + " is passed for " + colName + ".";
    warning = "WARNING: " + warning;
    CONSOLE.printInfo(warning);
  }

  @Override
  public void analyze(ASTNode ast, Context origCtx) throws SemanticException {
    QB qb;
    QBParseInfo qbp;

    // initialize QB
    init(true);

    // check if it is no scan. grammar prevents coexit noscan/columns
    super.processNoScanCommand(ast);
    /* Rewrite only analyze table <> column <> compute statistics; Don't rewrite analyze table
     * command - table stats are collected by the table scan operator and is not rewritten to
     * an aggregation.
     */
    if (shouldRewrite(ast)) {
      tbl = AnalyzeCommandUtils.getTable(ast, this);
      colNames = getColumnName(ast);
      // Save away the original AST
      originalTree = ast;
      boolean isPartitionStats = AnalyzeCommandUtils.isPartitionLevelStats(ast) 
          || StatsUtils.isPartitionStats(tbl, conf);
      
      Map<Integer, List<TransformSpec>> partTransformSpecs = Collections.singletonMap(-1, null);
      Map<String, String> partSpec = (isPartitionStats) ?
          AnalyzeCommandUtils.getPartKeyValuePairsFromAST(tbl, ast, conf) : null;
      checkForPartitionColumns(
          colNames, Utilities.getColumnNamesFromFieldSchema(tbl.getPartitionKeys()));
      validateSpecifiedColumnNames(colNames);

      if (isPartitionStats) {
        handlePartialPartitionSpec(partSpec, null);
        if (tbl.hasNonNativePartitionSupport()) {
          partTransformSpecs = tbl.getStorageHandler().getPartitionTransformSpecs(tbl);
        }
      }
      colType = getColumnTypes(tbl, colNames);
      isTableLevel = !isPartitionStats;

      rewrittenQuery = String.join(" union all ",
        Maps.transformEntries(partTransformSpecs, (specId, partTransformSpec) ->
            genRewrittenQuery(colNames, colType, conf, partTransformSpec, specId, partSpec, isPartitionStats))
          .values());
      
      rewrittenTree = genRewrittenTree(rewrittenQuery);
    } else {
      // Not an analyze table column compute statistics statement - don't do any rewrites
      originalTree = rewrittenTree = ast;
      rewrittenQuery = null;
      isRewritten = false;
    }

    // Setup the necessary metadata if originating from analyze rewrite
    if (isRewritten) {
      qb = getQB();
      qb.setAnalyzeRewrite(true);
      qbp = qb.getParseInfo();
      analyzeRewrite = new AnalyzeRewriteContext();
      analyzeRewrite.setTableName(tbl.getFullyQualifiedName());
      analyzeRewrite.setTblLvl(isTableLevel);
      analyzeRewrite.setColName(colNames);
      analyzeRewrite.setColType(colType);
      qbp.setAnalyzeRewrite(analyzeRewrite);
      origCtx.addSubContext(ctx);
      initCtx(ctx);
      ctx.setExplainConfig(origCtx.getExplainConfig());
      LOG.info("Invoking analyze on rewritten query");
      analyzeInternal(rewrittenTree);
      // After analyzeInternal() Hiveop get set as Query
      // since we are passing in AST for select query, so reset it.
      this.queryState.setCommandType(HiveOperation.ANALYZE_TABLE);
    } else {
      initCtx(origCtx);
      LOG.info("Invoking analyze on original query");
      analyzeInternal(originalTree);
    }
  }

  /**
   * @param ast
   *          is the original analyze ast
   * @param context
   *          the column stats auto gather context
   * @return the rewritten AST
   */
  public ASTNode rewriteAST(ASTNode ast, ColumnStatsAutoGatherContext context)
      throws SemanticException {
    // Save away the original AST
    originalTree = ast;

    tbl = AnalyzeCommandUtils.getTable(ast, this);

    colNames = getColumnName(ast);
    boolean isPartitionStats = AnalyzeCommandUtils.isPartitionLevelStats(ast)
        || StatsUtils.isPartitionStats(tbl, conf);
    
    List<TransformSpec> partTransformSpec = null;
    Map<String, String> partSpec = null;
    checkForPartitionColumns(colNames,
        Utilities.getColumnNamesFromFieldSchema(tbl.getPartitionKeys()));
    validateSpecifiedColumnNames(colNames);

    if (isPartitionStats) {
      partSpec = AnalyzeCommandUtils.getPartKeyValuePairsFromAST(tbl, ast, conf);
      handlePartialPartitionSpec(partSpec, context);
      if (tbl.hasNonNativePartitionSupport()) {
        partTransformSpec = tbl.getStorageHandler().getPartitionTransformSpec(tbl);
      }
    }
    colType = getColumnTypes(tbl, colNames);
    isTableLevel = !isPartitionStats;

    rewrittenQuery = genRewrittenQuery(colNames, colType, conf, partTransformSpec, -1, 
        partSpec, isPartitionStats);
    rewrittenTree = genRewrittenTree(rewrittenQuery);

    return rewrittenTree;
  }

  AnalyzeRewriteContext getAnalyzeRewriteContext() {
    AnalyzeRewriteContext analyzeRewrite = new AnalyzeRewriteContext();
    analyzeRewrite.setTableName(tbl.getFullyQualifiedName());
    analyzeRewrite.setTblLvl(isTableLevel);
    analyzeRewrite.setColName(colNames);
    analyzeRewrite.setColType(colType);
    return analyzeRewrite;
  }

  static AnalyzeRewriteContext genAnalyzeRewriteContext(HiveConf conf, Table tbl) {
    AnalyzeRewriteContext analyzeRewrite = new AnalyzeRewriteContext();
    analyzeRewrite.setTableName(tbl.getFullyQualifiedName());
    analyzeRewrite.setTblLvl(!(conf.getBoolVar(ConfVars.HIVE_STATS_COLLECT_PART_LEVEL_STATS) && tbl.isPartitioned()));
    List<String> colNames = Utilities.getColumnNamesFromFieldSchema(tbl.getCols());
    List<String> colTypes = getColumnTypes(tbl, colNames);
    analyzeRewrite.setColName(colNames);
    analyzeRewrite.setColType(colTypes);
    return analyzeRewrite;
  }

  @Override
  public void setQueryType(ASTNode tree) {
    queryProperties.setQueryType(QueryProperties.QueryType.STATS);
  }
}
