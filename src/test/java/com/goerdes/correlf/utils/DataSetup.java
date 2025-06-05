package com.goerdes.correlf.utils;

import com.goerdes.correlf.services.FileAnalysisService;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;

@SpringBootTest
public class DataSetup {

    protected static FileAnalysisService fileAnalysisService;

    /**
     * Ingests the ZIP before any tests run.
     * Uses parameter injection to get the Spring bean into this static method.
     */
    @BeforeAll
    static void setupData(@Autowired FileAnalysisService service) throws IOException {
        fileAnalysisService = service;
        fileAnalysisService.importZipArchive(new MockMultipartFile(
                "file",
                "all_elfs.zip",
                "application/zip",
                new ClassPathResource("all_elfs.zip").getInputStream()
        ));
    }

}
