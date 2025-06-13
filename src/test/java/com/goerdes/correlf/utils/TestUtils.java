package com.goerdes.correlf.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;

public class TestUtils {

    public static MockMultipartFile getMockFile(String resourcePath) throws IOException {
        return new MockMultipartFile(
                "file",
                resourcePath,
                "application/octet-stream",
                new ClassPathResource(resourcePath).getInputStream()
        );
    }

}
