package com.goerdes.correlf.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goerdes.correlf.components.coderec.Coderec;
import com.goerdes.correlf.exception.FileProcessingException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import static java.nio.ByteOrder.LITTLE_ENDIAN;

/**
 * Utility methods for packing and unpacking primitives to byte arrays.
 */
public final class ByteUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Packs the given array of doubles into a byte array using little‐endian order.
     *
     * @param values the doubles to pack
     * @return a byte[] of length values.length * 8 containing the IEEE-754 bits
     */
    public static byte[] packDoublesToBytes(double[] values) {
        ByteBuffer buf = ByteBuffer
                .allocate(values.length * Double.BYTES)
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
        if (bytes.length % Double.BYTES != 0) {
            throw new IllegalArgumentException("Byte array length must be a multiple of " + Double.BYTES);
        }
        ByteBuffer buf = ByteBuffer
                .wrap(bytes)
                .order(LITTLE_ENDIAN);
        double[] values = new double[bytes.length / Double.BYTES];
        for (int i = 0; i < values.length; i++) {
            values[i] = buf.getDouble();
        }
        return values;
    }

    /**
     * Packs an array of ints into a byte array (little‐endian).
     *
     * @param values the int[] to pack
     * @return a byte[] of length values.length * 4
     */
    public static byte[] packIntsToBytes(int[] values) {
        ByteBuffer buf = ByteBuffer
                .allocate(values.length * Integer.BYTES)
                .order(LITTLE_ENDIAN);
        for (int v : values) {
            buf.putInt(v);
        }
        return buf.array();
    }

    /**
     * Unpacks a little‐endian byte[] back into an int[].
     *
     * @param bytes the byte array (length must be multiple of 4)
     * @return an array of ints
     */
    public static int[] unpackBytesToInts(byte[] bytes) {
        if (bytes.length % Integer.BYTES != 0) {
            throw new IllegalArgumentException("Byte array length must be a multiple of " + Integer.BYTES);
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN);
        int[] ints = new int[bytes.length / Integer.BYTES];
        for (int i = 0; i < ints.length; i++) {
            ints[i] = buf.getInt();
        }
        return ints;
    }

    /**
     * Computes the SHA-256 digest of the given byte array and returns
     * it as a lowercase hexadecimal string.
     *
     * @param data the input bytes to hash
     * @return the hex-encoded SHA-256 hash
     * @throws FileProcessingException if SHA-256 algorithm is unavailable
     */
    public static String computeSha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new FileProcessingException("SHA-256 algorithm not available", e);
        }
    }



    public static byte[] serializeCodeRegions(List<Coderec.CodeRegion> regs) {
        try {
            return MAPPER.writeValueAsBytes(regs);
        } catch (JsonProcessingException e) {
            throw new FileProcessingException("Fehler beim Serialisieren der CodeRegions", e);
        }
    }

    public static List<Coderec.CodeRegion> deserializeCodeRegions(byte[] data) {
        try {
            return MAPPER.readValue(
                    data,
                    new TypeReference<>() {
                    }
            );
        } catch (IOException e) {
            throw new FileProcessingException(
                    "Error deserializing CodeRegion list", e
            );
        }
    }

}

