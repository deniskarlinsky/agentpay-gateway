package com.agentpay.sanctions;

public record SanctionsResult(
    boolean isMatch, Float matchStrength, String listName, String citationId) {}
