package com.dragonmeow.nyanlex.translate;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class HttpEndpointPolicyTest {
 @Test void permitsTlsAndExplicitLocalServers() {
  for (String url : List.of("https://api.example/v1", "http://localhost:11434/v1",
       "http://127.0.0.1:1234/v1", "http://[::1]:11434/v1"))
   assertEquals(url, HttpEndpointPolicy.validate(url).toString());
 }
 @Test void onlyTheOfficialAnthropicEndpointCountsAsAnthropic() {
  for (String url : List.of("https://api.anthropic.com", "https://api.anthropic.com/", "https://api.anthropic.com/v1",
       "https://API.Anthropic.com:443/v1/messages"))
   assertTrue(HttpEndpointPolicy.isAnthropic(url), url);
  for (String url : List.of("http://api.anthropic.com", "https://api.anthropic.com:8443/v1",
       "https://api.anthropic.com.evil.com/v1", "https://evil.com/api.anthropic.com", "https://key@api.anthropic.com/v1",
       "https://api.anthropic.com/v1#x", "https://claude.ai", "https://api.openai.com/v1",
       "http://localhost:11434/v1", "", "not a url"))
   assertFalse(HttpEndpointPolicy.isAnthropic(url), url);
  assertFalse(HttpEndpointPolicy.isAnthropic(null));
 }
 @Test void geminiAndDeepSeekAreRecognisedByTheirOfficialHostOnly() {
  for (String url : List.of("https://generativelanguage.googleapis.com/v1beta/openai",
       "https://GenerativeLanguage.googleapis.com/v1beta/openai/chat/completions", "https://generativelanguage.googleapis.com:443/"))
   assertTrue(HttpEndpointPolicy.isGemini(url), url);
  for (String url : List.of("https://api.deepseek.com", "https://api.deepseek.com/", "https://API.DeepSeek.com:443/v1"))
   assertTrue(HttpEndpointPolicy.isDeepSeek(url), url);
  for (String url : List.of("http://generativelanguage.googleapis.com/v1beta/openai",
       "https://generativelanguage.googleapis.com.evil.test/v1beta/openai", "https://evil.test/generativelanguage.googleapis.com",
       "https://key@generativelanguage.googleapis.com/", "https://api.deepseek.com/", "", "not a url"))
   assertFalse(HttpEndpointPolicy.isGemini(url), url);
  for (String url : List.of("http://api.deepseek.com", "https://api.deepseek.com:8443", "https://api.deepseek.com.evil.test",
       "https://key@api.deepseek.com", "https://api.openai.com/v1", "", "not a url"))
   assertFalse(HttpEndpointPolicy.isDeepSeek(url), url);
  assertFalse(HttpEndpointPolicy.isGemini(null));
  assertFalse(HttpEndpointPolicy.isDeepSeek(null));
  assertFalse(HttpEndpointPolicy.isOpenAi("https://api.deepseek.com"));
  assertFalse(HttpEndpointPolicy.isAnthropic("https://generativelanguage.googleapis.com/v1beta/openai"));
 }
 @Test void rejectsRemotePlaintextAndConfusingAddresses() {
  for (String url : List.of("http://api.example/v1", "http://localhost.evil/v1",
       "http://127.0.0.1.evil/v1", "http://localhost@evil/v1", "https://key@api.example/v1",
       "file:///etc/passwd", "http://192.168.1.5/v1", "https://api.example/v1#secret"))
   assertThrows(IllegalArgumentException.class, () -> HttpEndpointPolicy.validate(url), url);
 }
}
