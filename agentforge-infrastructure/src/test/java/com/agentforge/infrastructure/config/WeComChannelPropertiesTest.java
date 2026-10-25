package com.agentforge.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.channel.wecom.WeComChannelSettings;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeComChannelPropertiesTest {

    @Test
    void disabledChannelNeedsNoConfiguration() {
        new WeComChannelProperties().validate();
    }

    @Test
    void buildsSettingsFromValidConfiguration() {
        WeComChannelProperties properties = valid();

        properties.validate();
        WeComChannelSettings settings = properties.toSettings();

        assertEquals("dev-staff", settings.directUsers().get("zhangsan"));
        assertEquals("wecom-group:cust-a", settings.groupUsers().get("wrkA"));
        assertFalse(settings.allowStaffGroups());
        assertEquals(java.util.Set.of("search_knowledge"), settings.allowedTools());
    }

    @Test
    void groupsMustActAsReservedGroupUsers() {
        WeComChannelProperties properties = valid();
        // Binding a group to a real person would let that person read the group's session.
        properties.setGroups(List.of("wrkA=dev-staff"));

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void directUsersMayNotUseTheGroupPrefix() {
        WeComChannelProperties properties = valid();
        properties.setDirectUsers(List.of("zhangsan=wecom-group:cust-a"));

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void turnsMustEndBeforeWeComStopsPulling() {
        WeComChannelProperties properties = valid();
        properties.setTurnTimeout(Duration.ofMinutes(6));
        assertThrows(IllegalStateException.class, properties::validate);

        // Past the reply window but short of 6 minutes: no turn could be admitted under it.
        properties.setTurnTimeout(Duration.ofMinutes(5).plusSeconds(50));
        properties.setExpectedTurnDuration(Duration.ofMinutes(5).plusSeconds(45));
        assertThrows(IllegalStateException.class, properties::validate);

        properties.setTurnTimeout(Duration.ofMinutes(5).plusSeconds(40));
        properties.setExpectedTurnDuration(Duration.ofSeconds(15));
        properties.validate();
    }

    @Test
    void repliesMustOutliveWeComsStreamWindow() {
        WeComChannelProperties properties = valid();
        properties.setStreamTtl(Duration.ofMinutes(5).plusSeconds(30));

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsMalformedOrDuplicateEntriesAndBadKeys() {
        WeComChannelProperties duplicate = valid();
        duplicate.setDirectUsers(List.of("zhangsan=a", "zhangsan=b"));
        assertThrows(IllegalStateException.class, duplicate::validate);

        WeComChannelProperties malformed = valid();
        malformed.setDirectUsers(List.of("zhangsan"));
        assertThrows(IllegalStateException.class, malformed::validate);

        WeComChannelProperties badKey = valid();
        badKey.setEncodingAesKey("short");
        assertThrows(IllegalStateException.class, badKey::validate);
    }

    @Test
    void intentRoutingIsCheckedAtStartup() {
        WeComChannelProperties valid = valid();
        valid.getIntent().setEnabled(true);
        valid.getIntent().setIntents(
                List.of(
                        entry("answer", "answer"),
                        entry("handoff", "handoff")));
        valid.validate();

        WeComChannelProperties unknownTarget = valid();
        unknownTarget.getIntent().setEnabled(true);
        unknownTarget.getIntent().setIntents(
                List.of(entry("answer", "agent:other")));
        assertThrows(IllegalStateException.class, unknownTarget::validate);

        WeComChannelProperties handoffByDefault = valid();
        handoffByDefault.getIntent().setEnabled(true);
        handoffByDefault.getIntent().setDefaultIntent("handoff");
        handoffByDefault.getIntent().setIntents(valid.getIntent().getIntents());
        assertThrows(IllegalStateException.class, handoffByDefault::validate);

        // Off, the intents are not read.
        WeComChannelProperties off = valid();
        off.getIntent().setIntents(List.of());
        off.validate();
    }

    private static WeComChannelProperties.Entry entry(String name, String target) {
        WeComChannelProperties.Entry entry = new WeComChannelProperties.Entry();
        entry.setName(name);
        entry.setDescription(name);
        entry.setTarget(target);
        entry.setReply("answer".equals(target) ? null : "已登记，请直接联系群里的客服同事。");
        return entry;
    }

    private static WeComChannelProperties valid() {
        WeComChannelProperties properties = new WeComChannelProperties();
        properties.setEnabled(true);
        properties.setToken("token");
        properties.setEncodingAesKey("abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
        properties.setBotId("bot-1");
        properties.setAgentId("wecom-assistant");
        properties.setDirectUsers(List.of("zhangsan=dev-staff"));
        properties.setGroups(List.of("wrkA=wecom-group:cust-a"));
        return properties;
    }
}
