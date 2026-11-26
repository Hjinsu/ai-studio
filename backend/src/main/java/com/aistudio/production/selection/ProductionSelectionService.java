package com.aistudio.production.selection;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.audit.AuditService.Actor;
import com.aistudio.channel.Channel;
import com.aistudio.channel.ChannelService;
import com.aistudio.content.ContentService;
import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionSelectionPort;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.cost.ProductionCostMapper;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.topic.TopicService;

@Service
class ProductionSelectionService implements ProductionSelectionPort {
    private final ProductionCostMapper requests;
    private final ProductionSelectionMapper selections;
    private final ChannelService channels;
    private final TopicService topics;
    private final ContentService contents;

    ProductionSelectionService(ProductionCostMapper requests, ProductionSelectionMapper selections,
            ChannelService channels, TopicService topics, ContentService contents) {
        this.requests = requests;
        this.selections = selections;
        this.channels = channels;
        this.topics = topics;
        this.contents = contents;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Selected link(StepOwner owner, Selection selection) {
        var request = requests.lockRequest(owner.requestId());
        if (request == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        var step = requests.step(owner.requestId(), owner.stepId());
        if (step == null || step.stage() != ProductionStage.SELECT
                || !requests.owns(owner.requestId(), owner.leaseOwner(), owner.leaseEpoch())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "SELECT step ownership is required.");
        }
        if (selection == null || selection.fingerprint() == null || !selection.fingerprint().matches("[0-9a-f]{64}")
                || selection.title() == null || selection.brief() == null) {
            throw ApiException.validation("selection", "a SHA-256 fingerprint, title and brief are required");
        }
        String hash = CanonicalJson.sha256(Map.of("fingerprint", selection.fingerprint(), "title", selection.title(),
                "brief", selection.brief(), "duration", selection.targetDurationSeconds()));
        if (request.selectionHash() != null) {
            if (!hash.equals(request.selectionHash())) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "The selected input changed.");
            }
            return selections.selected(request.id());
        }
        if (request.cancelled() || request.spendingBlocked() || !"RUNNING".equals(request.status())
                || !"RUNNING".equals(step.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "SELECT cannot link this request.");
        }
        Channel channel = channels.lockShared(request.channelId());
        if (!Channel.ACTIVE.equals(channel.status())) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET, "The channel is not active.");
        }
        var config = channels.configSnapshot(request.channelId(), request.channelConfigId());
        Actor actor = Actor.admin(request.requestedBy());
        var topic = topics.findOrCreateSelected(channel.id(), selection.title(), actor);
        var content = contents.createSelected(channel.id(), topic.id(), request.contentType(), config,
                selection.title(), selection.brief(), channel.language(), selection.targetDurationSeconds(), actor);
        long contentId = content.content().id();
        long revisionId = content.currentRevision().id();
        selections.insertRun(contentId, revisionId, request.pipelineVersion(), request.requestedBy());
        long runId = selections.runId(contentId);
        if (!requests.owns(owner.requestId(), owner.leaseOwner(), owner.leaseEpoch())
                || selections.activate(contentId, runId) != 1 || selections.link(request.id(), contentId, runId, hash) != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "SELECT could not link the owned run.");
        }
        return new Selected(topic.id(), contentId, revisionId, runId);
    }
}
