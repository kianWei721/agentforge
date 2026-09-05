package com.agentforge.tool.device;

import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import com.agentforge.tool.ToolRiskLevel;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class LureLampControlTool implements AgentTool {

    public static final String NAME = "set_lure_lamp_state";
    public static final String ALLOWED_DEVICE_ID = "1001";
    public static final long EXPECTED_PRODUCT_ID = 2001L;

    private final DeviceStatusQueryPort deviceStatusQueryPort;
    private final LureLampControlPort controlPort;
    private final int verifyAttempts;
    private final Duration verifyInterval;

    public LureLampControlTool(
            DeviceStatusQueryPort deviceStatusQueryPort,
            LureLampControlPort controlPort,
            int verifyAttempts,
            Duration verifyInterval) {
        this.deviceStatusQueryPort = deviceStatusQueryPort;
        this.controlPort = controlPort;
        if (verifyAttempts < 1 || verifyAttempts > 20) {
            throw new IllegalArgumentException("verifyAttempts must be between 1 and 20");
        }
        if (verifyInterval == null || verifyInterval.isNegative()) {
            throw new IllegalArgumentException("verifyInterval must not be negative");
        }
        this.verifyAttempts = verifyAttempts;
        this.verifyInterval = verifyInterval;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "将受控演示设备 1001 的诱虫灯管设置为明确的开启或关闭目标状态。"
                + "底层设备协议是无参数 TrapLight 切换动作；工具会在确认通过后的执行时刻重新读取状态，"
                + "仅在当前状态与目标不一致时下发一次命令，禁止自动重复下发。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "deviceId",
                                Map.of(
                                        "type", "string",
                                        "enum", List.of(ALLOWED_DEVICE_ID),
                                        "description", "当前唯一允许控制的设备 ID，只能为 1001"),
                                "enabled",
                                Map.of(
                                        "type", "boolean",
                                        "description", "目标状态：true=打开诱虫灯管，false=关闭诱虫灯管")),
                "required", List.of("deviceId", "enabled"),
                "additionalProperties", false);
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public boolean concurrencySafe() {
        return false;
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String confirmationSummary(Map<String, Object> arguments) {
        Object enabled = arguments == null ? null : arguments.get("enabled");
        String action =
                enabled instanceof Boolean value && value
                        ? "打开"
                        : enabled instanceof Boolean ? "关闭" : "变更";
        return "将设备 1001 的诱虫灯管" + action + "。该操作会真实下发设备控制命令。";
    }

    @Override
    public ToolResult execute(
            ToolInvocationContext context,
            Map<String, Object> arguments) {
        String deviceId = readDeviceId(arguments);
        if (deviceId == null) {
            return ToolResult.failure(
                    "deviceId must be exactly 1001",
                    "DEVICE_NOT_ALLOWED",
                    false);
        }

        Object rawEnabled = arguments == null ? null : arguments.get("enabled");
        if (!(rawEnabled instanceof Boolean enabled)) {
            return ToolResult.failure(
                    "enabled must be a boolean",
                    "INVALID_ARGUMENT",
                    false);
        }

        LureLampState desired = LureLampState.fromBoolean(enabled);

        Optional<DeviceStatusSnapshot> device;
        try {
            device = deviceStatusQueryPort.findByDeviceId(deviceId);
        } catch (RuntimeException exception) {
            return ToolResult.failure(
                    "cannot verify device identity and online state before control",
                    "DEVICE_PRECHECK_FAILED",
                    false);
        }
        if (device.isEmpty()) {
            return ToolResult.failure(
                    "controlled device not found",
                    "DEVICE_NOT_FOUND",
                    false);
        }

        DeviceStatusSnapshot deviceSnapshot = device.get();
        if (!"ONLINE".equals(deviceSnapshot.status())) {
            return ToolResult.failure(
                    "device 1001 is not online; control was not sent",
                    safeData(
                            desired,
                            null,
                            false,
                            null,
                            null,
                            "DEVICE_OFFLINE"),
                    "DEVICE_OFFLINE",
                    false);
        }
        if (!isExpectedProduct(deviceSnapshot)) {
            return ToolResult.failure(
                    "device 1001 no longer matches the approved product boundary",
                    safeData(
                            desired,
                            null,
                            false,
                            null,
                            null,
                            "PRODUCT_MISMATCH"),
                    "PRODUCT_MISMATCH",
                    false);
        }

        final LureLampStateSnapshot before;
        try {
            before = controlPort.readState(deviceId);
        } catch (LureLampControlException exception) {
            return controlFailure(exception, desired, null, false, null);
        }

        if (before.state() == LureLampState.UNKNOWN) {
            return ToolResult.failure(
                    "current lure lamp state is unknown; toggle command was not sent",
                    safeData(
                            desired,
                            before.state(),
                            false,
                            null,
                            null,
                            "STATE_UNKNOWN"),
                    "STATE_UNKNOWN",
                    false);
        }

        if (before.state() == desired) {
            return ToolResult.success(
                    "lure lamp already matches the requested target state; no command was sent",
                    safeData(
                            desired,
                            before.state(),
                            false,
                            null,
                            null,
                            "NO_OP_ALREADY_DESIRED"));
        }

        final LureLampCommandReceipt receipt;
        try {
            receipt =
                    controlPort.invokeToggle(
                            deviceId,
                            context == null ? null : context.telemetry());
        } catch (LureLampControlException exception) {
            if (exception.mayHaveExecuted()) {
                LureLampStateSnapshot observed = verifyTargetState(deviceId, desired);
                if (observed != null && observed.state() == desired) {
                    return ToolResult.success(
                            "lure lamp reached the requested target state after an ambiguous command response",
                            safeData(
                                    desired,
                                    observed.state(),
                                    true,
                                    null,
                                    null,
                                    "TARGET_STATE_CONFIRMED_AFTER_AMBIGUOUS_RESPONSE"));
                }
                return ToolResult.failure(
                        "command outcome is unknown; do not resend automatically",
                        safeData(
                                desired,
                                observed == null ? null : observed.state(),
                                true,
                                null,
                                null,
                                "COMMAND_OUTCOME_UNKNOWN"),
                        "COMMAND_OUTCOME_UNKNOWN",
                        false);
            }
            return controlFailure(exception, desired, before.state(), false, null);
        }

        LureLampStateSnapshot observed = verifyTargetState(deviceId, desired);
        if (observed != null && observed.state() == desired) {
            return ToolResult.success(
                    "lure lamp command succeeded and the requested target state was observed",
                    safeData(
                            desired,
                            observed.state(),
                            true,
                            receipt.commandId(),
                            receipt.messageId(),
                            "TARGET_STATE_CONFIRMED"));
        }

        return ToolResult.failure(
                "device accepted the control command, but the requested target state was not confirmed; do not resend automatically",
                safeData(
                        desired,
                        observed == null ? null : observed.state(),
                        true,
                        receipt.commandId(),
                        receipt.messageId(),
                        "TARGET_STATE_UNCONFIRMED"),
                "TARGET_STATE_UNCONFIRMED",
                false);
    }

    private LureLampStateSnapshot verifyTargetState(
            String deviceId,
            LureLampState desired) {
        LureLampStateSnapshot last = null;
        for (int attempt = 1; attempt <= verifyAttempts; attempt++) {
            if (attempt > 1 && !sleepVerificationInterval()) {
                return last;
            }
            try {
                last = controlPort.readState(deviceId);
                if (last.state() == desired) {
                    return last;
                }
            } catch (LureLampControlException exception) {
                if (exception.reason() == LureLampControlException.Reason.INTERRUPTED) {
                    return last;
                }
            }
        }
        return last;
    }

    private boolean sleepVerificationInterval() {
        long millis = verifyInterval.toMillis();
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static ToolResult controlFailure(
            LureLampControlException exception,
            LureLampState desired,
            LureLampState observed,
            boolean commandSent,
            LureLampCommandReceipt receipt) {
        String code = exception.reason().name();
        return ToolResult.failure(
                "lure lamp control unavailable: " + code,
                safeData(
                        desired,
                        observed,
                        commandSent,
                        receipt == null ? null : receipt.commandId(),
                        receipt == null ? null : receipt.messageId(),
                        code),
                code,
                false);
    }

    private static Map<String, Object> safeData(
            LureLampState desired,
            LureLampState observed,
            boolean commandSent,
            String commandId,
            String messageId,
            String outcome) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", ALLOWED_DEVICE_ID);
        if (desired != null) {
            data.put("desiredState", desired.name());
        }
        if (observed != null) {
            data.put("observedState", observed.name());
        }
        data.put("commandSent", commandSent);
        if (commandId != null && !commandId.isBlank()) {
            data.put("commandId", commandId);
        }
        if (messageId != null && !messageId.isBlank()) {
            data.put("messageId", messageId);
        }
        data.put("outcome", outcome);
        data.put("statePersistenceGuaranteed", false);
        return data;
    }

    private static String readDeviceId(Map<String, Object> arguments) {
        if (arguments == null) {
            return null;
        }
        Object raw = arguments.get("deviceId");
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return ALLOWED_DEVICE_ID.equals(value) ? value : null;
    }

    private static boolean isExpectedProduct(DeviceStatusSnapshot snapshot) {
        Object productId = snapshot.attributes().get("productId");
        if (productId instanceof Number number) {
            return number.longValue() == EXPECTED_PRODUCT_ID;
        }
        return productId != null
                && Long.toString(EXPECTED_PRODUCT_ID).equals(productId.toString().trim());
    }
}
