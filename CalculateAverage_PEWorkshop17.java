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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class CalculateAverage_PEWorkshop17 {

    /**
     * Use SWAR to parse the location name.
     *
     * We are scanning one byte at a time to get the bytes of the location name.
     * SWAR allows us to fetch 8 bytes in a go and check them for semicolon in a single
     * go. Thus reducing a ton of instructions
     *
     * This version cuts down the number of CPU instructions executed by half (nearly)
     */
    private static final String FILE_NAME = "./measurements.txt";

    private static final class Row {
        private short minTemp;
        private short maxTemp;
        private int count;
        private int sum;
        private final long hashCode;
        private final long locationOffset;
        private final int locationLength;

        public Row(short minTemp, short maxTemp, int count, int sum, long hashCode, long locationOffset,
                int locationLength) {
            this.minTemp = minTemp;
            this.maxTemp = maxTemp;
            this.count = count;
            this.sum = sum;
            this.hashCode = hashCode;
            this.locationOffset = locationOffset;
            this.locationLength = locationLength;
        }

        void update(short temperature) {
            this.minTemp = (short) Math.min(this.minTemp, temperature);
            this.maxTemp = (short) Math.max(this.maxTemp, temperature);
            this.count++;
            this.sum += temperature;
        }

        @Override
        public String toString() {
            return String.format("%.1f/%.1f/%.1f", (this.minTemp) / 10.0, this.sum / (count * 10.0), (maxTemp) / 10.0);
        }

        public void update(Row value) {
            this.minTemp = (short) Math.min(this.minTemp, value.minTemp);
            this.maxTemp = (short) Math.max(this.maxTemp, value.maxTemp);
            this.count += value.count;
            this.sum += value.sum;
        }

        public String getName() {
            Scanner scanner = new Scanner(locationOffset, locationOffset + locationLength + 1);
            byte[] array = new byte[locationLength];
            for (int i = 0; i < locationLength; i++) {
                array[i] = scanner.getByte(locationOffset + i);
            }
            return new String(array, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static class Table {
        private static final int TABLE_SIZE = 1 << 17;
        private static final int TABLE_MASK = TABLE_SIZE - 1;

        private final Row[] table = new Row[TABLE_SIZE];

    }

    public static void main(String[] args) throws IOException {
        String filename = args.length > 0 ? args[0] : FILE_NAME;
        FileChannel fc = FileChannel.open(Paths.get(filename), StandardOpenOption.READ);
        final long fileSize = fc.size();
        final long startAddress = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global()).address();
        final long endAddress = startAddress + fileSize;
        final Scanner scanner = new Scanner(startAddress, endAddress);
        int nthreads = fileSize > 1024 * 1024 * 1024 ? 8 : 1;
        final long[][] segments = findSegments(scanner, fileSize, nthreads);
        final List<Table> tableList = Arrays.stream(segments).parallel().map(s -> {
            Scanner scanner1 = new Scanner(s[0], s[1]);
            return readFile(scanner1);
        }).toList();

        TreeMap<String, Row> finalMap = new TreeMap<>();
        for (Table t : tableList) {
            for (Row r : t.table) {
                if (r == null) {
                    continue;
                }
                String locationName = r.getName();
                final Row row = finalMap.get(locationName);
                if (row == null) {
                    finalMap.put(locationName, r);
                } else {
                    row.update(r);
                }
            }
        }
        System.out.println(new TreeMap<>(finalMap));
    }

    private static short parseTemperature(Scanner scanner) {
        short temperatureVal = 0;
        short scale = 1;
        final byte firstByte = scanner.getByte();
        short isNegative = (short) (firstByte == '-' ? -1 : 1);
        if (isNegative == 1) {
            temperatureVal = (short) (firstByte - '0');
            scale = 10;
        }
        while (true) {
            byte b = scanner.getByte();
            if (b == '\n') {
                break;
            }
            if (b == '.') {
                continue;
            }
            temperatureVal = (short) (temperatureVal * scale + b - '0');
            scale = 10;
        }
        return (short) (temperatureVal * isNegative);
    }

    private static long findDelimiter(long word) {
        long input = word ^ 0x3B3B3B3B3B3B3B3BL;
        return (input - 0x0101010101010101L) & ~input & 0x8080808080808080L;
    }

    private static int getTableIndex(long hashCode) {
        return (int) (hashCode ^ (hashCode >>> 33) ^ (hashCode >>> 15)) & Table.TABLE_MASK;
    }

    private static Row findRow(Scanner scanner, Table table) {
        long word = scanner.getLong();
        long delimiterMask = findDelimiter(word);
        long hashCode = 1;
        long locationOffset = scanner.pos;
        while (true) {
            if (delimiterMask != 0) {
                int trailingZeros = Long.numberOfTrailingZeros(delimiterMask);
                word = (word << (63 - trailingZeros));
                final int bytesCount = trailingZeros >> 3;
                scanner.add(bytesCount);
                hashCode ^= word;
                break;
            }
            hashCode ^= word;
            scanner.add(8);
            word = scanner.getLong();
            delimiterMask = findDelimiter(word);
        }

        int tableIndex = getTableIndex(hashCode);
        int locationLength = (int) (scanner.pos - locationOffset);
        Row r = table.table[tableIndex];
        if (r == null) {
            r = new Row((short) 1000, (short) -1000, 0, 0, hashCode, locationOffset, locationLength);
            table.table[tableIndex] = r;
            return r;
        }

        outer: while (r != null) {
            if (r.hashCode == hashCode) {
                int i = 0;
                for (; i < locationLength + 1 - 8; i += 8) {
                    if (scanner.getLong(r.locationOffset + i) != scanner.getLong(locationOffset + i)) {
                        tableIndex = (tableIndex + 31) & Table.TABLE_MASK;
                        r = table.table[tableIndex];
                        continue outer;
                    }
                }

                int remainingShift = 64 - ((locationLength + 1 - i) << 3);
                long remainingBits1 = scanner.getLong(r.locationOffset + i) << remainingShift;
                long remainingBits2 = scanner.getLong(locationOffset + i) << remainingShift;
                if ((remainingBits1 ^ remainingBits2) == 0) {
                    return r;
                }
                tableIndex = (tableIndex + 31) & Table.TABLE_MASK;
                r = table.table[tableIndex];
            } else {
                tableIndex = (tableIndex + 31) & Table.TABLE_MASK;
                r = table.table[tableIndex];
            }
        }

        r = new Row((short) 1000, (short) -1000, 0, 0, hashCode, locationOffset, locationLength);
        table.table[tableIndex] = r;
        return r;
    }

    private static Table readFile(Scanner scanner) {
        Table table = new Table();
        while (scanner.hasNext()) {
            Row row = findRow(scanner, table);
            scanner.add(1);
            short temperature = parseTemperature(scanner);
            row.update(temperature);
        }
        return table;
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

        long getLong(long offset) {
            return UNSAFE.getLong(offset);
        }

        byte getByte() {
            return UNSAFE.getByte(pos++);
        }

        public byte getByte(long offset) {
            return UNSAFE.getByte(offset);
        }
    }
}
