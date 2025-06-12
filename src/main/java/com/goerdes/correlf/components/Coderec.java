package com.goerdes.correlf.components;

import com.sun.jna.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JNA binding for coderec_jni.dll, loaded from application.properties
 */
@Component
public class Coderec {

    private interface Lib extends Library {
        /** extern "C" fn coderec_detect_file(path: *const c_char) -> *mut c_char */
        Pointer coderec_detect_file(String path);
        /** extern "C" fn coderec_free_string(s: *mut c_char) */
        void coderec_free_string(Pointer s);
    }

    private final Lib lib;

    /**
     * @param coderecLocation Spring Resource location (e.g. classpath:coderec/coderec_jni.dll)
     */
    public Coderec(@Value("${coderec.location}") Resource coderecLocation) {
        try {
            Path tmpFile = Files.createTempFile("coderec_jni", getExtension(coderecLocation));
            tmpFile.toFile().deleteOnExit();
            try (InputStream in = coderecLocation.getInputStream()) {
                Files.copy(in, tmpFile, StandardCopyOption.REPLACE_EXISTING);
            }
            this.lib = Native.load(tmpFile.toAbsolutePath().toString(), Lib.class);
        } catch (IOException e) {
            throw new ExceptionInInitializerError("Failed to load coderec native library: " + e.getMessage());
        }
    }

    /**
     * Analyze a single file. Returns JSON string or null on error.
     */
    public String detectFile(String filePath) {
        Pointer resultPtr = lib.coderec_detect_file(filePath);
        if (resultPtr == null) {
            return null;
        }
        String json = resultPtr.getString(0);
        lib.coderec_free_string(resultPtr);
        return json;
    }

    private String getExtension(Resource resource) {
        try {
            String filename = resource.getFilename();
            int idx = (filename != null) ? filename.lastIndexOf('.') : -1;
            return (idx >= 0) ? filename.substring(idx) : ".dll";
        } catch (Exception e) {
            return ".dll";
        }
    }
}
