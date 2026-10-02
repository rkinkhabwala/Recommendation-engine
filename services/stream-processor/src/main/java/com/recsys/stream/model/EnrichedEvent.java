package com.recsys.stream.model;

/** An event joined with its item profile; {@code profile} is null for unknown items. */
public record EnrichedEvent(EventView event, ItemProfile profile) {}
