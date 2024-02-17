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

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class CalculateAverage_PEWorkshop13 {

    /**
     * Distribute the computation between multiple threads
     */
    private static final String FILE_NAME = "./measurements.txt";

    private static final class Row {
        private final short minTemp;
        private final short maxTemp;
        private final int count;
        private final int sum;

        public Row(short minTemp, short maxTemp, int count, int sum) {
            this.minTemp = minTemp;
            this.maxTemp = maxTemp;
            this.count = count;
            this.sum = sum;
        }

        public Row(short temperature) {
            this(temperature, temperature, 1, temperature);
        }

        Row update(short temperature) {
            short minTemp = (short) Math.min(this.minTemp, temperature);
            short maxTemp = (short) Math.max(this.maxTemp, temperature);
            return new Row(minTemp, maxTemp, this.count + 1, this.sum + temperature);
        }

        @Override
        public String toString() {
            return String.format("%.1f/%.1f/%.1f", (this.minTemp) / 10.0, this.sum / (count * 10.0), (maxTemp) / 10.0);
        }
    }

    private static final Map<String, Row> records = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        String filename = args.length > 0 ? args[0] : FILE_NAME;
        FileChannel fc = FileChannel.open(Paths.get(filename), StandardOpenOption.READ);
        final long fileSize = fc.size();
        final long startAddress = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global()).address();
        final long endAddress = startAddress + fileSize;
        final Scanner scanner = new Scanner(startAddress, endAddress);
        int nthreads = fileSize > 1024 * 1024 * 1024 ? 8 : 1;
        final long[][] segments = findSegments(scanner, fileSize, nthreads);
        Arrays.stream(segments).parallel().forEach(s -> {
            Scanner scanner1 = new Scanner(s[0], s[1]);
            readFile(scanner1);
        });
        System.out.println(new TreeMap<>(records));
    }

    private static short parseTemperature(byte[] temperatureBytes, int temperatureBytesLength) {
        short temperatureVal = 0;
        short scale = 1;
        final byte firstByte = temperatureBytes[0];
        short isNegative = (short) (firstByte == '-' ? -1 : 1);
        if (isNegative == 1) {
            temperatureVal = (short) (firstByte - '0');
            scale = 10;
        }
        for (int i = 1; i < temperatureBytesLength; i++) {
            byte b = temperatureBytes[i];
            if (b == '.') {
                continue;
            }
            temperatureVal = (short) (temperatureVal * scale + b - '0');
            scale = 10;
        }
        return (short) (temperatureVal * isNegative);
    }

    private static void readFile(Scanner scanner) {
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
            short temperature = parseTemperature(temperatureBytes, temperatureBytesOffset);
            records.compute(name, (k, v) -> v == null ? new Row(temperature) : v.update(temperature));
            temperatureBytesOffset = 0;
            nameBytesOffset = 0;
        }
    }

    private static long[][] findSegments(Scanner scanner, long size, int segmentCount) {
        if (segmentCount == 1) {
            return new long[][] { { scanner.pos, scanner.end } };
        }
        long[][] segments = new long[segmentCount][2];
        long segmentSize = size / segmentCount + 1;
        int i = 0;
        long currentOffset = scanner.pos;
        while (currentOffset < scanner.end) {
            segments[i][0] = currentOffset;
            currentOffset += segmentSize;
            currentOffset = Math.min(currentOffset, scanner.end);
            if (currentOffset >= scanner.end) {
                segments[i][1] = scanner.end;
                break;
            }
            while (scanner.getByte(currentOffset) != '\n') {
                currentOffset++;
                // align to newline boundary
            }
            segments[i++][1] = currentOffset++;
        }
        return segments;
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

        public byte getByte(long offset) {
            return UNSAFE.getByte(offset);
        }
    }
}
