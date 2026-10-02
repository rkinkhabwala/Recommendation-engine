package com.recsys.stream.model;

import com.recsys.events.v1.UserEvent;

/** Dedupe/lateness verdict; never serialized (branched immediately). */
public record Deduped(UserEvent event, boolean late) {}
