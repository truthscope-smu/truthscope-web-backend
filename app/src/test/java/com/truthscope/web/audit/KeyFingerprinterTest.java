package com.truthscope.web.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KeyFingerprinter 단위 테스트")
class KeyFingerprinterTest {

  @Test
  @DisplayName("fingerprint_정상키_16자_hex_반환")
  void fingerprint_정상키_16자_hex_반환() {
    String result = KeyFingerprinter.fingerprint("AIzaSyTestKeyExample0123456789");

    assertThat(result).hasSize(16);
    assertThat(result).matches("[0-9a-f]{16}");
  }

  @Test
  @DisplayName("fingerprint_동일입력_동일출력_결정적_해시")
  void fingerprint_동일입력_동일출력() {
    String input = "AIzaSyTestKey";
    String result1 = KeyFingerprinter.fingerprint(input);
    String result2 = KeyFingerprinter.fingerprint(input);

    assertThat(result1).isEqualTo(result2);
  }

  @Test
  @DisplayName("fingerprint_다른입력_다른출력")
  void fingerprint_다른입력_다른출력() {
    String result1 = KeyFingerprinter.fingerprint("key-A");
    String result2 = KeyFingerprinter.fingerprint("key-B");

    assertThat(result1).isNotEqualTo(result2);
  }

  @Test
  @DisplayName("fingerprint_null_IllegalArgumentException")
  void fingerprint_null_예외() {
    assertThatThrownBy(() -> KeyFingerprinter.fingerprint(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("userApiKey");
  }

  @Test
  @DisplayName("fingerprint_blank_IllegalArgumentException")
  void fingerprint_blank_예외() {
    assertThatThrownBy(() -> KeyFingerprinter.fingerprint("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("userApiKey");
  }

  @Test
  @DisplayName("fingerprint_empty_IllegalArgumentException")
  void fingerprint_empty_예외() {
    assertThatThrownBy(() -> KeyFingerprinter.fingerprint(""))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
