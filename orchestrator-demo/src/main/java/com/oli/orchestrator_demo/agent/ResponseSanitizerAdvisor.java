/**
 * @ClassName ResponseSanitizerAdvisor
 * @Description LLM output compatibility advisor: many local models (e.g.
 *              qwen:7b) produce output that breaks the structured-output
 *              conversion of `entity()` in three typical ways. This advisor
 *              sanitizes the final assistant message before it reaches the
 *              converter:
 *              <ol>
 *              <li>Markdown fences around the JSON payload are stripped;</li>
 *              <li>JSON embedded in surrounding prose is extracted;</li>
 *              <li>a bare JSON array (e.g. [{...}]) is wrapped into the single
 *              array-typed property of the target schema (e.g.
 *              {"dayPlans":[...]}) when the schema describes an object with
 *              exactly one top-level property.</li>
 *              </ol>
 *              Already-pure JSON objects pass through untouched.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;

public class ResponseSanitizerAdvisor implements CallAdvisor {

  public static final String NAME = "ResponseSanitizer";

  private static final Pattern FENCED_JSON =
      Pattern.compile("```(?:json|JSON)?\\s*(.*?)\\s*```", Pattern.DOTALL);

  private static final Pattern ARRAY_TYPED =
      Pattern.compile("\"type\"\\s*:\\s*\"array\"");

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public int getOrder() {
    // Must stay strictly below Ordered.LOWEST_PRECEDENCE: the terminal
    // ChatModelCallAdvisor also uses Integer.MAX_VALUE, and an equal order
    // gets shadowed by it (the advisor is then never invoked).
    return Ordered.LOWEST_PRECEDENCE - 100;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    ChatClientResponse response = chain.nextCall(request);
    ChatResponse chatResponse = response.chatResponse();
    if (chatResponse == null || chatResponse.getResult() == null
        || chatResponse.getResult().getOutput() == null) {
      return response;
    }
    String original = chatResponse.getResult().getOutput().getText();
    Object outputFormat = request.context().get(ChatClientAttributes.OUTPUT_FORMAT.getKey());
    String schemaHint = outputFormat instanceof String format ? format : null;
    String sanitized = sanitize(original, schemaHint);
    if (sanitized.equals(original)) {
      return response;
    }
    return response.mutate()
        .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(sanitized)))))
        .build();
  }

  /** Returns a parseable JSON payload for the given model output, or the input unchanged when no JSON can be located (the converter then reports the original error). */
  static String sanitize(String text) {
    return sanitize(text, null);
  }

  /**
   * Sanitizing as described in the class javadoc; {@code schemaHint} is the
   * JSON Schema carried in the ChatClient `OUTPUT_FORMAT` context attribute
   * when entity() is used (null when the schema is unknown).
   */
  static String sanitize(String text, String schemaHint) {
    if (text == null || text.isBlank()) {
      return text;
    }
    String trimmed = text.trim();
    String candidate = text;
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
      // 1. Markdown fenced block: ```json ... ```
      Matcher matcher = FENCED_JSON.matcher(trimmed);
      if (matcher.find() && isJsonLike(matcher.group(1).trim())) {
        candidate = matcher.group(1).trim();
      }
      else {
        // 2. Outermost JSON object/array embedded in prose.
        int start = firstJsonStart(trimmed);
        int end = lastJsonEnd(trimmed);
        if (start >= 0 && end > start && isJsonLike(trimmed.substring(start, end + 1))) {
          candidate = trimmed.substring(start, end + 1);
        }
      }
    }
    // 3. Bare array for a single-array-property schema: [{...}] -> {"dayPlans":[...]}.
    return wrapIfBareArray(candidate, schemaHint);
  }

  /**
   * Wraps a bare JSON array into the single array-typed property of the target
   * schema. Only applied when the schema describes an object with exactly one
   * top-level property (e.g. ItineraryResponse.dayPlans); otherwise the payload
   * is returned unchanged — the converter/retry then reports the mismatch.
   */
  static String wrapIfBareArray(String payload, String schemaHint) {
    if (schemaHint == null || schemaHint.isBlank()) {
      return payload;
    }
    String trimmed = payload.trim();
    if (!trimmed.startsWith("[")) {
      return payload;
    }
    String properties = propertiesObjectOf(schemaHint);
    if (properties == null || !ARRAY_TYPED.matcher(properties).find()) {
      return payload;
    }
    String key = singlePropertyKeyOf(properties);
    if (key == null) {
      return payload;
    }
    return "{\"" + key + "\":" + trimmed + "}";
  }

  /** The substring of the first (top-level) "properties" object, inclusive of braces, or null. */
  private static String propertiesObjectOf(String schema) {
    int start = schema.indexOf("\"properties\"");
    if (start < 0) {
      return null;
    }
    int open = schema.indexOf('{', start);
    if (open < 0) {
      return null;
    }
    int close = matchingBrace(schema, open);
    return close < 0 ? null : schema.substring(open, close + 1);
  }

  /** Index of the '}' matching the '{' at {@code open}, honoring JSON string escapes. */
  private static int matchingBrace(String text, int open) {
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
        }
        else if (c == '\\') {
          escaped = true;
        }
        else if (c == '"') {
          inString = false;
        }
      }
      else if (c == '"') {
        inString = true;
      }
      else if (c == '{') {
        depth++;
      }
      else if (c == '}') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  /**
   * The only top-level key of a properties object, or null when the object has
   * zero or multiple keys. Inside a JSON Schema "properties" object every
   * depth-0 string is a property name; values are nested schema objects.
   */
  private static String singlePropertyKeyOf(String propertiesObject) {
    List<String> keys = new ArrayList<>();
    boolean inString = false;
    boolean escaped = false;
    int depth = 0;
    int keyStart = -1;
    for (int i = 1; i < propertiesObject.length() - 1; i++) {
      char c = propertiesObject.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
        }
        else if (c == '\\') {
          escaped = true;
        }
        else if (c == '"') {
          inString = false;
          if (depth == 0 && keyStart >= 0) {
            keys.add(propertiesObject.substring(keyStart, i));
            keyStart = -1;
          }
        }
      }
      else if (c == '"') {
        inString = true;
        escaped = false;
        if (depth == 0) {
          keyStart = i + 1;
        }
      }
      else if (c == '{' || c == '[') {
        depth++;
      }
      else if (c == '}' || c == ']') {
        depth--;
      }
    }
    return keys.size() == 1 ? keys.get(0) : null;
  }

  private static boolean isJsonLike(String candidate) {
    return candidate.startsWith("{") || candidate.startsWith("[");
  }

  private static int firstJsonStart(String text) {
    int object = text.indexOf('{');
    int array = text.indexOf('[');
    if (object < 0) {
      return array;
    }
    if (array < 0) {
      return object;
    }
    return Math.min(object, array);
  }

  private static int lastJsonEnd(String text) {
    return Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
  }
}
