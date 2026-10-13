package app.originisland.extension;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the fields of a protocol buffer message from its bytes.
 *
 * The messages of the app are obfuscated, but can be written as bytes, and the numbers
 * of their fields are part of what the app and its servers agree on.
 */
final class ProtoReader {

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_FIXED32 = 5;

    private final byte[] data;
    private final int start;
    private final int end;

    ProtoReader(byte[] data) {
        this(data, 0, data.length);
    }

    private ProtoReader(byte[] data, int start, int end) {
        this.data = data;
        this.start = start;
        this.end = end;
    }

    /**
     * @return The first message of the field with this number, or null if the message has none.
     */
    ProtoReader message(int fieldNumber) {
        List<ProtoReader> messages = find(fieldNumber, WIRE_LENGTH_DELIMITED, true);
        return messages.isEmpty() ? null : messages.get(0);
    }

    /**
     * @return The message found by following the fields with these numbers,
     *         or null if one of them is not set.
     */
    ProtoReader message(int... fieldNumbers) {
        ProtoReader message = this;
        for (int fieldNumber : fieldNumbers) {
            message = message.message(fieldNumber);
            if (message == null) {
                return null;
            }
        }

        return message;
    }

    List<ProtoReader> messages(int fieldNumber) {
        return find(fieldNumber, WIRE_LENGTH_DELIMITED, false);
    }

    /**
     * @return The messages of the last of the fields with these numbers, in the message
     *         found by following the fields before it, which is empty if one of them is not set.
     */
    List<ProtoReader> messagesAt(int... fieldNumbers) {
        ProtoReader message = this;
        for (int i = 0; i < fieldNumbers.length - 1; i++) {
            message = message.message(fieldNumbers[i]);
            if (message == null) {
                return new ArrayList<>();
            }
        }

        return message.messages(fieldNumbers[fieldNumbers.length - 1]);
    }

    /**
     * @return The bytes of this message as a string.
     */
    String text() {
        return new String(data, start, end - start, StandardCharsets.UTF_8);
    }

    /**
     * @return The string of the field with this number, or null if the message has none.
     */
    String string(int fieldNumber) {
        ProtoReader bytes = message(fieldNumber);
        return bytes == null ? null : bytes.text();
    }

    /**
     * @return The number of the field with this number, or the default if the message has none.
     */
    long number(int fieldNumber, long defaultValue) {
        List<ProtoReader> values = find(fieldNumber, WIRE_VARINT, true);
        if (values.isEmpty()) {
            return defaultValue;
        }

        ProtoReader value = values.get(0);
        return readVarint(value.start, new int[1]);
    }

    /**
     * @param first If only the first field with the number is wanted.
     * @return The fields with this number and wire type, as readers of their values.
     */
    private List<ProtoReader> find(int fieldNumber, int wireType, boolean first) {
        List<ProtoReader> found = new ArrayList<>();
        int[] position = {start};

        while (position[0] < end) {
            long key = readVarint(position[0], position);
            int number = (int) (key >>> 3);
            int wire = (int) (key & 7);
            int valueStart = position[0];
            int valueEnd;

            switch (wire) {
                case WIRE_VARINT:
                    readVarint(valueStart, position);
                    valueEnd = position[0];
                    break;
                case WIRE_FIXED64:
                    valueEnd = valueStart + 8;
                    break;
                case WIRE_FIXED32:
                    valueEnd = valueStart + 4;
                    break;
                case WIRE_LENGTH_DELIMITED:
                    long length = readVarint(valueStart, position);
                    valueStart = position[0];
                    if (length < 0 || length > end - valueStart) {
                        throw new IllegalArgumentException("Field " + number + " is longer than its message");
                    }
                    valueEnd = valueStart + (int) length;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown wire type " + wire);
            }
            if (valueEnd > end) {
                throw new IllegalArgumentException("Field " + number + " is longer than its message");
            }
            position[0] = valueEnd;

            if (number == fieldNumber && wire == wireType) {
                found.add(new ProtoReader(data, valueStart, valueEnd));
                if (first) {
                    break;
                }
            }
        }

        return found;
    }

    /**
     * @param next Set to the position after the number.
     */
    private long readVarint(int position, int[] next) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (position >= end) {
                throw new IllegalArgumentException("Number is longer than its message");
            }

            byte b = data[position++];
            value |= (long) (b & 0x7F) << shift;
            if (b >= 0) {
                next[0] = position;
                return value;
            }
        }

        throw new IllegalArgumentException("Number is too long");
    }
}
