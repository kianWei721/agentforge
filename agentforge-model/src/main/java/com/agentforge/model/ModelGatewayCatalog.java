package com.agentforge.model;

import java.util.Map;

public interface ModelGatewayCatalog {
    String activeProvider();
    Map<String, String> registeredProviders();
}
