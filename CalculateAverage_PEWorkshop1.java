/*
 *  Copyright 2023 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dev.morling.onebrc;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.*;

public class CalculateAverage_PEWorkshop1 {

    /**
     * Most naive implementation. Simply streaming lines using BuffredReader and parsing them
     */
    private static final String FILE_NAME = "./measurements.txt";

    private static final class Row {
        private final float minTemp;
        private final float maxTemp;
        private final int count;
        private final float sum;

        public Row(float minTemp, float maxTemp, int count, float sum) {
            this.minTemp = minTemp;
            this.maxTemp = maxTemp;
            this.count = count;
            this.sum = sum;
        }

        public Row(float temperature) {
            this(temperature, temperature, 1, temperature);
        }

        Row update(float temperature) {
            float minTemp = Float.min(this.minTemp, temperature);
            float maxTemp = Float.max(this.maxTemp, temperature);
            return new Row(minTemp, maxTemp, this.count + 1, this.sum + temperature);
        }

        @Override
        public String toString() {
            return String.format("%.1f/%.1f/%.1f", (this.minTemp), (this.sum / count), (maxTemp));
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (obj == null || obj.getClass() != this.getClass())
                return false;
            var that = (Row) obj;
            return Float.floatToIntBits(this.minTemp) == Float.floatToIntBits(that.minTemp)
                    && Float.floatToIntBits(this.maxTemp) == Float.floatToIntBits(that.maxTemp)
                    && this.count == that.count && Float.floatToIntBits(this.sum) == Float.floatToIntBits(that.sum);
        }

        @Override
        public int hashCode() {
            return Objects.hash(minTemp, maxTemp, count, sum);
        }

    }

    ;

    private static final Map<String, Row> records = new HashMap<>();

    public static void main(String[] args) throws FileNotFoundException {
        String filename = args.length > 0 ? args[0] : FILE_NAME;
        BufferedReader br = new BufferedReader(new FileReader(filename));
        br.lines().forEach(l -> {
            int semiColonIndex = l.indexOf(";");
            String locationName = l.substring(0, semiColonIndex);
            String temperatureValStr = l.substring(semiColonIndex + 1);
            float temperature = Float.parseFloat(temperatureValStr);
            records.compute(locationName, (k, v) -> v == null ? new Row(temperature) : v.update(temperature));
        });
        System.out.println(new TreeMap<>(records));
    }
}
