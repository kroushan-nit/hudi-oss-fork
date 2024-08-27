/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.client.model;

import org.apache.flink.table.data.RowData;

/**
 * RowData implementation for Hoodie Row. It wraps an {@link RowData} and keeps meta columns locally. But the {@link RowData}
 * does include the meta columns as well just that {@link HoodieRowData} will intercept queries for meta columns and serve from its
 * copy rather than fetching from {@link RowData}.
 *
 * <p>The wrapped {@link RowData} does not contain hoodie metadata fields.
 */
public class HoodieRowData extends AbstractHoodieRowData {

  public HoodieRowData(String commitTime,
                       String commitSeqNumber,
                       String recordKey,
                       String partitionPath,
                       String fileName,
                       RowData row,
                       boolean withOperation) {
    super(commitTime, commitSeqNumber, recordKey, partitionPath, fileName, row, withOperation);
  }

  @Override
  public int getArity() {
    return metaColumnsNum + row.getArity();
  }

  protected int rebaseOrdinal(int ordinal) {
    // NOTE: In cases when source row does not contain meta fields, we will have to
    //       rebase ordinal onto its indexes
    return ordinal - metaColumnsNum;
  }
}
