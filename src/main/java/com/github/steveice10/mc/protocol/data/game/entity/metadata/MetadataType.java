package com.github.steveice10.mc.protocol.data.game.entity.metadata;

public enum MetadataType {
    BYTE,
    INT,
    FLOAT,
    STRING,
    CHAT,
    OPTIONAL_CHAT,
    ITEM,
    BOOLEAN,
    ROTATION,
    POSITION,
    OPTIONAL_POSITION,
    DIRECTION,
    OPTIONAL_UUID,
    BLOCK_STATE,
    NBT_TAG,
    PARTICLE,
    VILLAGER_DATA,
    OPTIONAL_VARINT,
    POSE;

    public static final MetadataType[] VALUES = values();
}
