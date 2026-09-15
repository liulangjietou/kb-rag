package io.kbrag.app.system;

import io.kbrag.domain.config.KbProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DemoDataDirectoryTest {

    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"", "kb-rag-server", "kb-rag-server/kb-api"})
    void shouldLocateTheSameDemoFromEachLaunchDirectory(String launchDirectory) throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        Path demo = Files.createDirectories(repository.resolve("kb-rag-deploy/demo"));
        Path workingDirectory = Files.createDirectories(repository.resolve(launchDirectory));

        assertEquals(demo, DemoDataDirectory.resolve(new KbProperties().getDemo().getDataDir(),
                workingDirectory));
    }

    @Test
    void shouldHonorExplicitAbsoluteAndRelativePathsEvenWhenMissing() throws IOException {
        Files.createDirectories(temp.resolve("kb-rag-deploy/demo"));
        Path configured = temp.resolve("custom-demo");

        assertEquals(configured, DemoDataDirectory.resolve(configured.toString(), temp));
        assertEquals(configured, DemoDataDirectory.resolve("sub/../custom-demo", temp));
        assertFalse(Files.exists(configured));
    }

    @Test
    void shouldNotReadDemoOutsideTheCurrentWorktree() throws IOException {
        Files.createDirectories(temp.resolve("kb-rag-deploy/demo"));
        Path worktree = Files.createDirectories(temp.resolve("worktree"));
        Files.writeString(worktree.resolve(".git"), "gitdir: ../git/worktrees/task");
        Path workingDirectory = Files.createDirectories(worktree.resolve("kb-rag-server/kb-api"));

        Path resolved = DemoDataDirectory.resolve("", workingDirectory);

        assertEquals(workingDirectory.resolve("kb-rag-deploy/demo"), resolved);
        assertFalse(Files.exists(resolved));
    }
}
