package com.aistudio.production.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aistudio.production.contract.MediaStore.Attempt;
import com.aistudio.production.contract.MediaStore.Role;

class WorkspaceMediaStoreTest {
    @TempDir
    Path root;

    @Test
    void wrongFinalFileCannotBeOverwrittenAndSameFileCanBeReplayed() throws Exception {
        var store = new WorkspaceMediaStore(root.toRealPath());
        var attempt = new Attempt(1, 2, 3, 4, 1);
        var role = Role.MP4;
        var bytes = "expected".getBytes(StandardCharsets.UTF_8);
        var identity = store.stage(attempt, role, new ByteArrayInputStream(bytes), 32);
        var target = root.resolve(attempt.relativePath(role));
        Files.writeString(target, "injected");
        assertThatThrownBy(() -> store.commit(attempt, role, identity)).isInstanceOf(IOException.class);
        assertThat(Files.readString(target)).isEqualTo("injected");
        Files.delete(target);
        store.commit(attempt, role, identity);
        store.commit(attempt, role, identity);
        assertThat(Files.readAllBytes(target)).isEqualTo(bytes);
    }
}
