package com.ratana.monoloticappbackend.service;

import org.springframework.stereotype.Service;

@Service
public interface TokenEncryptionService {
    String encrypt(String plaintext) throws Exception ;
    String decrypt(String encryptedData) throws Exception ;
}
