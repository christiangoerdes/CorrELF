package com.goerdes.correlf.data;

import com.goerdes.correlf.utils.Setup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;

import static com.goerdes.correlf.model.RepresentationType.NONE;

@SpringBootTest
public class UploadZipArchiveTest extends Setup {

    @Test
    void setupData() throws IOException {
        fileAnalysisService.importZipArchive(new MockMultipartFile(
                "file",
                "all_elfs.zip",
                "application/zip",
                new ClassPathResource("all_elfs.zip").getInputStream()
        ), List.of(NONE));
    }
}
