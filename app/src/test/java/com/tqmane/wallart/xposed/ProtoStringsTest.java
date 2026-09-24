package com.tqmane.wallart.xposed;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ProtoStringsTest {
    @Test
    public void extractsTextFieldsFromNestedMessages() {
        byte[] cardId = field(1, "instrument-42".getBytes(StandardCharsets.UTF_8));
        byte[] label = field(2, "Mina debit".getBytes(StandardCharsets.UTF_8));
        byte[] message = field(1, cardId);
        byte[] nested = field(2, label);
        byte[] combined = new byte[message.length + nested.length];
        System.arraycopy(message, 0, combined, 0, message.length);
        System.arraycopy(nested, 0, combined, message.length, nested.length);

        assertEquals(Arrays.asList("instrument-42", "Mina debit"), ProtoStrings.extract(combined));
    }

    @Test
    public void extractsParcelStyleUtf16Strings() {
        byte[] text = "Mina debit\u0000".getBytes(StandardCharsets.UTF_16LE);
        byte[] parcel = new byte[text.length + 4];
        parcel[0] = 10;
        System.arraycopy(text, 0, parcel, 4, text.length);

        assertTrue(ProtoStrings.extractUtf16LittleEndian(parcel).contains("Mina debit"));
    }

    private static byte[] field(int number, byte[] value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write((number << 3) | 2);
        output.write(value.length);
        output.write(value, 0, value.length);
        return output.toByteArray();
    }
}
