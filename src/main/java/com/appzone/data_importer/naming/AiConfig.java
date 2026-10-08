package com.appzone.data_importer.naming;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import com.google.genai.Client;

/**
 * Spring AI's own Gemini client bean throws at startup when no API key is set, which would stop
 * the whole app from opening. This replaces it with one that always starts; when the key is
 * missing, AiColumnNameShortener sees isKeyConfigured() == false and skips AI with a clear message.
 *
 * The key is read from the Environment, NOT with @Value: Spring AI makes this configuration class
 * get created very early (see the "Cannot enhance @Configuration bean 'aiConfig'" startup warning),
 * before ${...} placeholders in @Value are resolved. With @Value the literal text
 * "${spring.ai.google.genai.api-key:}" was sent to Google as the key -> "API key not valid".
 */
@Configuration(proxyBeanMethods = false)
public class AiConfig {

    private static final String KEY_PROPERTY = "spring.ai.google.genai.api-key";

    public record AiKeyStatus(boolean isKeyConfigured) {
    }

    @Bean
    public static AiKeyStatus aiKeyStatus(Environment environment) {
        return new AiKeyStatus(StringUtils.hasText(apiKey(environment)));
    }

    @Bean
    public static Client googleGenAiClient(Environment environment) {
        String key = apiKey(environment);
        if (StringUtils.hasText(key)) {
            System.out.println("Gemini API key loaded (" + key.length() + " characters, starts with '"
                    + key.substring(0, Math.min(4, key.length())) + "').");
            return Client.builder().apiKey(key).build();
        }
        System.out.println("No Gemini API key found (GEMINI_API_KEY). Page 1 will work without AI.");
        return Client.builder().apiKey("GEMINI_API_KEY_NOT_SET").build();
    }

    @Bean
    public ChatClient geminiChatClient(GoogleGenAiChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /** The configured key, trimmed; null when it is missing or still an unresolved placeholder. */
    private static String apiKey(Environment environment) {
        String key;
        try {
            key = environment.getProperty(KEY_PROPERTY, "");
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            key = null;   // ${GEMINI_API_KEY} without a default and the variable is not set
        }
        if (!StringUtils.hasText(key) || key.contains("${")) {
            key = System.getenv("GEMINI_API_KEY");
        }
        return StringUtils.hasText(key) ? key.trim() : null;
    }
}
