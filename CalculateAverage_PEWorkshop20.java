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

public class CalculateAverage_PEWorkshop20 {

    /**
     * Use 3-way loop unrolling.
     *
     * We split the segment being processed by a thread into 3 parts and create 3 scanners for them.
     * We read 16 bytes from each scanner, then try to parse them one after another. This can potentially
     * allow the CPU to improve its instruction throughput because it can see that all those instructions
     * are independent. 
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
        final List<Table> tableList = Arrays.stream(segments).parallel().map(s -> readFile(s[0], s[1])).toList();
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
        // System.out.println(finalMap.size());
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
        long delimiterMask;
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

    private static Table readFile(long startOffset, long endOffset) {
        Table table = new Table();
        long dist = (endOffset - startOffset) / 3;
        long midPoint1 = nextNewLine(startOffset + dist);
        long midPoint2 = nextNewLine(startOffset + dist + dist);
        Scanner scanner1 = new Scanner(startOffset, midPoint1);
        Scanner scanner2 = new Scanner(midPoint1 + 1, midPoint2);
        Scanner scanner3 = new Scanner(midPoint2 + 1, endOffset);
        while (true) {
            if (!scanner1.hasNext() || !scanner2.hasNext() || !scanner3.hasNext()) {
                break;
            }
            long word1 = scanner1.getLong();
            long word2 = scanner2.getLong();
            long word3 = scanner3.getLong();
            long delimeterMask1 = findDelimiter(word1);
            long delimeterMask2 = findDelimiter(word2);
            long delimeterMask3 = findDelimiter(word3);
            long word1b = scanner1.getLong(scanner1.pos + 8);
            long word2b = scanner2.getLong(scanner2.pos + 8);
            long word3b = scanner3.getLong(scanner3.pos + 8);
            long delimeterMask1b = findDelimiter(word1b);
            long delimeterMask2b = findDelimiter(word2b);
            long delimeterMask3b = findDelimiter(word3b);
            Row row1 = findRow(scanner1, word1, delimeterMask1, word1b, delimeterMask1b, table);
            Row row2 = findRow(scanner2, word2, delimeterMask2, word2b, delimeterMask2b, table);
            Row row3 = findRow(scanner3, word3, delimeterMask3, word3b, delimeterMask3b, table);
            // if (row1.getName().equals("id3380") || row2.getName().equals("id3380") ||
            // row3.getName().equals("id3380")) {
            // System.out.println("ssadsa");
            // }
            scanner1.add(1);
            scanner2.add(1);
            scanner3.add(1);
            short temperature1 = scanNumber(scanner1);
            short temperature2 = scanNumber(scanner2);
            short temperature3 = scanNumber(scanner3);
            row1.update(temperature1);
            row2.update(temperature2);
            row3.update(temperature3);
        }

        finishScanner(scanner1, table);
        finishScanner(scanner2, table);
        finishScanner(scanner3, table);
        return table;
    }

    private static void finishScanner(Scanner scanner1, Table table) {
        while (scanner1.hasNext()) {
            long word = scanner1.getLong();
            long mask = findDelimiter(word);
            long wordb = scanner1.getLong(scanner1.pos + 8);
            long maskb = findDelimiter(wordb);
            Row r = findRow(scanner1, word, mask, wordb, maskb, table);
            // if (r.getName().equals("id3380")) {
            // System.out.println("asdad");
            // }
            scanner1.add(1);
            short temperature = scanNumber(scanner1);
            r.update(temperature);
        }
    }

    private static long nextNewLine(long prev) {
        while (true) {
            long currentWord = Scanner.UNSAFE.getLong(prev);
            long input = currentWord ^ 0x0A0A0A0A0A0A0A0AL;
            long pos = (input - 0x0101010101010101L) & ~input & 0x8080808080808080L;
            if (pos != 0) {
                prev += Long.numberOfTrailingZeros(pos) >>> 3;
                break;
            } else {
                prev += 8;
            }
        }
        return prev;
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
