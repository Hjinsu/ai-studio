package com.aistudio.production.media;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.aistudio.production.contract.MediaProcessRunner;
import com.aistudio.production.contract.MediaProfile;
import com.aistudio.production.contract.MediaStageWork;
import com.aistudio.production.media.storage.WorkspaceMediaStore;
import com.aistudio.production.planning.PlanningAvailability;

@Component
public class MediaAvailability {
    private final boolean enabled;
    private final String root;
    private final String catalog;
    private final String voice;
    private final PlanningAvailability planning;
    private final ObjectProvider<MediaProcessRunner> runner;
    private final ObjectProvider<MediaStageWork> stages;

    public MediaAvailability(@Value("${ai-studio.production.media.enabled:false}") boolean enabled,
            @Value("${ai-studio.production.media.storage-root:}") String root,
            @Value("${ai-studio.production.media.fixture-catalog:}") String catalog,
            @Value("${ai-studio.production.media.voice-id:}") String voice,
            PlanningAvailability planning, ObjectProvider<MediaProcessRunner> runner,
            ObjectProvider<MediaStageWork> stages) {
        this.enabled = enabled;
        this.root = root;
        this.catalog = catalog;
        this.voice = voice;
        this.planning = planning;
        this.runner = runner;
        this.stages = stages;
    }

    public boolean ready() {
        if (!enabled || root.isBlank() || catalog.isBlank() || voice.isBlank() || !planning.ready()
                || stages.getIfAvailable() == null) {
            return false;
        }
        MediaProcessRunner process = runner.getIfAvailable();
        if (process == null || !process.ready()) {
            return false;
        }
        try {
            new WorkspaceMediaStore(Path.of(root));
            return true;
        } catch (IOException | IllegalArgumentException ex) {
            return false;
        }
    }

    public MediaProfile snapshot(long configId, String language) {
        return MediaProfile.localFixture(configId, language, catalog, voice);
    }
}
