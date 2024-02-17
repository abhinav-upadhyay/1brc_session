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
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public class CalculateAverage_PEWorkshop11 {

    /**
     * Use MMap to read file
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

    public static void main(String[] args) throws IOException {
        String filename = args.length > 0 ? args[0] : FILE_NAME;
        FileChannel fc = FileChannel.open(Paths.get(filename), StandardOpenOption.READ);
        final long fileSize = fc.size();
        final long startAddress = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global()).address();
        final long endAddress = startAddress + fileSize;
        readFile(startAddress, endAddress);
        System.out.println(new TreeMap<>(records));
    }

    private static void readFile(long startAddress, long endAddress) {
        Scanner scanner = new Scanner(startAddress, endAddress);
        byte b;
        byte[] nameBytes = new byte[512];
        int nameBytesOffset = 0;
        byte[] temperatureBytes = new byte[8];
        int temperatureBytesOffset = 0;
        while (scanner.hasNext()) {
            while ((b = scanner.getByte()) != ';') {
                nameBytes[nameBytesOffset++] = b;
                scanner.add(1);
            }
            scanner.add(1);
            while ((b = scanner.getByte()) != '\n') {
                temperatureBytes[temperatureBytesOffset++] = b;
                scanner.add(1);
            }
            scanner.add(1);
            String name = new String(nameBytes, 0, nameBytesOffset);
            String temperatureStr = new String(temperatureBytes, 0, temperatureBytesOffset);
            float temperature = Float.parseFloat(temperatureStr);
            records.compute(name, (k, v) -> v == null ? new Row(temperature) : v.update(temperature));
            temperatureBytesOffset = 0;
            nameBytesOffset = 0;
        }

    }

    private static class Scanner {

        private static final sun.misc.Unsafe UNSAFE = initUnsafe();
        private long pos;
        private final long end;

        private static sun.misc.Unsafe initUnsafe() {
            try {
                java.lang.reflect.Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                return (sun.misc.Unsafe) theUnsafe.get(sun.misc.Unsafe.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        public Scanner(long pos, long end) {
            this.pos = pos;
            this.end = end;
        }

        boolean hasNext() {
            return pos < end;
        }

        void add(long delta) {
            pos += delta;
        }

        long getLong() {
            return UNSAFE.getLong(pos);
        }

        byte getByte() {
            return UNSAFE.getByte(pos);
        }
    }
}
