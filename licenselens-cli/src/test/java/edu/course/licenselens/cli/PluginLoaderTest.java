package edu.course.licenselens.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnEmptyWhenPluginDirMissing() {
        try (PluginLoader loader = new PluginLoader()) {
            assertTrue(loader.loadAll(tempDir.resolve("missing")).isEmpty());
        }
    }

    @Test
    void shouldIgnoreFilesWithoutJarSuffix() throws IOException {
        Files.writeString(tempDir.resolve("notes.txt"), "not a jar", StandardCharsets.UTF_8);

        try (PluginLoader loader = new PluginLoader()) {
            assertTrue(loader.loadAll(tempDir).isEmpty());
        }
    }

    @Test
    void shouldSkipCorruptJar() throws IOException {
        Files.write(tempDir.resolve("broken.jar"), "not a zip".getBytes(StandardCharsets.UTF_8));

        try (PluginLoader loader = new PluginLoader()) {
            assertTrue(loader.loadAll(tempDir).isEmpty());
        }
    }

    @Test
    void shouldSkipJarWithBrokenServiceEntry() throws IOException {
        Path jar = tempDir.resolve("bad-service.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(
                    "META-INF/services/edu.course.licenselens.spi.DependencySource"));
            out.write("no.such.PluginImpl\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        try (PluginLoader loader = new PluginLoader()) {
            assertTrue(loader.loadAll(tempDir).isEmpty());
        }
    }
}
