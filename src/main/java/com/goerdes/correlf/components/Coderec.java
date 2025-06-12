package com.goerdes.correlf.components;

import com.sun.jna.*;
import java.io.*;
import java.net.URL;
import java.nio.file.*;

/** JNA binding for coderec_jna.dll */
public class Coderec {

    private interface Lib extends Library {
        /** extern "C" fn coderec_detect_file(path: *const c_char) -> *mut c_char */
        Pointer coderec_detect_file(String path);

        /** extern "C" fn coderec_free_string(s: *mut c_char) */
        void coderec_free_string(Pointer s);
    }

    private static final Lib LIB;

    static {
        try {

            String res = "/coderec/coderec_jna.dll";
            URL url = Coderec.class.getResource(res);
            if (url == null) {
                throw new UnsatisfiedLinkError("Resource not found: " + res);
            }
            Path tmp = Files.createTempFile("coderec_jni", ".dll");
            tmp.toFile().deleteOnExit();
            try (InputStream in = url.openStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            // load native library
            LIB = Native.load(tmp.toAbsolutePath().toString(), Lib.class);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Analyze a single file. Returns JSON string or null on error.
     */
    public static String detectFile(String filePath) {
        Pointer resultPtr = LIB.coderec_detect_file(filePath);
        if (resultPtr == null) {
            return null;
        }
        String json = resultPtr.getString(0);
        LIB.coderec_free_string(resultPtr);
        return json;
    }
}
