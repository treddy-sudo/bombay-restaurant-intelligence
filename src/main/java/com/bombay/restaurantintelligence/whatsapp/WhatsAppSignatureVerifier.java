package com.bombay.restaurantintelligence.whatsapp;
import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Component; import javax.crypto.Mac; import javax.crypto.spec.SecretKeySpec; import java.nio.charset.StandardCharsets; import java.security.MessageDigest; import java.util.HexFormat;
@Component public class WhatsAppSignatureVerifier {
 private final String secret; public WhatsAppSignatureVerifier(@Value("${app.whatsapp.app-secret:}")String secret){this.secret=secret;}
 public boolean valid(String body,String header){if(secret==null||secret.isBlank())return true;if(header==null||!header.startsWith("sha256="))return false;try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));String expected="sha256="+HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),header.getBytes(StandardCharsets.US_ASCII));}catch(Exception e){return false;}}
}
