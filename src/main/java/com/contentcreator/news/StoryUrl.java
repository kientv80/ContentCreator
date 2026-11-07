package com.contentcreator.news;

/**
 * An article link a draft was built from, remembered so the same article is not collected again.
 *
 * @param hash SHA-256 (64 hex characters) of the normalized URL
 * @param url the article URL as the model gave it
 */
public record StoryUrl(String hash, String url) {}
