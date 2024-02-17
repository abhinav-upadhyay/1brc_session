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
import java.util.*;
import java.util.stream.Collectors;

public class CalculateAverage_PEWorkshop15 {

    /**
     * Replace HashMap with a custom hash table which uses the bytes of the location name as key We compute the hashcode
     * as we read the bytes from the file to save overhead of scanning the bytes again.
     */
    private static final String FILE_NAME = "./measurements.txt";

    private static final class Row {
        private short minTemp;
        private short maxTemp;
        private int count;
        private int sum;
        private final int hashCode;
        private final byte[] location;

        public Row(short minTemp, short maxTemp, int count, int sum, int hashCode, byte[] location) {
            this.minTemp = minTemp;
            this.maxTemp = maxTemp;
            this.count = count;
            this.sum = sum;
            this.hashCode = hashCode;
            this.location = location;
        }

        public Row(short temperature, int hashCode, byte[] location) {
            this(temperature, temperature, 1, temperature, hashCode, location);
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
    }

    private static class Table {
        private static final int TABLE_SIZE = 1 << 17;
        private static final int TABLE_MASK = TABLE_SIZE - 1;

        private final Row[] table = new Row[TABLE_SIZE];

        public void put(byte[] location, int locationLength, int hashCode, short temperature) {
            int tableIndex = hashCode & TABLE_MASK;
            Row r = table[tableIndex];
            if (r == null) {
                byte[] locationCopy = new byte[locationLength];
                System.arraycopy(location, 0, locationCopy, 0, locationLength);
                table[tableIndex] = new Row(temperature, hashCode, locationCopy);
                return;
            }

            while (r != null) {
                if (r.hashCode == hashCode
                        && Arrays.equals(r.location, 0, r.location.length, location, 0, locationLength)) {
                    r.update(temperature);
                    return;
                }
                tableIndex = (tableIndex + 31) & TABLE_MASK;
                r = table[tableIndex];
            }
            byte[] locationCopy = new byte[locationLength];
            System.arraycopy(location, 0, locationCopy, 0, locationLength);
            table[tableIndex] = new Row(temperature, hashCode, locationCopy);
        }

        private void put(Row e) {
            int tableIndex = e.hashCode & TABLE_MASK;
            Row r = table[tableIndex];
            if (r == null) {
                table[tableIndex] = e;
                return;
            }

            while (r != null) {
                if (r.hashCode == e.hashCode
                        && Arrays.equals(r.location, 0, r.location.length, e.location, 0, e.location.length)) {
                    r.update(e);
                    return;
                }
                tableIndex = (tableIndex + 31) & TABLE_MASK;
                r = table[tableIndex];
            }
            table[tableIndex] = e;
        }

        private static Table mergeTables(List<Table> tables) {
            // assuming maps.length is a multiple of 2
            while (tables.size() > 1) {
                List<Table[]> pairs = new ArrayList<>();
                for (int i = 0; i < tables.size(); i += 2) {
                    final Table[] e = new Table[2];
                    e[0] = tables.get(i);
                    e[1] = tables.get(i + 1);
                    pairs.add(e);
                }
                tables = pairs.parallelStream().map(p -> {
                    final Row[] entries = p[1].table;
                    final Table m = p[0];
                    for (Row e : entries) {
                        if (e != null) {
                            m.put(e);
                        }
                    }
                    return m;
                }).collect(Collectors.toList());
            }
            return tables.getFirst();

        }

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
        }).collect(Collectors.toList());
        Table table = Table.mergeTables(tableList);
        TreeMap<String, Row> finalMap = new TreeMap<>();
        for (Row r : table.table) {
            if (r != null) {
                finalMap.put(new String(r.location), r);
            }
        }
        System.out.println(new TreeMap<>(finalMap));
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

    private static Table readFile(Scanner scanner) {
        Table table = new Table();
        byte b;
        byte[] nameBytes = new byte[512];
        int nameBytesOffset = 0;
        byte[] temperatureBytes = new byte[8];
        int temperatureBytesOffset = 0;
        int hashCode = 1;
        while (scanner.hasNext()) {
            while ((b = scanner.getByte()) != ';') {
                nameBytes[nameBytesOffset++] = b;
                hashCode = hashCode * 31 + b;
                scanner.add(1);
            }
            scanner.add(1);
            while ((b = scanner.getByte()) != '\n') {
                temperatureBytes[temperatureBytesOffset++] = b;
                scanner.add(1);
            }
            scanner.add(1);
            short temperature = parseTemperature(temperatureBytes, temperatureBytesOffset);
            table.put(nameBytes, nameBytesOffset, hashCode, temperature);
            temperatureBytesOffset = 0;
            nameBytesOffset = 0;
            hashCode = 1;
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

        byte getByte() {
            return UNSAFE.getByte(pos);
        }

        public byte getByte(long offset) {
            return UNSAFE.getByte(offset);
        }
    }
}
