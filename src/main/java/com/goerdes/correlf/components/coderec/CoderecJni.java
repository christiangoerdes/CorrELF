package com.goerdes.correlf.components.coderec;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads and provides access to the native <code>coderec_jni</code> library via JNI.
 */
@Component
public class CoderecJni {

    static {
        String os = System.getProperty("os.name").toLowerCase();
        String libName;
        String resourcePath;

        if (os.contains("win")) {
            libName = "coderec_jni.dll";
        } else if (os.contains("mac")) {
            libName = "coderec_jni.dylib";
        } else {
            libName = "libcoderec_jni.so";
        }
        resourcePath = "/coderec/" + libName;

        try (InputStream in = CoderecJni.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Native lib not found: " + resourcePath);
            }
            Path tmp = Files.createTempFile("coderec_jni-", libName);
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().deleteOnExit();
            System.load(tmp.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load native lib: " + resourcePath, e);
        }
    }

    /**
     * Analyzes the given ELF binary file using the native coderec algorithm.
     *
     * @param path the filesystem path to the ELF binary
     * @return a JSON string containing coderec’s range_results, or <code>null</code> if analysis fails
     */
    public native String detectFile(String path);
}
