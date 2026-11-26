package com.aistudio.production.api;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.channel.Channel;
import com.aistudio.channel.ChannelService;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.contract.ProductionStatus;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.db.BudgetControlLock;
import com.aistudio.shared.db.LockRetryingTransactions;

@Service
class ProductionRequestService {
    record Created(ProductionRequestMapper.Request request, boolean replayed) {}

    private final ProductionRequestMapper mapper;
    private final ChannelService channels;
    private final BudgetControlLock control;
    private final LockRetryingTransactions transactions;

    ProductionRequestService(ProductionRequestMapper mapper, ChannelService channels,
            BudgetControlLock control, LockRetryingTransactions transactions) {
        this.mapper = mapper;
        this.channels = channels;
        this.control = control;
        this.transactions = transactions;
    }

    Created create(long actorId, long channelId, String type, String key) {
        String hash = fingerprint(actorId, channelId, type);
        try {
            return transactions.execute(() -> {
                ProductionRequestMapper.Request previous = mapper.byKey(key);
                if (previous != null) {
                    return replay(previous, hash);
                }
                control.shared();
                Channel channel = channels.lockShared(channelId);
                if (!"ACTIVE".equals(channel.status())) {
                    throw new ApiException(ErrorCode.POLICY_VIOLATION, "Channel is not active.");
                }
                BigDecimal limit = mapper.typeLimit(channelId, type);
                if (limit == null) {
                    throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET, "An active video budget is required.");
                }
                long configId = channels.latestConfigInTransaction(channelId).id();
                mapper.insert(channelId, configId, actorId, type, key, hash, limit);
                ProductionRequestMapper.Request request = mapper.byKey(key);
                for (ProductionStage stage : ProductionStage.values()) {
                    mapper.addStep(request.id(), stage);
                }
                return new Created(request, false);
            });
        } catch (DuplicateKeyException collision) {
            ProductionRequestMapper.Request previous = mapper.byKey(key);
            if (previous == null) {
                throw collision;
            }
            return replay(previous, hash);
        }
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ProductionRequestController.Detail get(long actorId, long id) {
        return detail(requireOwner(mapper.byId(id), actorId));
    }

    ProductionRequestController.Detail cancel(long actorId, long id) {
        return transactions.execute(() -> {
            ProductionRequestMapper.Request request = requireOwner(mapper.lock(id), actorId);
            ProductionStatus status = request.status();
            if (request.cancelRequestedAt() == null && status != ProductionStatus.SUCCEEDED
                    && status != ProductionStatus.FAILED && status != ProductionStatus.CANCELLED) {
                mapper.requestCancel(id);
                request = mapper.byId(id);
            }
            return detail(request);
        });
    }

    private ProductionRequestController.Detail detail(ProductionRequestMapper.Request request) {
        var usage = mapper.usage(request.id());
        return new ProductionRequestController.Detail(Long.toString(request.id()), Long.toString(request.channelId()),
                request.contentType(), request.status(), idString(request.contentId()), idString(request.runId()),
                request.cancelRequestedAt(), request.limitKrw().toPlainString(), usage.reservedKrw().toPlainString(),
                usage.recognizedKrw().toPlainString(), mapper.steps(request.id()));
    }

    private static String idString(Long id) {
        return id == null ? null : id.toString();
    }

    private static ProductionRequestMapper.Request requireOwner(ProductionRequestMapper.Request request, long actorId) {
        if (request == null || request.requestedBy() != actorId) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return request;
    }

    private static Created replay(ProductionRequestMapper.Request request, String hash) {
        if (!request.requestHash().equals(hash)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT);
        }
        return new Created(request, true);
    }

    private static String fingerprint(long actorId, long channelId, String type) {
        try {
            byte[] input = (actorId + ":" + channelId + ":" + type).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
