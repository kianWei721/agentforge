package com.agentforge.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IoTDeviceStatusPropertiesTest {

    @Test
    void shouldAcceptRawOrBearerToken() {
        IoTDeviceStatusProperties properties = valid();

        properties.setAccessToken("abc");
        assertEquals("Bearer abc", properties.authorizationHeaderValue());

        properties.setAccessToken("Bearer xyz");
        assertEquals("Bearer xyz", properties.authorizationHeaderValue());
    }

    @Test
    void shouldRejectUnsafeOrIncompleteHttpConfiguration() {
        IoTDeviceStatusProperties properties = valid();

        properties.setBaseUrl("ftp://iot.example.internal");
        assertThrows(IllegalArgumentException.class, properties::validateForHttp);

        properties = valid();
        properties.setTenantId("tenant-a");
        assertThrows(IllegalArgumentException.class, properties::validateForHttp);

        properties = valid();
        properties.setDetailPath("/devices/no-placeholder");
        assertThrows(IllegalArgumentException.class, properties::validateForHttp);
    }

    private static IoTDeviceStatusProperties valid() {
        IoTDeviceStatusProperties properties = new IoTDeviceStatusProperties();
        properties.setMode(IoTDeviceStatusProperties.Mode.HTTP);
        properties.setBaseUrl("https://iot.example.internal");
        properties.setAccessToken("token");
        properties.setTenantId("1");
        return properties;
    }
}
