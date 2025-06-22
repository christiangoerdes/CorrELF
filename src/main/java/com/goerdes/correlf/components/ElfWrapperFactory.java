package com.goerdes.correlf.components;

import com.goerdes.correlf.exception.FileProcessingException;
import com.goerdes.correlf.model.ElfWrapper;
import com.goerdes.correlf.model.RepresentationType;
import com.goerdes.correlf.utils.ByteUtils;
import lombok.RequiredArgsConstructor;
import net.fornwall.jelf.ElfFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.goerdes.correlf.model.RepresentationType.CODE_REGION_LIST;
import static com.goerdes.correlf.utils.StringsUtil.strings;

/**
 * Factory that builds {@link ElfWrapper} instances, optionally running the
 * JNA‐backed coderec detection and parsing its JSON output.
 */
@Component
@RequiredArgsConstructor
public class ElfWrapperFactory {

    private final Coderec coderec;

    /**
     * Toggle in application.properties (default=false)
     */
    @Value("${coderec.enabled:false}")
    private boolean coderecEnabled;

    public ElfWrapper create(MultipartFile file) {
        return create(file, List.of());
    }

    public ElfWrapper create(MultipartFile file, List<RepresentationType> representationTypes) {
        try {
            String filename = file.getOriginalFilename();
            if (filename == null) {
                throw new FileProcessingException("Missing original filename", null);
            }

            byte[] content = file.getBytes();
            String sha256 = ByteUtils.computeSha256(content);

            ElfFile elfFile = null;
            boolean parsed = true;
            try {
                elfFile = ElfFile.from(content);
            } catch (Exception ignored) {
                parsed = false;
            }
            long size = file.getSize();

            Path tmpDir = Files.createTempDirectory("elf-");
            Path tmpFile = tmpDir.resolve(filename);
            Files.write(tmpFile, content, StandardOpenOption.CREATE);

            List<String> strings = strings(tmpFile);

            List<Coderec.CodeRegion> regions = new ArrayList<>();
            if (coderecEnabled && (representationTypes.contains(CODE_REGION_LIST) || representationTypes.isEmpty())) {
                regions = coderec.analyze(tmpFile);
            }
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });

            return new ElfWrapper(filename, elfFile, parsed, sha256, size, strings, regions);

        } catch (IOException e) {
            throw new FileProcessingException("I/O error during ELF wrap: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new FileProcessingException("Failed to wrap ELF: " + e.getMessage(), e);
        }
    }
}
