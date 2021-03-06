/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.datamap.lucene;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.carbondata.common.annotations.InterfaceAudience;
import org.apache.carbondata.common.exceptions.sql.MalformedDataMapCommandException;
import org.apache.carbondata.common.logging.LogService;
import org.apache.carbondata.common.logging.LogServiceFactory;
import org.apache.carbondata.core.datamap.DataMapDistributable;
import org.apache.carbondata.core.datamap.DataMapMeta;
import org.apache.carbondata.core.datamap.DataMapStoreManager;
import org.apache.carbondata.core.datamap.Segment;
import org.apache.carbondata.core.datamap.TableDataMap;
import org.apache.carbondata.core.datamap.dev.DataMap;
import org.apache.carbondata.core.datamap.dev.DataMapFactory;
import org.apache.carbondata.core.datamap.dev.DataMapWriter;
import org.apache.carbondata.core.datastore.filesystem.CarbonFile;
import org.apache.carbondata.core.datastore.filesystem.CarbonFileFilter;
import org.apache.carbondata.core.datastore.impl.FileFactory;
import org.apache.carbondata.core.metadata.AbsoluteTableIdentifier;
import org.apache.carbondata.core.metadata.datatype.DataTypes;
import org.apache.carbondata.core.metadata.encoder.Encoding;
import org.apache.carbondata.core.metadata.schema.table.CarbonTable;
import org.apache.carbondata.core.metadata.schema.table.DataMapSchema;
import org.apache.carbondata.core.metadata.schema.table.column.CarbonColumn;
import org.apache.carbondata.core.scan.filter.intf.ExpressionType;
import org.apache.carbondata.core.statusmanager.SegmentStatusManager;
import org.apache.carbondata.core.util.CarbonUtil;
import org.apache.carbondata.core.util.path.CarbonTablePath;
import org.apache.carbondata.events.Event;

import org.apache.commons.lang.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

/**
 * Base implementation for CG and FG lucene DataMapFactory.
 */
@InterfaceAudience.Internal
abstract class LuceneDataMapFactoryBase<T extends DataMap> implements DataMapFactory<T> {

  static final String TEXT_COLUMNS = "text_columns";

  /**
   * Logger
   */
  final LogService LOGGER = LogServiceFactory.getLogService(this.getClass().getName());

  /**
   * table's index columns
   */
  DataMapMeta dataMapMeta = null;

  /**
   * analyzer for lucene
   */
  Analyzer analyzer = null;

  /**
   * index name
   */
  String dataMapName = null;

  /**
   * table identifier
   */
  AbsoluteTableIdentifier tableIdentifier = null;

  /**
   * indexed carbon columns for lucene
   */
  List<String> indexedCarbonColumns = null;

  CarbonTable carbonTable = null;


  @Override
  public void init(CarbonTable carbonTable, DataMapSchema dataMapSchema)
      throws IOException, MalformedDataMapCommandException {
    Objects.requireNonNull(carbonTable.getAbsoluteTableIdentifier());
    Objects.requireNonNull(dataMapSchema);

    this.carbonTable = carbonTable;
    this.tableIdentifier = carbonTable.getAbsoluteTableIdentifier();
    this.dataMapName = dataMapSchema.getDataMapName();

    // validate DataMapSchema and get index columns
    List<String> indexedColumns =  validateAndGetIndexedColumns(dataMapSchema, carbonTable);

    // add optimizedOperations
    List<ExpressionType> optimizedOperations = new ArrayList<ExpressionType>();
    // optimizedOperations.add(ExpressionType.EQUALS);
    // optimizedOperations.add(ExpressionType.GREATERTHAN);
    // optimizedOperations.add(ExpressionType.GREATERTHAN_EQUALTO);
    // optimizedOperations.add(ExpressionType.LESSTHAN);
    // optimizedOperations.add(ExpressionType.LESSTHAN_EQUALTO);
    // optimizedOperations.add(ExpressionType.NOT);
    optimizedOperations.add(ExpressionType.TEXT_MATCH);
    this.dataMapMeta = new DataMapMeta(indexedColumns, optimizedOperations);

    // get analyzer
    // TODO: how to get analyzer ?
    analyzer = new StandardAnalyzer();
  }

  /**
   * validate Lucene DataMap
   * 1. require TEXT_COLUMNS property
   * 2. TEXT_COLUMNS can't contains illegal argument(empty, blank)
   * 3. TEXT_COLUMNS can't contains duplicate same columns
   * 4. TEXT_COLUMNS should be exists in table columns
   * 5. TEXT_COLUMNS support only String DataType columns
   */
  public static List<String> validateAndGetIndexedColumns(DataMapSchema dataMapSchema,
      CarbonTable carbonTable) throws MalformedDataMapCommandException {
    String textColumnsStr = dataMapSchema.getProperties().get(TEXT_COLUMNS);
    if (textColumnsStr == null || StringUtils.isBlank(textColumnsStr)) {
      throw new MalformedDataMapCommandException(
          "Lucene DataMap require proper TEXT_COLUMNS property.");
    }
    String[] textColumns = textColumnsStr.split(",", -1);
    for (int i = 0; i < textColumns.length; i++) {
      textColumns[i] = textColumns[i].trim().toLowerCase();
    }
    for (int i = 0; i < textColumns.length; i++) {
      if (textColumns[i].isEmpty()) {
        throw new MalformedDataMapCommandException("TEXT_COLUMNS contains illegal argument.");
      }
      for (int j = i + 1; j < textColumns.length; j++) {
        if (textColumns[i].equals(textColumns[j])) {
          throw new MalformedDataMapCommandException(
              "TEXT_COLUMNS has duplicate columns :" + textColumns[i]);
        }
      }
    }
    List<String> indexedCarbonColumns = new ArrayList<>(textColumns.length);
    for (int i = 0; i < textColumns.length; i++) {
      CarbonColumn column = carbonTable.getColumnByName(carbonTable.getTableName(), textColumns[i]);
      if (null == column) {
        throw new MalformedDataMapCommandException("TEXT_COLUMNS: " + textColumns[i]
            + " does not exist in table. Please check create DataMap statement.");
      } else if (column.getDataType() != DataTypes.STRING) {
        throw new MalformedDataMapCommandException(
            "TEXT_COLUMNS only supports String column. " + "Unsupported column: " + textColumns[i]
                + ", DataType: " + column.getDataType());
      } else if (column.getEncoder().contains(Encoding.DICTIONARY)) {
        throw new MalformedDataMapCommandException(
            "TEXT_COLUMNS cannot contain dictionary column " + column.getColName());
      }
      indexedCarbonColumns.add(column.getColName());
    }
    return indexedCarbonColumns;
  }

  /**
   * this method will delete the datamap folders during drop datamap
   * @throws MalformedDataMapCommandException
   */
  private void deleteDatamap() throws MalformedDataMapCommandException {
    SegmentStatusManager ssm = new SegmentStatusManager(tableIdentifier);
    try {
      List<Segment> validSegments = ssm.getValidAndInvalidSegments().getValidSegments();
      for (Segment segment : validSegments) {
        String segmentId = segment.getSegmentNo();
        String datamapPath =
            CarbonTablePath.getSegmentPath(tableIdentifier.getTablePath(), segmentId)
                + File.separator + dataMapName;
        if (FileFactory.isFileExist(datamapPath)) {
          CarbonFile file =
              FileFactory.getCarbonFile(datamapPath, FileFactory.getFileType(datamapPath));
          CarbonUtil.deleteFoldersAndFilesSilent(file);
        }
      }
    } catch (IOException ex) {
      throw new MalformedDataMapCommandException(
          "drop datamap failed, failed to delete datamap directory");
    } catch (InterruptedException ex) {
      throw new MalformedDataMapCommandException(
          "drop datamap failed, failed to delete datamap directory");
    }
  }

  /**
   * Return a new write for this datamap
   */
  @Override
  public DataMapWriter createWriter(Segment segment, String writeDirectoryPath) {
    LOGGER.info("lucene data write to " + writeDirectoryPath);
    return new LuceneDataMapWriter(tableIdentifier, dataMapName, segment, writeDirectoryPath, true,
        indexedCarbonColumns);
  }

  /**
   * Get all distributable objects of a segmentid
   */
  @Override public List<DataMapDistributable> toDistributable(Segment segment) {
    List<DataMapDistributable> lstDataMapDistribute = new ArrayList<>();
    CarbonFile[] indexDirs =
        getAllIndexDirs(tableIdentifier.getTablePath(), segment.getSegmentNo());
    for (CarbonFile indexDir : indexDirs) {
      // Filter out the tasks which are filtered through CG datamap.
      if (!segment.getFilteredIndexShardNames().contains(indexDir.getName())) {
        continue;
      }
      DataMapDistributable luceneDataMapDistributable = new LuceneDataMapDistributable(
          CarbonTablePath.getSegmentPath(tableIdentifier.getTablePath(), segment.getSegmentNo()),
          indexDir.getAbsolutePath());
      lstDataMapDistribute.add(luceneDataMapDistributable);
    }
    return lstDataMapDistribute;
  }

  @Override
  public void fireEvent(Event event) {

  }

  /**
   * Clears datamap of the segment
   */
  @Override
  public void clear(Segment segment) {

  }

  /**
   * Clear all datamaps from memory
   */
  @Override
  public void clear() {

  }

  @Override public void deleteDatamapData() {
    try {
      deleteDatamap();
    } catch (MalformedDataMapCommandException ex) {
      LOGGER.error(ex, "failed to delete datamap directory ");
    }
  }

  /**
   * Return metadata of this datamap
   */
  public DataMapMeta getMeta() {
    return dataMapMeta;
  }

  /**
   * returns all the directories of lucene index files for query
   * @param tablePath
   * @param segmentId
   * @return
   */
  private CarbonFile[] getAllIndexDirs(String tablePath, String segmentId) {
    List<CarbonFile> indexDirs = new ArrayList<>();
    List<TableDataMap> dataMaps = new ArrayList<>();
    try {
      // there can be multiple lucene datamaps present on a table, so get all datamaps and form
      // the path till the index file directories in all datamaps folders present in each segment
      dataMaps = DataMapStoreManager.getInstance().getAllDataMap(carbonTable);
    } catch (IOException ex) {
      LOGGER.error("failed to get datamaps");
    }
    if (dataMaps.size() > 0) {
      for (TableDataMap dataMap : dataMaps) {
        List<CarbonFile> indexFiles;
        String dmPath =
            CarbonTablePath.getSegmentPath(tablePath, segmentId) + File.separator + dataMap
                .getDataMapSchema().getDataMapName();
        FileFactory.FileType fileType = FileFactory.getFileType(dmPath);
        final CarbonFile dirPath = FileFactory.getCarbonFile(dmPath, fileType);
        indexFiles = Arrays.asList(dirPath.listFiles(new CarbonFileFilter() {
          @Override public boolean accept(CarbonFile file) {
            return file.isDirectory();
          }
        }));
        indexDirs.addAll(indexFiles);
      }
    }
    return indexDirs.toArray(new CarbonFile[0]);
  }
}
