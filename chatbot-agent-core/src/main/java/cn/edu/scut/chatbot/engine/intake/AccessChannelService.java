package cn.edu.scut.chatbot.engine.intake;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.exception.ChatBotException;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.infrastructure.eventbus.ConfigRefreshedEvent;
import cn.edu.scut.chatbot.llm.slot.CredentialDO;
import cn.edu.scut.chatbot.llm.slot.CredentialMapper;
import cn.edu.scut.chatbot.management.AccessChannelFacade;
import cn.edu.scut.chatbot.management.OverviewController;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 接入渠道与 Bot 实例管理实现：平台凭证统一走 CredentialCodec 密文落库、界面脱敏展示；
 * 删除渠道前校验无 ACTIVE 群。
 */
@Service
public class AccessChannelService implements AccessChannelFacade, OverviewController.OverviewExtensionStats {

    private final AccessChannelMapper channelMapper;
    private final GroupMapper groupMapper;
    private final CredentialMapper credentialMapper;
    private final CredentialCodec credentialCodec;
    private final ApplicationEventPublisher eventPublisher;

    public AccessChannelService(AccessChannelMapper channelMapper,
                                GroupMapper groupMapper,
                                CredentialMapper credentialMapper,
                                @org.springframework.lang.Nullable CredentialCodec credentialCodec,
                                ApplicationEventPublisher eventPublisher) {
        this.channelMapper = channelMapper;
        this.groupMapper = groupMapper;
        this.credentialMapper = credentialMapper;
        this.credentialCodec = credentialCodec;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Boolean hasEnabledPlatformChannel() {
        return channelMapper.countEnabled() > 0;
    }

    @Override
    public List<ChannelView> listAll() {
        return channelMapper.selectAll().stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public ChannelView create(ChannelUpsertRequest request) {
        if (request == null || request.botId() == null || request.botId().isBlank()) {
            throw new IllegalArgumentException("botId 不能为空");
        }
        String platform = (request.platform() == null || request.platform().isBlank())
                ? "onebot-v11" : request.platform().trim();
        String botId = request.botId().trim();
        if (channelMapper.selectByPlatformBot(platform, botId) != null) {
            throw new CommandRejectedException("该平台与 botId 组合已存在：" + platform + "/" + botId);
        }

        Long credId = request.credentialId();
        if (request.credentialSecret() != null && !request.credentialSecret().isBlank()) {
            credId = createPlatformCredential(botId, request.credentialSecret().trim());
        }

        AccessChannelDO row = new AccessChannelDO();
        row.setPlatform(platform);
        row.setBotId(botId);
        row.setDisplayName(request.displayName() != null && !request.displayName().isBlank()
                ? request.displayName().trim() : botId);
        row.setTransportType(request.transportType() != null && !request.transportType().isBlank()
                ? request.transportType().trim() : "ws_client");
        row.setEndpointUrl(request.endpointUrl());
        row.setCredentialId(credId);
        row.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
        channelMapper.insert(row);
        eventPublisher.publishEvent(new ConfigRefreshedEvent("access_channel", "新增接入渠道 " + botId));
        return toView(channelMapper.selectById(row.getId()));
    }

    @Override
    @Transactional
    public ChannelView update(long id, ChannelUpsertRequest request) {
        AccessChannelDO existing = channelMapper.selectById(id);
        if (existing == null) {
            throw new NoSuchElementException("接入渠道不存在 id=" + id);
        }
        if (request.displayName() != null && !request.displayName().isBlank()) {
            existing.setDisplayName(request.displayName().trim());
        }
        if (request.transportType() != null && !request.transportType().isBlank()) {
            existing.setTransportType(request.transportType().trim());
        }
        if (request.endpointUrl() != null) {
            existing.setEndpointUrl(request.endpointUrl());
        }
        if (request.credentialSecret() != null && !request.credentialSecret().isBlank()) {
            existing.setCredentialId(createPlatformCredential(existing.getBotId(), request.credentialSecret().trim()));
        } else if (request.credentialId() != null) {
            existing.setCredentialId(request.credentialId());
        }
        if (request.enabled() != null) {
            existing.setEnabled(request.enabled() ? 1 : 0);
        }
        channelMapper.update(existing);
        eventPublisher.publishEvent(new ConfigRefreshedEvent("access_channel", "更新接入渠道 " + existing.getBotId()));
        return toView(channelMapper.selectById(id));
    }

    @Override
    @Transactional
    public void delete(long id) {
        AccessChannelDO existing = channelMapper.selectById(id);
        if (existing == null) {
            throw new ChatBotException("mgmt.not_found", "接入渠道不存在 id=" + id);
        }
        if (existing.getEnabled() != null && existing.getEnabled() == 1) {
            throw new CommandRejectedException("请先停用该接入渠道后再删除");
        }
        boolean hasActiveGroup = groupMapper.selectAll().stream()
                .anyMatch(g -> existing.getPlatform().equals(g.getPlatform())
                        && existing.getBotId().equals(g.getBotId())
                        && GroupDO.LIFECYCLE_ACTIVE.equals(g.getLifecycle()));
        if (hasActiveGroup) {
            throw new CommandRejectedException("该 Bot 下仍有 ACTIVE 状态群聊，请先暂停或销毁相关群后再删除渠道");
        }
        channelMapper.deleteById(id);
        eventPublisher.publishEvent(new ConfigRefreshedEvent("access_channel", "删除接入渠道 " + existing.getBotId()));
    }

    private Long createPlatformCredential(String botId, String plainSecret) {
        CredentialDO cred = new CredentialDO();
        cred.setOwnerKind("platform");
        cred.setOwnerCode(botId);
        if (credentialCodec == null) {
            throw new CommandRejectedException("未配置凭证加密主密钥，无法保存平台凭证");
        }
        cred.setKeyCipher(java.util.Base64.getDecoder().decode(credentialCodec.encrypt(plainSecret)));
        cred.setKeyMask(maskSecret(plainSecret));
        cred.setStatus("available");
        credentialMapper.insertCredential(cred);
        return cred.getId();
    }

    private ChannelView toView(AccessChannelDO row) {
        String masked = null;
        String credStatus = null;
        if (row.getCredentialId() != null) {
            CredentialDO cred = credentialMapper.selectById(row.getCredentialId());
            if (cred != null) {
                masked = cred.getKeyMask();
                credStatus = cred.getStatus();
            }
        }
        return new ChannelView(
                row.getId(),
                row.getPlatform(),
                row.getBotId(),
                row.getDisplayName(),
                row.getTransportType(),
                row.getEndpointUrl(),
                row.getCredentialId(),
                masked,
                credStatus,
                row.getEnabled() != null && row.getEnabled() == 1,
                row.getUpdatedAt() != null ? row.getUpdatedAt().toString() : null
        );
    }

    static String maskSecret(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "***";
        }
        if (raw.length() <= 6) {
            return "***";
        }
        return raw.substring(0, 3) + "***" + raw.substring(raw.length() - 3);
    }
}
