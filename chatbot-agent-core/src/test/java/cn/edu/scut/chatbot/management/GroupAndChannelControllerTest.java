package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.engine.intake.*;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.llm.slot.CredentialDO;
import cn.edu.scut.chatbot.llm.slot.CredentialMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GroupAndChannelControllerTest {

    @Test
    void channelCreateEncryptsAndMasksCredentialAndBlocksDeleteWhenActiveGroupExists() {
        AccessChannelMapper channelMapper = mock(AccessChannelMapper.class);
        GroupMapper groupMapper = mock(GroupMapper.class);
        CredentialMapper credentialMapper = mock(CredentialMapper.class);
        CredentialCodec codec = new CredentialCodec("12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        when(channelMapper.selectByPlatformBot("onebot-v11", "bot-100")).thenReturn(null);
        doAnswer(inv -> {
            CredentialDO cred = inv.getArgument(0);
            cred.setId(99L);
            return 1;
        }).when(credentialMapper).insertCredential(any());
        doAnswer(inv -> {
            AccessChannelDO ch = inv.getArgument(0);
            ch.setId(1L);
            return 1;
        }).when(channelMapper).insert(any());

        AccessChannelDO storedChannel = new AccessChannelDO();
        storedChannel.setId(1L);
        storedChannel.setPlatform("onebot-v11");
        storedChannel.setBotId("bot-100");
        storedChannel.setDisplayName("测试Bot");
        storedChannel.setTransportType("ws_client");
        storedChannel.setCredentialId(99L);
        storedChannel.setEnabled(0);
        when(channelMapper.selectById(1L)).thenReturn(storedChannel);

        CredentialDO storedCred = new CredentialDO();
        storedCred.setId(99L);
        storedCred.setKeyMask("tok***xyz");
        storedCred.setStatus("available");
        when(credentialMapper.selectById(99L)).thenReturn(storedCred);

        AccessChannelService service = new AccessChannelService(
                channelMapper, groupMapper, credentialMapper, codec, publisher);
        AccessChannelController controller = new AccessChannelController(service);

        var created = controller.create(
                new AccessChannelFacade.ChannelUpsertRequest(
                        "onebot-v11", "bot-100", "测试Bot", "ws_client",
                        "ws://napcat:3001/", null, "token-secret-xyz", true),
                new org.springframework.mock.web.MockHttpServletRequest()
        );
        assertThat(created.maskedCredential()).isEqualTo("tok***xyz");

        GroupDO activeGroup = new GroupDO();
        activeGroup.setPlatform("onebot-v11");
        activeGroup.setBotId("bot-100");
        activeGroup.setGroupId("g-1");
        activeGroup.setLifecycle(GroupDO.LIFECYCLE_ACTIVE);
        when(groupMapper.selectAll()).thenReturn(List.of(activeGroup));

        assertThatThrownBy(() -> controller.delete(1L, new org.springframework.mock.web.MockHttpServletRequest()))
                .isInstanceOf(CommandRejectedException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void groupDestroyRequiresExactConfirmGroupId() {
        EngineCommandFacades.EngineGroupAdminFacade facade = mock(EngineCommandFacades.EngineGroupAdminFacade.class);
        EngineCommandFacades.EngineLifecycleCommands lifecycle = mock(EngineCommandFacades.EngineLifecycleCommands.class);
        GroupAdminController controller = new GroupAdminController(facade, lifecycle);

        assertThatThrownBy(() -> controller.destroy(
                "onebot-v11", "10001", "20001",
                new GroupAdminController.DestroyGroupRequest(true, "wrong-id"),
                new org.springframework.mock.web.MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmGroupId");

        var resp = controller.destroy(
                "onebot-v11", "10001", "20001",
                new GroupAdminController.DestroyGroupRequest(true, "20001"),
                new org.springframework.mock.web.MockHttpServletRequest());
        assertThat(resp.destroyed()).isTrue();
        assertThat(resp.notice()).contains("永久保留");
        verify(facade).updateTranscriptRetainPolicy("onebot-v11", "10001", "20001", true);
        verify(lifecycle).destroyGroup("onebot-v11", "10001", "20001");
    }
}
