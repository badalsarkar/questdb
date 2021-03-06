/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.functions.catalogue;

import io.questdb.cairo.ColumnType;
import io.questdb.cairo.GenericRecordMetadata;
import io.questdb.cairo.TableColumnMetadata;
import io.questdb.cairo.sql.NoRandomAccessRecordCursor;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordMetadata;

class NamespaceCatalogueCursor implements NoRandomAccessRecordCursor {
    static final RecordMetadata METADATA;

    static {
        final GenericRecordMetadata metadata = new GenericRecordMetadata();
        metadata.add(new TableColumnMetadata("nspname", ColumnType.STRING));
        metadata.add(new TableColumnMetadata("oid", ColumnType.INT));
        METADATA = metadata;
    }

    private int row = 0;

    @Override
    public void close() {
        row = 0;
    }

    @Override
    public Record getRecord() {
        return NamespaceCatalogueRecord.INSTANCE;
    }

    @Override
    public boolean hasNext() {
        return row++ == 0;
    }

    @Override
    public void toTop() {
        row = 0;
    }

    @Override
    public long size() {
        return 1;
    }

    private static class NamespaceCatalogueRecord implements Record {
        private static final NamespaceCatalogueRecord INSTANCE = new NamespaceCatalogueRecord();

        @Override
        public int getInt(int col) {
            return 1;
        }

        @Override
        public CharSequence getStr(int col) {
            return "public";
        }
    }

}
