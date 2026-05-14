package com.agentpay.sanctions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SanctionsMatchingTest {

  @Test
  void exactMatch() {
    assertThat(SanctionsMcpService.nameMatches("Test Subject Alpha", "Test Subject Alpha"))
        .isTrue();
  }

  @Test
  void caseInsensitiveMatch() {
    assertThat(SanctionsMcpService.nameMatches("TEST SUBJECT ALPHA", "Test Subject Alpha"))
        .isTrue();
  }

  @Test
  void fuzzyOneEditDeletionOnLongEntry() {
    // "Test Subect Alpha" vs "Test Subject Alpha" — one deletion of 'j', entry >5 chars
    assertThat(SanctionsMcpService.nameMatches("Test Subect Alpha", "Test Subject Alpha")).isTrue();
  }

  @Test
  void fuzzyOneEditInsertionOnLongEntry() {
    // "Test Subjectt Alpha" vs "Test Subject Alpha" — one insertion, entry >5 chars
    assertThat(SanctionsMcpService.nameMatches("Test Subjectt Alpha", "Test Subject Alpha"))
        .isTrue();
  }

  @Test
  void noMatchCompletelyDifferent() {
    assertThat(SanctionsMcpService.nameMatches("Completely Harmless Person", "Test Subject Alpha"))
        .isFalse();
  }

  @Test
  void noFuzzyForShortEntries() {
    // Entry "Bob" is 3 chars, not >5, so fuzzy is disabled even for 1-edit distance
    assertThat(SanctionsMcpService.nameMatches("Bop", "Bob")).isFalse();
  }

  @Test
  void levenshteinSameStrings() {
    assertThat(SanctionsMcpService.levenshtein("hello", "hello")).isZero();
  }

  @Test
  void levenshteinOneSubstitution() {
    assertThat(SanctionsMcpService.levenshtein("kitten", "sitten")).isEqualTo(1);
  }

  @Test
  void levenshteinOneDeletion() {
    assertThat(SanctionsMcpService.levenshtein("test subject", "test subect")).isEqualTo(1);
  }

  @Test
  void levenshteinEmpty() {
    assertThat(SanctionsMcpService.levenshtein("", "abc")).isEqualTo(3);
    assertThat(SanctionsMcpService.levenshtein("abc", "")).isEqualTo(3);
  }
}
