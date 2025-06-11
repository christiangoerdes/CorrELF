package com.goerdes.correlf;

import com.goerdes.correlf.components.CoderecParser;
import com.goerdes.correlf.components.ElfHandler;
import com.goerdes.correlf.components.MinHashProvider;
import com.goerdes.correlf.model.ElfWrapper;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

import static com.goerdes.correlf.TestUtils.getMockFile;

@SpringBootTest
@RequiredArgsConstructor
public class ElfHandlerTest {

    private final CoderecParser coderecParser;

    @Test
    void testEntityCreation() throws IOException {
        System.out.println(new ElfHandler(new MinHashProvider()).createEntity(ElfWrapper.of(
                getMockFile("busybox"),
                coderecParser)
        ));
    }

}
