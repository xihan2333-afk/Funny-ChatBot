package cn.edu.scut.chatbot.memory.persona;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人格版本服务面（contracts/persona-and-emotion.md §4，T038）：
 * 发布 = persona 主表快照入 content_version（entityKind=persona）+ version_no 递增 +
 * 发 ConfigRefreshEvent 通知刷新（草稿编辑不影响线上，发布后新 Run 生效）；
 * 回滚 = 旧快照写回主表再走一次发布。人格本质是带版本的运营内容——
 * 与 Prompt 片段同一套动作语义，不另造机制（表读写归 PersonaMapper 自带事务）。
 */
@Component
public class PersonaVersioningService {

    private static final Logger log = LoggerFactory.getLogger(PersonaVersioningService.class);

    private final PersonaMapper personaMapper;
    private final ApplicationEventPublisher eventPublisher;

    public PersonaVersioningService(PersonaMapper personaMapper,
                                    ApplicationEventPublisher eventPublisher) {
        this.personaMapper = personaMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 发布：主表现状快照入版本表、version_no 递增、通知刷新。
     * operator / remark 进操作日志（content_version 无对应列——留痕载体是版本快照本身）
     */
    @Transactional
    public void publishPersona(long personaId, String operator, String remark) {
        PersonaDO persona = requirePersona(personaId);
        int nextVersion = nextVersionNo(personaId);
        personaMapper.insertPersonaVersion(personaId, nextVersion, snapshotOf(persona));
        persona.setVersionNo(nextVersion);
        personaMapper.updatePersona(persona);
        eventPublisher.publishEvent(
                new cn.edu.scut.chatbot.tool.registry.ToolRegistry.ConfigRefreshEvent(
                        "persona-published:" + personaId));
        log.info("人格发布 personaId={} version={} operator={} remark={}",
                personaId, nextVersion, operator, remark);
    }

    /**
     * 回滚：旧快照写回主表，再走一次发布（回滚本身也是新版本——历史只增不减）
     */
    @Transactional
    public void rollbackPersona(long personaId, int targetVersion) {
        String snapshot = personaMapper.selectPersonaSnapshot(personaId, targetVersion);
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "目标版本不存在 personaId=" + personaId + " version=" + targetVersion);
        }
        PersonaDO persona = requirePersona(personaId);
        persona.setDefinition(snapshot);
        personaMapper.updatePersona(persona);
        publishPersona(personaId, "rollback", "回滚到版本 " + targetVersion);
        log.info("人格回滚 personaId={} targetVersion={}", personaId, targetVersion);
    }

    private PersonaDO requirePersona(long personaId) {
        for (PersonaDO persona : personaMapper.selectAllPersonas()) {
            if (personaId == persona.getId()) {
                return persona;
            }
        }
        throw new IllegalArgumentException("人格不存在 personaId=" + personaId);
    }

    private int nextVersionNo(long personaId) {
        Integer max = personaMapper.selectMaxPersonaVersion(personaId);
        return max == null ? 1 : max + 1;
    }

    private String snapshotOf(PersonaDO persona) {
        return persona.getDefinition() == null ? "{}" : persona.getDefinition();
    }
}
