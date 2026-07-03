package com.enterprise.ems.employee.storage;

import com.enterprise.ems.employee.exception.InvalidFileException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileSystemStorageAdapterTest {

    @TempDir
    Path tempDir;

    private StoragePort storage(long maxFileSizeBytes) {
        StorageProperties properties = new StorageProperties(
                tempDir.toString(), maxFileSizeBytes, List.of("application/pdf", "image/png"));
        return new LocalFileSystemStorageAdapter(properties);
    }

    @Test
    void storesAFileUnderARandomNameNeverTheClientSuppliedFilename() throws IOException {
        StoragePort storage = storage(1024);
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        StoredFile stored = storage.store(1L, "../../etc/passwd.pdf", "application/pdf", content.length,
                new ByteArrayInputStream(content));

        assertThat(stored.storagePath()).doesNotContain("..").doesNotContain("passwd");
        assertThat(stored.storagePath()).startsWith("1/").endsWith(".pdf");

        Resource loaded = storage.load(stored.storagePath());
        try (InputStream in = loaded.getInputStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello world");
        }
    }

    @Test
    void rejectsAContentTypeNotOnTheAllowList() {
        StoragePort storage = storage(1024);
        byte[] content = "not a pdf".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> storage.store(1L, "script.sh", "application/x-sh", content.length,
                new ByteArrayInputStream(content)))
                .isInstanceOf(InvalidFileException.class);
    }

    @Test
    void rejectsAFileLargerThanTheConfiguredLimit() {
        StoragePort storage = storage(10);
        byte[] content = "this content is definitely larger than ten bytes".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> storage.store(1L, "big.pdf", "application/pdf", content.length,
                new ByteArrayInputStream(content)))
                .isInstanceOf(InvalidFileException.class);
    }

    @Test
    void deleteActuallyRemovesTheUnderlyingFile() {
        StoragePort storage = storage(1024);
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        StoredFile stored = storage.store(1L, "resume.pdf", "application/pdf", content.length,
                new ByteArrayInputStream(content));

        storage.delete(stored.storagePath());

        assertThat(Files.exists(tempDir.resolve(stored.storagePath()))).isFalse();
    }
}
