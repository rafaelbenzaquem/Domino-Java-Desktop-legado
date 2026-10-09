package br.com.mss.domino.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** {@link PasswordHashing} — SHA-256 sem sal, determinístico (Fase 4-1, ADR-0014). */
class PasswordHashingTest {

  @Test
  void nulo_devolveNull() {
    assertNull(PasswordHashing.hash(null));
  }

  @Test
  void emBranco_devolveNull() {
    assertNull(PasswordHashing.hash("   "));
  }

  @Test
  void mesmaSenha_mesmoHash() {
    assertEquals(PasswordHashing.hash("abacate123"), PasswordHashing.hash("abacate123"));
  }

  @Test
  void senhasDiferentes_hashesDiferentes() {
    assertNotEquals(PasswordHashing.hash("abacate123"), PasswordHashing.hash("abacate124"));
  }

  @Test
  void hashEhHexDe64Caracteres() {
    String hash = PasswordHashing.hash("qualquer coisa");
    assertEquals(64, hash.length());
    assertEquals(hash, hash.toLowerCase());
  }
}
