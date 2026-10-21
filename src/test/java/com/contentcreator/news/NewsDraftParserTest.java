package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewsDraftParserTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

  @Test
  void parsesAnArrayWrappedInProseAndFencesAndCutsToTheColumnLimits() {
    String text = "Here you go:\n```json\n[{\"headline\":\"" + "h".repeat(150) + "\",\"summary\":\"" + "s".repeat(1200)
        + "\",\"newsDate\":\"2026-10-19\"}]\n```";

    List<NewsDraft> drafts = NewsDraftParser.parse(text, TODAY);

    assertThat(drafts).hasSize(1);
    assertThat(drafts.get(0).headline()).hasSize(120);
    assertThat(drafts.get(0).summary()).hasSize(1000);
    assertThat(drafts.get(0).newsDate()).isEqualTo(LocalDate.of(2026, 10, 19));
    assertThat(drafts.get(0).category()).isEqualTo("OTHER");
  }

  @Test
  void usesTheVietnamDayForInstantsAndTodayForMissingOrBadDates_andDropsIncompleteEntries() {
    String text = "[{\"headline\":\"a\",\"summary\":\"s\",\"newsDate\":\"2026-10-19T18:30:00Z\"},"
        + "{\"headline\":\"b\",\"summary\":\"s\"},"
        + "{\"headline\":\"c\",\"summary\":\"s\",\"newsDate\":\"soon\"},"
        + "{\"headline\":\"\",\"summary\":\"s\"},{\"headline\":\"d\",\"summary\":null}]";

    List<NewsDraft> drafts = NewsDraftParser.parse(text, TODAY);

    assertThat(drafts).extracting(NewsDraft::headline).containsExactly("a", "b", "c");
    assertThat(drafts).extracting(NewsDraft::newsDate).containsExactly(TODAY, TODAY, TODAY);
  }

  @Test
  void rejectsAnswersWithoutAJsonArray() {
    assertThatThrownBy(() -> NewsDraftParser.parse("Sorry, nothing found", TODAY)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NewsDraftParser.parse("[not json]", TODAY)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NewsDraftParser.parse(null, TODAY)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void markupCharactersAreNeutralisedInHeadlineAndSummary() {
    String text = "[{\"headline\":\"**Big** [news](https://evil)\",\"summary\":\"See [x](https://evil.example) **now**\"},"
        + "{\"headline\":\"***\",\"summary\":\"s\"}]";

    List<NewsDraft> drafts = NewsDraftParser.parse(text, TODAY);

    assertThat(drafts).hasSize(1);
    assertThat(drafts.get(0).headline()).isEqualTo("Big (news)(https://evil)");
    assertThat(drafts.get(0).summary()).isEqualTo("See (x)(https://evil.example) now");
  }
}
