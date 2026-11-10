package com.contentcreator.news.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class IntakeTypeDetectorTest {

  private static IntakeTypeDetector.Type of(String json) throws Exception {
    return IntakeTypeDetector.detect(new ObjectMapper().readTree(json));
  }

  @Test
  void aLetterTextWithoutSectionsIsALetter() throws Exception {
    assertThat(of("{\"letter\":\"# T\"}")).isEqualTo(IntakeTypeDetector.Type.LETTER);
  }

  @Test
  void sectionsOrNeitherIsNews() throws Exception {
    assertThat(of("{\"sections\":{}}")).isEqualTo(IntakeTypeDetector.Type.NEWS);
    assertThat(of("{}")).isEqualTo(IntakeTypeDetector.Type.NEWS);
    assertThat(of("{\"letter\":5}")).isEqualTo(IntakeTypeDetector.Type.NEWS);
  }

  @Test
  void bothIsAmbiguous() throws Exception {
    assertThat(of("{\"letter\":\"x\",\"sections\":{}}")).isEqualTo(IntakeTypeDetector.Type.AMBIGUOUS);
  }
}
