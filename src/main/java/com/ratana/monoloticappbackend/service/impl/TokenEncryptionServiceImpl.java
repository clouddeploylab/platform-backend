package com.ratana.monoloticappbackend.service.impl;

import com.ratana.monoloticappbackend.service.TokenEncryptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class TokenEncryptionServiceImpl implements TokenEncryptionService {

    @Value("${token.encryption.key}")
    private String base64Key;

    @Override
    public String encrypt(String plaintext) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        SecretKey key = new SecretKeySpec(keyBytes, "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));

        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        // Format: base64(iv):base64(ciphertext)
        return Base64.getEncoder().encodeToString(iv) + ":" +
                Base64.getEncoder().encodeToString(ciphertext);
    }

    @Override
    public String decrypt(String encryptedData) throws Exception {
        String[] parts = encryptedData.split(":", 2);
        byte[] iv = Base64.getDecoder().decode(parts[0]);
        byte[] ciphertext = Base64.getDecoder().decode(parts[1]);

        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        SecretKey key = new SecretKeySpec(keyBytes, "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
