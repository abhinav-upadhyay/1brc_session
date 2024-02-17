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
import java.util.List;
import java.util.TreeMap;

public class CalculateAverage_PEWorkshop19 {

    /**
     * Most of the location names are <= 16 bytes. The collision check can be optimized for handling these
     *
     * By storing the first two long words of each location name in the Row object itself, we simply need to compare the
     * two long values against the colliding key. This logic is much simpler and in majority of cases the other branch
     * will not execute. Thus
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
        private final long firstWord;
        private final long secondWord;

        public Row(short minTemp, short maxTemp, int count, int sum, long hashCode, long locationOffset,
                int locationLength, long firstWord, long secondWord) {
            this.minTemp = minTemp;
            this.maxTemp = maxTemp;
            this.count = count;
            this.sum = sum;
            this.hashCode = hashCode;
            this.locationOffset = locationOffset;
            this.locationLength = locationLength;
            this.firstWord = firstWord;
            this.secondWord = secondWord;
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
        // Table table = Table.mergeTables(tableList);
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

    private static long findDelimiter(long word) {
        long input = word ^ 0x3B3B3B3B3B3B3B3BL;
        return (input - 0x0101010101010101L) & ~input & 0x8080808080808080L;
    }

    private static int getTableIndex(long hashCode) {
        return (int) (hashCode ^ (hashCode >>> 33) ^ (hashCode >>> 15)) & Table.TABLE_MASK;
    }

    private static final long[] MASK1 = new long[] { 0xFFL, 0xFFFFL, 0xFFFFFFL, 0xFFFFFFFFL, 0xFFFFFFFFFFL,
            0xFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL, 0xFFFFFFFFFFFFFFFFL };
    private static final long[] MASK2 = new long[] { 0x00L, 0x00L, 0x00L, 0x00L, 0x00L, 0x00L, 0x00L, 0x00L,
            0xFFFFFFFFFFFFFFFFL };

    private static Row findRow(Scanner scanner, long word1, long delimiterMask1, long word2, long delimiterMask2,
            Table table) {
        long word = word1;
        long delimiterMask = delimiterMask1;
        long hashCode;
        long locationOffset = scanner.pos;
        if ((delimiterMask1 | delimiterMask2) != 0) {
            int letterCount1 = Long.numberOfTrailingZeros(delimiterMask1) >>> 3;
            int letterCount2 = Long.numberOfTrailingZeros(delimiterMask2) >>> 3;
            long mask = MASK2[letterCount1];
            word = word & MASK1[letterCount1];
            word2 = mask & word2 & MASK1[letterCount2];
            hashCode = word ^ word2;
            int tableIndex = getTableIndex(hashCode);
            Row row = table.table[tableIndex];
            scanner.add(letterCount1 + (letterCount2 & mask));
            if (row != null && row.firstWord == word && row.secondWord == word2) {
                return row;
            }
        } else {
            hashCode = word ^ word2;
            scanner.add(16);
            while (true) {
                word = scanner.getLong();
                delimiterMask = findDelimiter(word);
                if (delimiterMask != 0) {
                    int trailingZeros = Long.numberOfTrailingZeros(delimiterMask);
                    word = (word << (63 - trailingZeros));
                    final int bytesCount = trailingZeros >>> 3;
                    scanner.add(bytesCount);
                    hashCode ^= word;
                    break;
                }
                hashCode ^= word;
                scanner.add(8);
            }
        }

        int tableIndex = getTableIndex(hashCode);
        int locationLength = (int) (scanner.pos - locationOffset);
        Row r = table.table[tableIndex];
        if (r == null) {
            r = new Row((short) 1000, (short) -1000, 0, 0, hashCode, locationOffset, locationLength, word, word2);
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

        r = new Row((short) 1000, (short) -1000, 0, 0, hashCode, locationOffset, locationLength, word, word2);
        table.table[tableIndex] = r;
        return r;
    }

    private static Table readFile(Scanner scanner) {
        Table table = new Table();
        while (scanner.hasNext()) {
            long word1 = scanner.getLong();
            long word2 = scanner.getLong(scanner.pos + 8);
            long delimeterMask1 = findDelimiter(word1);
            long delimeterMask2 = findDelimiter(word2);
            Row row = findRow(scanner, word1, delimeterMask1, word2, delimeterMask2, table);
            scanner.add(1);
            short temperature = scanNumber(scanner);
            row.update(temperature);
        }
        return table;
    }

    private static short scanNumber(Scanner scanPtr) {
        long numberWord = scanPtr.getLong(scanPtr.pos);
        int decimalSepPos = Long.numberOfTrailingZeros(~numberWord & 0x10101000L);
        long number = convertIntoNumber(decimalSepPos, numberWord);
        scanPtr.add((decimalSepPos >>> 3) + 3);
        return (short) number;
    }

    private static long convertIntoNumber(int decimalSepPos, long numberWord) {
        int shift = 28 - decimalSepPos;
        // signed is -1 if negative, 0 otherwise
        long signed = (~numberWord << 59) >> 63;
        long designMask = ~(signed & 0xFF);
        // Align the number to a specific position and transform the ascii to digit value
        long digits = ((numberWord & designMask) << shift) & 0x0F000F0F00L;
        // Now digits is in the form 0xUU00TTHH00 (UU: units digit, TT: tens digit, HH: hundreds digit)
        // 0xUU00TTHH00 * (100 * 0x1000000 + 10 * 0x10000 + 1) =
        // 0x000000UU00TTHH00 + 0x00UU00TTHH000000 * 10 + 0xUU00TTHH00000000 * 100
        long absValue = ((digits * 0x640a0001) >>> 32) & 0x3FF;
        return (absValue ^ signed) - signed;
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
