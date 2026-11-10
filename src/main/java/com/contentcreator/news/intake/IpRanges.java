package com.contentcreator.news.intake;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * A list of address ranges in CIDR form (IPv4 or IPv6), used to decide whether a caller's address is allowed without
 * a token (see {@link NewsIntakeProperties#allowedCidrs()}). Only literal addresses are parsed, never host names.
 */
final class IpRanges {

  private record Range(byte[] network, int prefix) {}

  private final List<Range> ranges;

  private IpRanges(List<Range> ranges) {
    this.ranges = ranges;
  }

  /**
   * Parses a comma-separated list such as {@code 160.79.104.0/21, 2607:6bc0::/48}; a bare address means /32 (IPv4) or
   * /128 (IPv6).
   *
   * @param csv the list, may be null or blank (no ranges)
   * @return the ranges
   * @throws IllegalArgumentException when an entry is not a valid address or prefix (the entry is named, nothing else)
   */
  static IpRanges parse(String csv) {
    List<Range> list = new ArrayList<>();
    if (csv != null) {
      for (String raw : csv.split(",")) {
        String entry = raw.trim();
        if (!entry.isEmpty()) {
          list.add(parseOne(entry));
        }
      }
    }
    return new IpRanges(List.copyOf(list));
  }

  private static Range parseOne(String entry) {
    String[] parts = entry.split("/", 2);
    byte[] address = literal(parts[0].trim());
    if (address == null) {
      throw new IllegalArgumentException("not an IP address: " + entry);
    }
    int max = address.length * 8;
    int prefix = max;
    if (parts.length == 2) {
      try {
        prefix = Integer.parseInt(parts[1].trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("not a prefix length: " + entry);
      }
    }
    if (prefix < 0 || prefix > max) {
      throw new IllegalArgumentException("prefix out of range: " + entry);
    }
    return new Range(address, prefix);
  }

  /**
   * Whether an address is inside any range.
   *
   * @param ip a literal IPv4 or IPv6 address, or null/anything else (then false)
   * @return true when it is inside a range of the same family
   */
  boolean contains(String ip) {
    byte[] address = literal(ip);
    if (address == null) {
      return false;
    }
    for (Range range : ranges) {
      if (range.network().length == address.length && matches(range, address)) {
        return true;
      }
    }
    return false;
  }

  /** @return true when no range is configured */
  boolean isEmpty() {
    return ranges.isEmpty();
  }

  private static boolean matches(Range range, byte[] address) {
    int full = range.prefix() / 8;
    for (int i = 0; i < full; i++) {
      if (range.network()[i] != address[i]) {
        return false;
      }
    }
    int rest = range.prefix() % 8;
    if (rest == 0) {
      return true;
    }
    int mask = 0xFF << (8 - rest);
    return (range.network()[full] & mask) == (address[full] & mask);
  }

  /** The bytes of a literal address; null for anything that is not an IP literal (never resolves a name). */
  private static byte[] literal(String text) {
    if (text == null || !text.matches("[0-9a-fA-F:.]{2,45}")) {
      return null;
    }
    try {
      return InetAddress.getByName(text).getAddress();
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
