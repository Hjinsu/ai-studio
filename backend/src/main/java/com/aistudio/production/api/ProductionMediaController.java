package com.aistudio.production.api;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aistudio.production.contract.MediaMime;
import com.aistudio.production.contract.MediaStore.Attempt;
import com.aistudio.production.contract.MediaStore.FileIdentity;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.media.storage.WorkspaceMediaStore;
import com.aistudio.security.CurrentActor;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ApiId;
import com.aistudio.shared.api.ErrorCode;

@RestController
@RequestMapping("/api/v1/production-requests/{id}/media")
class ProductionMediaController {
    private static final Logger log = LoggerFactory.getLogger(ProductionMediaController.class);

    record Asset(String id, ProductionStage stage, String role, String contentType, String byteLength, String sha256) {}
    record Qc(String status) {}
    record Media(String mode, Qc qc, List<Asset> assets) {}

    private final ProductionRequestMapper requests;
    private final MediaReadMapper media;
    private final String storageRoot;

    ProductionMediaController(ProductionRequestMapper requests, MediaReadMapper media,
            @Value("${ai-studio.production.media.storage-root:}") String storageRoot) {
        this.requests = requests;
        this.media = media;
        this.storageRoot = storageRoot;
    }

    @GetMapping
    Media list(@PathVariable @Pattern(regexp = ApiId.PATTERN) String id) {
        var request = owner(id);
        return new Media("FAKE", new Qc(media.qcStatus(request.id())),
                request.runId() == null ? List.of() : media.list(request.id()).stream()
                        .filter(asset -> MediaMime.accepts(asset.role(), asset.mimeType()))
                        .map(asset -> new Asset(Long.toString(asset.id()), asset.stage(), asset.role().name(),
                                asset.mimeType(), Long.toString(asset.byteLength()), asset.sha256()))
                        .toList());
    }

    @RequestMapping(path = "/{assetId}", method = {RequestMethod.GET, RequestMethod.HEAD})
    void read(@PathVariable @Pattern(regexp = ApiId.PATTERN) String id,
            @PathVariable @Pattern(regexp = ApiId.PATTERN) String assetId,
            @RequestParam(defaultValue = "false") boolean download, HttpServletRequest input,
            HttpServletResponse response) throws IOException {
        var request = owner(id);
        long assetKey = ApiId.parse("assetId", assetId);
        var asset = media.find(request.id(), assetKey);
        if (asset == null || request.contentId() == null || request.runId() == null || storageRoot.isBlank()
                || !MediaMime.accepts(asset.role(), asset.mimeType())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        var attempt = new Attempt(request.channelId(), request.contentId(), request.runId(), asset.itemId(),
                asset.attemptNo());
        if (!attempt.relativePath(asset.role()).equals(asset.relativePath())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        var identity = new FileIdentity(asset.byteLength(), asset.sha256());
        java.nio.channels.FileChannel channel;
        try {
            channel = new WorkspaceMediaStore(Path.of(storageRoot)).openVerified(attempt, asset.role(), identity);
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("Media asset {} failed storage verification", assetKey);
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        try (channel) {
            boolean head = "HEAD".equals(input.getMethod());
            var range = MediaByteRange.parse(head || input.getHeader("If-Range") != null
                    ? null : input.getHeader("Range"), identity.bytes());
            response.setStatus(range.status());
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Cache-Control", "private, no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            if (range.status() == 416) {
                response.setHeader("Content-Range", "bytes */" + identity.bytes());
                response.setContentLengthLong(0);
                return;
            }
            if (range.status() == 206) {
                response.setHeader("Content-Range",
                        "bytes " + range.start() + "-" + range.end() + "/" + identity.bytes());
            }
            response.setContentType(asset.mimeType());
            response.setContentLengthLong(range.count());
            if (download) {
                response.setHeader("Content-Disposition", "attachment; filename=\"asset-" + assetKey
                        + extension(asset.mimeType()) + "\"");
            }
            if (!head) {
                channel.position(range.start());
                byte[] buffer = new byte[64 * 1024];
                var output = response.getOutputStream();
                for (long remaining = range.count(); remaining > 0;) {
                    int n = channel.read(ByteBuffer.wrap(buffer, 0, (int) Math.min(remaining, buffer.length)));
                    if (n < 0) {
                        throw new IOException("Media asset shortened during streaming");
                    }
                    output.write(buffer, 0, n);
                    remaining -= n;
                }
            }
        }
    }

    private ProductionRequestMapper.Request owner(String id) {
        var request = requests.byId(ApiId.parse("id", id));
        if (request == null || request.requestedBy() != CurrentActor.principal().id()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return request;
    }

    private static String extension(String mimeType) {
        return switch (mimeType) {
            case "video/mp4" -> ".mp4";
            case "audio/wav", "audio/wave" -> ".wav";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "application/x-subrip", "text/plain" -> ".srt";
            case "application/json" -> ".json";
            default -> ".bin";
        };
    }
}
