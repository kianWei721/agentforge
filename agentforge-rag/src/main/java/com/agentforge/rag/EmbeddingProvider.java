package com.agentforge.rag;

import java.util.List;

public interface EmbeddingProvider {

    List<float[]> embed(List<String> texts);

    String model();

    int dimensions();
}
