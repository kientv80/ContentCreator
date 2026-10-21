package com.contentcreator.news;

/**
 * An active row of ElderEase's news_category table: what a bulletin section needs to be headed and filed.
 *
 * @param code the primary key, e.g. {@code HEALTH}
 * @param emoji the emoji shown before the heading
 * @param nameVi the Vietnamese display name, e.g. "Sức khỏe"
 */
public record NewsCategory(String code, String emoji, String nameVi) {}
