package com.agentforge.infrastructure.health;

public record InfrastructureStatus(boolean mysqlUp, boolean redisUp) {

    public boolean ready() {
        return mysqlUp && redisUp;
    }
}
