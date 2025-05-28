package com.goerdes.correlf.utils;

import java.nio.ByteBuffer;

import static java.lang.Double.BYTES;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

/**
 * Utility methods for packing and unpacking primitives to byte arrays.
 */
public final class ByteUtils {

    /**
     * Packs the given array of doubles into a byte array using little‐endian order.
     *
     * @param values the doubles to pack
     * @return a byte[] of length values.length * 8 containing the IEEE-754 bits
     */
    public static byte[] packDoublesToBytes(double[] values) {
        ByteBuffer buf = ByteBuffer
                .allocate(values.length * BYTES)
                .order(LITTLE_ENDIAN);
        for (double v : values) {
            buf.putDouble(v);
        }
        return buf.array();
    }

    /**
     * Unpacks a byte array (little‐endian) back into an array of doubles.
     *
     * @param bytes the byte array (must be a multiple of 8)
     * @return the resulting array of doubles
     * @throws IllegalArgumentException if bytes.length % 8 != 0
     */
    public static double[] unpackBytesToDoubles(byte[] bytes) {
        if (bytes.length % BYTES != 0) {
            throw new IllegalArgumentException("Byte array length must be a multiple of " + BYTES);
        }
        ByteBuffer buf = ByteBuffer
                .wrap(bytes)
                .order(LITTLE_ENDIAN);
        double[] values = new double[bytes.length / BYTES];
        for (int i = 0; i < values.length; i++) {
            values[i] = buf.getDouble();
        }
        return values;
    }
}

