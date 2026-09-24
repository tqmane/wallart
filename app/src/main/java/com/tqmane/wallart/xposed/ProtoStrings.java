package com.tqmane.wallart.xposed;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Extracts bounded text fields from opaque activity arguments without retaining them. */
final class ProtoStrings {
    private ProtoStrings() {
    }

    static List<String> extract(byte[] data) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (data != null && data.length <= 64 * 1024) readMessage(data, 0, data.length, 0, result);
        return new ArrayList<>(result);
    }

    static List<String> extractUtf16LittleEndian(byte[] data) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (data == null || data.length > 64 * 1024) return new ArrayList<>(result);
        for (int start = 0; start + 1 < data.length && result.size() < 64; start += 2) {
            StringBuilder value = new StringBuilder();
            int position = start;
            while (position + 1 < data.length && value.length() < 128) {
                char c = (char) ((data[position] & 0xff) | ((data[position + 1] & 0xff) << 8));
                if (!Character.isLetterOrDigit(c) && c != ' ' && c != '-' && c != '_' && c != '.') break;
                value.append(c);
                position += 2;
            }
            if (value.length() >= 4) {
                result.add(value.toString());
                start = position - 2;
            }
        }
        return new ArrayList<>(result);
    }

    private static void readMessage(byte[] data, int start, int end, int depth, LinkedHashSet<String> output) {
        if (depth > 4 || output.size() >= 64) return;
        int[] position = {start};
        while (position[0] < end && output.size() < 64) {
            long tag = readVarint(data, position, end);
            if (tag <= 0) return;
            switch ((int) (tag & 7)) {
                case 0:
                    if (readVarint(data, position, end) < 0) return;
                    break;
                case 1:
                    if (end - position[0] < 8) return;
                    position[0] += 8;
                    break;
                case 2:
                    long size = readVarint(data, position, end);
                    if (size < 0 || size > end - position[0]) return;
                    int fieldStart = position[0];
                    int fieldEnd = fieldStart + (int) size;
                    String value = decodeText(data, fieldStart, (int) size);
                    if (value != null) output.add(value);
                    else if (depth < 4) readMessage(data, fieldStart, fieldEnd, depth + 1, output);
                    position[0] = fieldEnd;
                    break;
                case 5:
                    if (end - position[0] < 4) return;
                    position[0] += 4;
                    break;
                default:
                    return;
            }
        }
    }

    private static long readVarint(byte[] data, int[] position, int end) {
        long value = 0;
        for (int shift = 0; shift < 64 && position[0] < end; shift += 7) {
            int next = data[position[0]++] & 0xff;
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
        }
        return -1;
    }

    private static String decodeText(byte[] data, int offset, int length) {
        if (length < 4 || length > 256) return null;
        String value = new String(data, offset, length, StandardCharsets.UTF_8);
        if (value.indexOf('\ufffd') >= 0 || value.codePoints().noneMatch(Character::isLetterOrDigit)
                || value.codePoints().anyMatch(Character::isISOControl)) return null;
        return value;
    }
}
