package br.com.mss.domino.net;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 da senha de uma partida (Fase 4-1, ADR-0014) — a senha em si nunca viaja pelo transporte,
 * só este hash. Sem sal: o cliente tem que produzir o mesmo hash tanto ao criar quanto ao entrar,
 * sem um segredo prévio pra buscar no servidor primeiro; para o risco real (uma partida casual em
 * LAN, não uma conta protegida), isso é suficiente — evita só o caso óbvio de "senha em claro no
 * fio/log". Em {@code net} (não {@code net.rmi}) porque {@code net.grpc} (Fase 6a) também precisa —
 * é puramente criptografia, sem nada de transporte.
 */
public final class PasswordHashing {

  private PasswordHashing() {}

  /** {@code null} se {@code password} for nula/em branco (partida aberta); senão, 64 hex chars. */
  public static String hash(String password) {
    if (password == null || password.isBlank()) {
      return null;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(password.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 é obrigatório em toda implementação de JVM (java.security.MessageDigest javadoc).
      throw new IllegalStateException("SHA-256 indisponível nesta JVM", e);
    }
  }
}
