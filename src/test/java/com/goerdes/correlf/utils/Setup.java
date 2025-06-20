package com.goerdes.correlf.utils;

import com.goerdes.correlf.services.FileAnalysisService;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class Setup {

    protected static FileAnalysisService fileAnalysisService;

    @BeforeAll
    static void setupData(@Autowired FileAnalysisService service) {
        fileAnalysisService = service;
    }

}
