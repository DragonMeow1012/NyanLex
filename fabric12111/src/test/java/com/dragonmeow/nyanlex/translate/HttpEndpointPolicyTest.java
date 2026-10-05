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
 @Test void rejectsRemotePlaintextAndConfusingAddresses() {
  for (String url : List.of("http://api.example/v1", "http://localhost.evil/v1",
       "http://127.0.0.1.evil/v1", "http://localhost@evil/v1", "https://key@api.example/v1",
       "file:///etc/passwd", "http://192.168.1.5/v1", "https://api.example/v1#secret"))
   assertThrows(IllegalArgumentException.class, () -> HttpEndpointPolicy.validate(url), url);
 }
}
