package com.aistudio.production.contract;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.List;

import com.aistudio.production.contract.MediaEffectPort.Payload;
import com.aistudio.production.contract.MediaStageWork.Context;
import com.aistudio.production.contract.MediaStore.FileIdentity;
import com.aistudio.production.contract.MediaStore.Role;

/** Fenced local output and verified input access for the media product worker. */
public interface MediaAssets {
    record Stored(long id, Role role, String mimeType, FileIdentity identity) {}

    void publish(Context context, List<Payload> payloads) throws IOException;

    List<Stored> inputs(Context context, ProductionStage stage, Role role);

    FileChannel open(Context context, Stored asset) throws IOException;
}
