package com.goerdes.correlf;

import com.goerdes.correlf.components.ElfHandler;
import com.goerdes.correlf.components.ElfWrapperFactory;
import com.goerdes.correlf.components.MinHashProvider;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;

import static com.goerdes.correlf.utils.TestUtils.getMockFile;

@SpringBootTest
@RequiredArgsConstructor
public class ElfHandlerTest {

    ElfWrapperFactory factory;

    @Test
    void testEntityCreation() throws IOException {
        System.out.println(new ElfHandler(new MinHashProvider()).createEntity(factory.create(getMockFile("busybox"), List.of())));
    }

}
