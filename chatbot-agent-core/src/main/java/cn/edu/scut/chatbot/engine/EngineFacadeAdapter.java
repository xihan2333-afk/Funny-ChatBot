package cn.edu.scut.chatbot.engine;

import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.engine.lifecycle.GroupLifecycleManager;
import cn.edu.scut.chatbot.engine.outbox.OutboxWriter;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.engine.task.TaskCoordinator;
import cn.edu.scut.chatbot.engine.task.TaskDO;
import cn.edu.scut.chatbot.engine.task.TaskMapper;
import cn.edu.scut.chatbot.engine.turn.TurnCoordinator;
import cn.edu.scut.chatbot.management.EngineCommandFacades;
import cn.edu.scut.chatbot.management.EngineViews;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 管理门面适配装配（T102）：engine 实现全部 management 门面接口——命令路由到各组件，
 * 视图转换在此完成（engine → management 视图 record；依赖方向 engine → management 合法）。
 */
@Configuration
public class EngineFacadeAdapter {

    @Bean
    EngineCommandFacades.EngineTurnCommands engineTurnCommands(TurnCoordinator turnCoordinator) {
        return new EngineCommandFacades.EngineTurnCommands() {
            @Override
            public long acceptTurn(String platform, String botId, String groupId, long triggerId) {
                return turnCoordinator.acceptTurn(platform, botId, groupId, triggerId);
            }

            @Override
            public void generationCompleted(long turnId, List<Long> speechOutboxIds) {
                turnCoordinator.generationCompleted(turnId, speechOutboxIds);
            }

            @Override
            public void generationFailed(long turnId, String errorCode) {
                turnCoordinator.generationFailed(turnId, errorCode);
            }

            @Override
            public void dropTurn(long turnId, String reason) {
                turnCoordinator.dropTurn(turnId, reason);
            }
        };
    }

    @Bean
    EngineCommandFacades.EngineTaskCommands engineTaskCommands(TaskCoordinator taskCoordinator) {
        return new EngineCommandFacades.EngineTaskCommands() {
            @Override
            public long createTask(EngineViews.TaskCreation creation) {
                TaskDO task = new TaskDO();
                task.setPlatform(creation.platform());
                task.setBotId(creation.botId());
                task.setGroupId(creation.groupId());
                task.setTaskKind(creation.taskKind());
                task.setCategory(creation.category());
                task.setMandateSource(creation.mandateSource());
                task.setMandateRef(creation.mandateRef());
                task.setCreatorUserId(creation.creatorUserId());
                task.setSnapshot(creation.snapshot());
                if (creation.scheduleAtIso() != null) {
                    task.setScheduleAt(java.time.LocalDateTime.parse(creation.scheduleAtIso()));
                }
                return taskCoordinator.createTask(task);
            }

            @Override
            public void advanceTask(long taskId) {
                taskCoordinator.advanceTask(taskId);
            }

            @Override
            public void stopTask(long taskId, String reason) {
                taskCoordinator.stopTask(taskId, reason);
            }

            @Override
            public void taskProgress(long taskId, String note) {
                taskCoordinator.taskProgress(taskId, note);
            }

            @Override
            public void taskConcluded(long taskId, String resultJson, String errorCode) {
                taskCoordinator.taskConcluded(taskId, resultJson, errorCode);
            }

            @Override
            public int revalidateMandates(String platform, String botId, String groupId) {
                return taskCoordinator.revalidateMandates(platform, botId, groupId);
            }
        };
    }

    @Bean
    EngineCommandFacades.EngineSessionCommands engineSessionCommands(SessionManager sessionManager) {
        return new EngineCommandFacades.EngineSessionCommands() {
            @Override
            public Object acquireInference(String platform, String botId, String groupId, String purpose) {
                return sessionManager.acquireInference(platform, botId, groupId, purpose);
            }

            @Override
            public void releaseInference(Object ticket) {
                sessionManager.releaseInference((SessionManager.InferenceTicket) ticket);
            }
        };
    }

    @Bean
    EngineCommandFacades.EngineOutboxCommands engineOutboxCommands(OutboxWriter outboxWriter) {
        return new EngineCommandFacades.EngineOutboxCommands() {
            @Override
            public long enqueueOutbound(String platform, String botId, String groupId, String type,
                                        String payloadJson, Long turnId, Long taskId) {
                return outboxWriter.enqueueOutbound(platform, botId, groupId, type,
                        payloadJson, turnId, taskId);
            }

            @Override
            public void outboxSettled(long outboxId, String status, String reportJson, String errorCode) {
                outboxWriter.settleDeliveryStatus(outboxId, status, reportJson, errorCode);
            }
        };
    }

    @Bean
    EngineCommandFacades.EngineLifecycleCommands engineLifecycleCommands(GroupLifecycleManager lifecycleManager,
                                                                         GroupMapper groupMapper) {
        return new EngineCommandFacades.EngineLifecycleCommands() {
            @Override
            public void discoverGroup(String platform, String botId, String groupId, String groupName) {
                groupMapper.upsertDiscovered(platform, botId, groupId, groupName);
            }

            @Override
            public void pauseGroup(String platform, String botId, String groupId) {
                lifecycleManager.pauseGroup(platform, botId, groupId);
            }

            @Override
            public void resumeGroup(String platform, String botId, String groupId) {
                lifecycleManager.resumeGroup(platform, botId, groupId);
            }

            @Override
            public void destroyGroup(String platform, String botId, String groupId) {
                lifecycleManager.destroyGroup(platform, botId, groupId);
            }
        };
    }

    @Bean
    EngineCommandFacades.EngineTaskQueries engineTaskQueries(TaskMapper taskMapper) {
        return new EngineCommandFacades.EngineTaskQueries() {
            @Override
            public List<EngineViews.TaskView> queryTasks(String platform, String botId, String groupId) {
                return taskMapper.selectByGroup(platform, botId, groupId).stream()
                        .map(EngineFacadeAdapter::taskView)
                        .toList();
            }

            @Override
            public EngineViews.TaskView queryTask(long taskId) {
                TaskDO task = taskMapper.selectById(taskId);
                return task == null ? null : taskView(task);
            }
        };
    }

    @Bean
    EngineCommandFacades.EngineDecisionQueries engineDecisionQueries(TriggerEventMapper triggerEventMapper) {
        return new EngineCommandFacades.EngineDecisionQueries() {
            @Override
            public List<EngineViews.TriggerEventView> queryDecisions(
                    String platform, String botId, String groupId) {
                return triggerEventMapper.selectAllByGroup(platform, botId, groupId).stream()
                        .map(EngineFacadeAdapter::triggerView)
                        .toList();
            }

            @Override
            public EngineViews.TriggerEventView queryDecision(long triggerId) {
                var trigger = triggerEventMapper.selectById(triggerId);
                return trigger == null ? null : triggerView(trigger);
            }
        };
    }

    @Bean
    EngineCommandFacades.EngineSessionQueries engineSessionQueries(AgentSessionMapper sessionMapper) {
        return new EngineCommandFacades.EngineSessionQueries() {
            @Override
            public EngineViews.SessionView querySession(String platform, String botId, String groupId) {
                var session = sessionMapper.selectByGroup(platform, botId, groupId);
                return session == null ? null : new EngineViews.SessionView(
                        session.getId(), session.getGroupId(), session.getLifecycle(),
                        session.getWindowLevel(), session.getEmotion(), session.getSummaryEntryId());
            }

            @Override
            public List<EngineViews.TranscriptEntryView> queryTranscript(long sessionId) {
                return sessionMapper.selectOrdered(sessionId).stream()
                        .map(entry -> new EngineViews.TranscriptEntryView(
                                entry.getId(), entry.getSessionId(), entry.getKind(),
                                entry.getPayload() != null && entry.getPayload().length() > 200
                                        ? entry.getPayload().substring(0, 200) + "…" : entry.getPayload(),
                                entry.getWeight(), entry.getRunId()))
                        .toList();
            }
        };
    }

    private static EngineViews.TaskView taskView(TaskDO task) {
        return new EngineViews.TaskView(task.getId(), task.getGroupId(), task.getTaskKind(),
                task.getCategory(), task.getStatus(), task.getMandateSource(),
                task.getScheduleAt() != null ? task.getScheduleAt().toString() : null,
                task.getResult(), task.getErrorCode());
    }

    private static EngineViews.TriggerEventView triggerView(
            cn.edu.scut.chatbot.engine.intake.TriggerEventDO trigger) {
        return new EngineViews.TriggerEventView(trigger.getId(), trigger.getGroupId(),
                trigger.getTriggerKind(), trigger.getEventType(), trigger.getDedupKey(),
                trigger.getDecision(), trigger.getDecisionLayer(), trigger.getDecisionReason(),
                trigger.getTurnId());
    }
}
