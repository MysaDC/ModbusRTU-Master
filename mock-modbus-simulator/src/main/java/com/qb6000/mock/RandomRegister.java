package com.qb6000.mock;

import com.ghgande.j2mod.modbus.procimg.SynchronizedAbstractRegister;

import java.util.concurrent.ThreadLocalRandom;

public final class RandomRegister extends SynchronizedAbstractRegister {
    private final int min;
    private final int max;

    public RandomRegister(int min, int max) {
        this.min = min;
        this.max = max;
        setValue(nextRandom());
    }

    @Override
    public synchronized int getValue() {
        int value = nextRandom();
        super.setValue(value);
        return value;
    }

    @Override
    public synchronized byte[] toBytes() {
        int value = nextRandom();
        super.setValue(value);
        return super.toBytes();
    }

    @Override
    public synchronized int toUnsignedShort() {
        return getValue();
    }

    @Override
    public synchronized short toShort() {
        return (short) getValue();
    }

    private int nextRandom() {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}
