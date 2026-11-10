package com.contentcreator.news.intake;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests of {@link IpRanges}: CIDR matching for the intake's address allow-list. */
class IpRangesTest {

  @Test
  void aV4RangeCoversItsFirstAndLastAddressButNotTheNeighbours() {
    IpRanges ranges = IpRanges.parse("160.79.104.0/21");
    assertTrue(ranges.contains("160.79.104.0"));
    assertTrue(ranges.contains("160.79.106.130"));
    assertTrue(ranges.contains("160.79.111.255"));
    assertFalse(ranges.contains("160.79.103.255"));
    assertFalse(ranges.contains("160.79.112.0"));
  }

  @Test
  void severalEntriesWithBlanksAreAllUsed() {
    IpRanges ranges = IpRanges.parse(" 10.0.0.0/8 , ,203.0.113.7 ");
    assertTrue(ranges.contains("10.200.1.1"));
    assertTrue(ranges.contains("203.0.113.7"));
    assertFalse(ranges.contains("203.0.113.8"));
  }

  @Test
  void aV6RangeMatchesV6AddressesOnly() {
    IpRanges ranges = IpRanges.parse("2001:db8::/32");
    assertTrue(ranges.contains("2001:db8:1::5"));
    assertFalse(ranges.contains("2001:db9::1"));
    assertFalse(ranges.contains("10.0.0.1"));
  }

  @Test
  void anythingThatIsNotAnAddressIsNeverAllowed() {
    IpRanges ranges = IpRanges.parse("0.0.0.0/0");
    assertFalse(ranges.contains(null));
    assertFalse(ranges.contains("unknown"));
    assertFalse(ranges.contains("example.com"));
    assertFalse(ranges.contains(""));
  }

  @Test
  void blankOrNullGivesAnEmptyList() {
    assertTrue(IpRanges.parse(null).isEmpty());
    assertTrue(IpRanges.parse("  ").isEmpty());
    assertFalse(IpRanges.parse("1.2.3.4").isEmpty());
  }

  @Test
  void badEntriesAreRejectedAtStartup() {
    assertThrows(IllegalArgumentException.class, () -> IpRanges.parse("not-an-ip"));
    assertThrows(IllegalArgumentException.class, () -> IpRanges.parse("10.0.0.0/33"));
    assertThrows(IllegalArgumentException.class, () -> IpRanges.parse("10.0.0.0/x"));
  }
}
