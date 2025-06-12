package com.goerdes.correlf.components;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goerdes.correlf.exception.FileProcessingException;
import com.sun.jna.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * JNA binding for coderec_jni.dll, loaded from application.properties
 */
@Component
public class Coderec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    /** Returns parsed regions for a single file. */
    public List<CodeRegion> analyze(Path elfPath) {
        String json = detectFile(elfPath.toString());
        if (json == null) {
            throw new FileProcessingException("coderec failed to analyze " + elfPath, null);
        }
        return extractRegions(parseRawJson(json));
    }

    /**
     * Analyze a single file. Returns JSON string or null on error.
     */
    private String detectFile(String filePath) {
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

    /** Deserialize a single JSON blob into a raw Map. */
    private Map<String, Object> parseRawJson(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new FileProcessingException("Error reading json", null);
        }
    }

    /** Convert raw JSON map into a List<CodeRegion>. */
    @SuppressWarnings("unchecked")
    private List<CodeRegion> extractRegions(Map<String,Object> raw) {
        List<List<Object>> rr = (List<List<Object>>) raw.get("range_results");
        if (rr == null) throw new FileProcessingException("missing range_results", null);

        return rr.stream().map(entry -> {
            var coords = (Map<String,Object>) entry.getFirst();
            return new CodeRegion(
                    ((Number) coords.get("start")).longValue(),
                    ((Number) coords.get("end")).longValue(),
                    ((Number) entry.get(1)).longValue(), (String) entry.get(2)
            );
        }).toList();
    }

    /**
     * Represents a region in the binary that coderec classified,
     * with start, end, byte-length, and the tag
     */
    public record CodeRegion(long start, long end, long length, String tag) {}
}
