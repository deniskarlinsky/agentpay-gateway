package com.agentpay.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AgentCard(
    String name,
    String description,
    String url,
    String version,
    @JsonProperty("protocolVersion") String protocolVersion,
    Capabilities capabilities,
    @JsonProperty("defaultInputModes") List<String> defaultInputModes,
    @JsonProperty("defaultOutputModes") List<String> defaultOutputModes,
    List<Skill> skills) {

  public record Capabilities(boolean streaming) {}

  public record Skill(String id, String name, String description, List<String> tags) {}
}
