package com.qb6000.das.model;

public record ChannelData(
    int channel,
    int register,
    int rawValue,
    double concentration
) {
}
