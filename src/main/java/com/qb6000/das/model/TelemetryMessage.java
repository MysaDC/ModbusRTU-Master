package com.qb6000.das.model;

import java.time.Instant;
import java.util.List;

public record TelemetryMessage(
    String controllerIp,
    Instant timestamp,
    List<ChannelData> channels
) {
}
