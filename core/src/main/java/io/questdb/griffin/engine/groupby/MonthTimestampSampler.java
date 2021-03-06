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

package io.questdb.griffin.engine.groupby;

import io.questdb.std.microtime.Timestamps;

class MonthTimestampSampler implements TimestampSampler {
    private final int bucket;

    MonthTimestampSampler(int bucket) {
        this.bucket = bucket;
    }

    @Override
    public long nextTimestamp(long timestamp) {
        return Timestamps.addMonths(timestamp, bucket);
    }

    @Override
    public long previousTimestamp(long timestamp) {
        return Timestamps.addMonths(timestamp, -bucket);
    }

    @Override
    public long round(long value) {
        int y = Timestamps.getYear(value);
        boolean l = Timestamps.isLeapYear(y);
        int m = Timestamps.getMonthOfYear(value, y, l);
        // target month
        int n = ((m - 1) / bucket) * bucket + 1;
        return Timestamps.yearMicros(y, l) +
                Timestamps.monthOfYearMicros(n, l);
    }
}
