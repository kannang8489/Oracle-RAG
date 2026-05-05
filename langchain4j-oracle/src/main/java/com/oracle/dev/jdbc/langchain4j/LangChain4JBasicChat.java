/*
  Copyright (c) 2024, Oracle and/or its affiliates.

  This software is dual-licensed to you under the Universal Permissive License
  (UPL) 1.0 as shown at https://oss.oracle.com/licenses/upl or Apache License
  2.0 as shown at http://www.apache.org/licenses/LICENSE-2.0. You may choose
  either license.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/
/*
  Copyright (c) 2024, Oracle and/or its affiliates.

  This software is dual-licensed to you under the Universal Permissive License
  (UPL) 1.0 as shown at https://oss.oracle.com/licenses/upl or Apache License
  2.0 as shown at http://www.apache.org/licenses/LICENSE-2.0. You may choose
  either license.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/
package com.oracle.dev.jdbc.langchain4j;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

public class LangChain4JBasicChat {

  public static void main(String[] args) {

    // You can easily switch between "OLLAMA", "GEMINI", or "OPENAI"
    String provider = "OLLAMA";
    ChatLanguageModel model = getChatModel(provider);
        
    String answer = model.generate("Who is the Prime Minister of India?");
    System.out.println("\nANSWER:\n" + answer);

  }

  private static ChatLanguageModel getChatModel(String provider) {
    if ("OLLAMA".equalsIgnoreCase(provider)) {
      return OllamaChatModel.builder()
          .baseUrl("http://localhost:11434")
          .modelName("llama3.1") // Using the model you have installed locally
          .build();
    } else if ("GEMINI".equalsIgnoreCase(provider)) {
      return GoogleAiGeminiChatModel.builder()
          .apiKey(System.getenv("GEMINI_API_KEY"))
          .modelName("gemini-1.5-flash") // Update with your valid model name
          .build();
    } else if ("OPENAI".equalsIgnoreCase(provider)) {
      return OpenAiChatModel.builder()
          .apiKey(System.getenv("OPENAI_API_KEY"))
          .modelName("gpt-4o")
          .build();
    }
    throw new IllegalArgumentException("Unknown model provider: " + provider);
  }
}
