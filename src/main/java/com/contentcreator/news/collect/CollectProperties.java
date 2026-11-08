package com.contentcreator.news.collect;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the AI news collection ({@code contentcreator.news.collect.*}).
 *
 * @param requireGrounding whether a story's site must also be among the domains Google Search reported (default true;
 *     switch off only if the grounding behaviour turns out not to return publisher domains)
 * @param titleDedupeDays a story whose normalized title was stored for the same country within this many days is a duplicate (default 7)
 */
@ConfigurationProperties("contentcreator.news.collect")
public record CollectProperties(@DefaultValue("true") boolean requireGrounding, @DefaultValue("7") int titleDedupeDays) {}
