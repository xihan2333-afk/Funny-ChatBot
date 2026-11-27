package cn.edu.scut.chatbot.management;

import java.util.List;

/**
 * 接入渠道与 Bot 实例管理门面接口（management 收口，实现位于 engine.intake）
 */
public interface AccessChannelFacade {

    List<ChannelView> listAll();

    ChannelView create(ChannelUpsertRequest request);

    ChannelView update(long id, ChannelUpsertRequest request);

    void delete(long id);

    record ChannelView(
            long id,
            String platform,
            String botId,
            String displayName,
            String transportType,
            String endpointUrl,
            Long credentialId,
            String maskedCredential,
            String credentialStatus,
            boolean enabled,
            String updatedAt
    ) {
    }

    record ChannelUpsertRequest(
            String platform,
            String botId,
            String displayName,
            String transportType,
            String endpointUrl,
            Long credentialId,
            String credentialSecret,
            Boolean enabled
    ) {
    }
}
